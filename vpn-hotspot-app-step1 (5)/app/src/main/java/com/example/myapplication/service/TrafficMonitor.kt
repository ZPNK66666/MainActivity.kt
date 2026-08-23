package com.example.myapplication.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myapplication.config.XrayConfigGenerator
import com.example.myapplication.data.db.AppDatabase
import com.example.myapplication.data.db.entity.KeyEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Мониторинг трафика Xray.
 *
 * Каждые [intervalMs] запускает `xray api stats` (subprocess), парсит
 * per-key uplink/downlink, обновляет счётчики в Room (атомарно через
 * [com.example.myapplication.data.db.dao.KeyDao.addBytesUsed]) и отключает
 * ключи при превышении лимита трафика (ГБ) или срока (дни).
 *
 * Статистика Xray — кумулятивная (суммарно с момента старта), поэтому
 * храним последний снимок в [lastSeen] и пишем в БД только дельту,
 * иначе счётчик задваивался бы на каждом опросе.
 *
 * При отключении ключа — шлём ACTION_RELOAD в [VpnServerService], чтобы
 * ядро перезагрузило конфиг без этого клиента.
 *
 * @author ZPNK666
 */
class TrafficMonitor(
    private val context: Context,
    private val binaryFile: File,
    private val apiPort: Int = XrayConfigGenerator.API_PORT,
    private val intervalMs: Long = 5_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val dao = AppDatabase.getInstance(context).keyDao()

    /** Последний кумулятивный снимок per-email (чтобы считать дельту). */
    private val lastSeen = mutableMapOf<String, Long>()

    fun start() {
        if (job?.isActive == true) return
        lastSeen.clear()
        job = scope.launch {
            Log.i(TAG, "TrafficMonitor запущен (интервал ${intervalMs}мс, api=$apiPort)")
            while (isActive) {
                delay(intervalMs)
                runCatching { pollOnce() }.onFailure {
                    Log.w(TAG, "Опрос статистики не удался: ${it.message}")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        Log.i(TAG, "TrafficMonitor остановлен")
    }

    // ───────────────────────────────────────────────────────────────
    //  Один цикл опроса
    // ───────────────────────────────────────────────────────────────

    private suspend fun pollOnce() {
        val stats = queryStats() ?: return          // email → кумулятивные байты
        if (stats.isEmpty()) return

        val keys: List<KeyEntity> = dao.observeAll().first()
        if (keys.isEmpty()) return

        var disabledAny = false

        // 1) Обновляем счётчики дельтой
        for (key in keys) {
            val email = emailFor(key)
            val cumulative = stats[email] ?: continue
            val last = lastSeen[email] ?: 0L
            // дельта; если Xray рестартнул (cumulative < last) — пишем весь cumulative
            val delta = if (cumulative >= last) cumulative - last else cumulative
            if (delta > 0) {
                dao.addBytesUsed(key.uuid, delta)
            }
            lastSeen[email] = cumulative
        }

        // 2) Проверяем лимиты и отключаем просроченные
        val expiredTraffic = dao.getExpiredByTraffic()
        val expiredTime = dao.getExpiredByTime()
        val allExpired = (expiredTraffic + expiredTime).distinctBy { it.uuid }
        for (key in allExpired) {
            dao.setActive(key.uuid, active = false)
            disabledAny = true
            Log.w(TAG, "Ключ «${key.name}» отключён: превышен лимит (ГБ=${key.trafficLimitGb}, дней=${key.timeLimitDays})")
        }

        // 3) Если ключи отключились — перезагружаем конфиг Xray
        if (disabledAny) {
            triggerReload()
        }
    }

    /** Шлём сервису команду перезагрузить конфиг (без него Xray держал бы старый список клиентов). */
    private fun triggerReload() {
        runCatching {
            val intent = Intent(context, VpnServerService::class.java)
                .setAction(VpnServerService.ACTION_RELOAD)
            context.startService(intent)
        }.onFailure { Log.w(TAG, "Не удалось отправить reload: ${it.message}") }
    }

    /** email-тег ключа (совпадает с тем, что ставит XrayConfigGenerator). */
    private fun emailFor(key: KeyEntity): String = "key-${key.uuid}"

    // ───────────────────────────────────────────────────────────────
    //  Запуск `xray api stats` + парсинг
    // ───────────────────────────────────────────────────────────────

    /** @return email → кумулятивные байты (uplink+downlink), либо null при ошибке. */
    private fun queryStats(): Map<String, Long>? = try {
        val pb = ProcessBuilder(
            binaryFile.absolutePath,
            "api", "stats",
            "--server=127.0.0.1:$apiPort",
        ).redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return null
        }
        parseStats(out)
    } catch (e: Exception) {
        Log.w(TAG, "queryStats: ${e.message}")
        null
    }

    /**
     * Парсит вывод `xray api stats`:
     *   - Name: key-<uuid>
     *     ↑ 123 bytes
     *     ↓ 45 bytes
     * Берём только Name, начинающиеся с "key-" (per-клиентские).
     */
    private fun parseStats(text: String): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        var currentName: String? = null
        var up = 0L
        var down = 0L

        fun flush() {
            currentName?.let { name ->
                if (name.startsWith("key-")) {
                    result[name] = up + down
                }
            }
        }

        text.lineSequence().forEach { line ->
            nameRegex.find(line)?.let { m ->
                flush()
                currentName = m.groupValues[1].trim()
                up = 0L
                down = 0L
            } ?: currentName?.let {
                upRegex.find(line)?.let { up = it.groupValues[1].toLongOrNull() ?: 0L }
                downRegex.find(line)?.let { down = it.groupValues[1].toLongOrNull() ?: 0L }
            }
        }
        flush()
        return result
    }

    companion object {
        private const val TAG = "TrafficMonitor"
        private val nameRegex = Regex("""Name:\s*(.+)""")
        // ↑ ↓ — стрелки в выводе xray. Дополнительно ловим uplink:/downlink: на случай другого формата.
        private val upRegex = Regex("""(?:↑|uplink:?)\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val downRegex = Regex("""(?:↓|downlink:?)\s*(\d+)""", RegexOption.IGNORE_CASE)
    }
}
