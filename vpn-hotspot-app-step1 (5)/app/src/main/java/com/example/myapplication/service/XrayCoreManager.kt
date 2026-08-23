package com.example.myapplication.service

import android.content.Context
import android.util.Log
import com.example.myapplication.config.ClientInfo
import com.example.myapplication.config.RealityKeyPair
import com.example.myapplication.config.XrayConfigGenerator
import com.example.myapplication.config.XrayConfigInput
import com.example.myapplication.data.db.AppDatabase
import com.example.myapplication.data.db.entity.KeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Запускает и останавливает ядро Xray-core на телефоне.
 *
 * Реализация: запускает бинарник `xray` (скомпилированный под Android,
 * лежит в `assets/xray/xray`) как subprocess с `-c config.json`.
 * Это рабочий подход, который используют v2rayNG / Hiddify.
 *
 * Бинарник `xray` нужно один раз собрать под arm64-v8a / armeabi-v7a / x86_64
 * и положить в `app/src/main/assets/xray/xray`. Менеджер сам распакует его
 * в `filesDir/xray/` и сделает исполняемым.
 *
 * Гео-базы `geoip.dat` / `geosite.dat` кладутся в `assets/xray/` и тоже
 * распаковываются; Xray ищет их по переменной окружения `XRAY_LOCATION_ASSET`.
 *
 * @author ZPNK666
 */
class XrayCoreManager(private val context: Context) {

    private var process: Process? = null

    /** Живёт ли сейчас процесс Xray. */
    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Запускает ядро Xray с готовым JSON-конфигом.
     * @param configJson строка из [XrayConfigGenerator.generate]
     * @return true если процесс стартовал
     */
    fun start(configJson: String): Boolean {
        if (isRunning) {
            Log.w(TAG, "Xray уже запущен — игнорирую повторный start")
            return true
        }
        return try {
            val binary = ensureBinaryExtracted()
            copyGeoAssets()
            val cfg = writeConfig(configJson)

            val pb = ProcessBuilder(
                binary.absolutePath,
                "run",
                "-c", cfg.absolutePath,
            )
                .redirectErrorStream(true)
                .directory(workDir)

            // Xray ищет geoip.dat / geosite.dat по этой переменной
            pb.environment()["XRAY_LOCATION_ASSET"] = assetsDir.absolutePath
            pb.environment()["TZ"] = "UTC"

            process = pb.start()
            startLogReader(process!!)
            Log.i(TAG, "Xray запущен (pid=${process!!.pid}), config=${cfg.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запустить Xray", e)
            process = null
            false
        }
    }

    /**
     * Удобный запуск: сам собирает конфиг из Room (ключи) +
     * серверной пары Reality (SharedPreferences) и стартует.
     * @return true если запущено; false — если активных ключей нет
     */
    suspend fun startWithCurrentConfig(): Boolean = withContext(Dispatchers.IO) {
        val config = buildXrayConfig(context)
        if (config.isEmpty()) {
            Log.w(TAG, "Нет активных ключей в БД — Xray не запускается")
            return@withContext false
        }
        start(config)
    }

    /** Останавливает ядро: SIGTERM, затем SIGKILL если не вышло за 2с. */
    fun stop() {
        process?.let { p ->
            runCatching {
                p.destroy()                                   // SIGTERM
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly()                        // SIGKILL
                    p.waitFor(1, TimeUnit.SECONDS)
                }
            }
            Log.i(TAG, "Xray остановлен")
        }
        process = null
    }

    /** Перезапуск с новым конфигом (например, при добавлении/удалении ключа). */
    fun restart(configJson: String) {
        stop()
        start(configJson)
    }

    /** Путь к распакованному бинарнику xray (для TrafficMonitor'а). */
    fun binary(): File = ensureBinaryExtracted()

    // ───────────────────────────────────────────────────────────────
    //  Файловая инфраструктура
    // ───────────────────────────────────────────────────────────────

    private val workDir: File get() = File(context.filesDir, "xray").apply { mkdirs() }
    private val assetsDir: File get() = File(workDir, "assets").apply { mkdirs() }
    private val binaryFile: File get() = File(workDir, "xray")
    private val configDest: File get() = File(workDir, "config.json")

    /** Распаковывает бинарник xray из assets и делает исполняемым. */
    private fun ensureBinaryExtracted(): File {
        if (binaryFile.exists() && binaryFile.canExecute()) return binaryFile
        context.assets.open("xray/xray").use { input ->
            binaryFile.outputStream().use { input.copyTo(it) }
        }
        binaryFile.setExecutable(true, true)
        Log.i(TAG, "Бинарник xray распакован: ${binaryFile.absolutePath}")
        return binaryFile
    }

    /** Копирует geoip.dat / geosite.dat из assets в рабочую папку. */
    private fun copyGeoAssets() {
        listOf("geoip.dat", "geosite.dat").forEach { name ->
            val target = File(assetsDir, name)
            if (target.exists()) return@forEach
            runCatching {
                context.assets.open("xray/$name").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }.onFailure {
                Log.w(TAG, "Не найден assets/xray/$name — routing по гео может не работать")
            }
        }
    }

    private fun writeConfig(json: String): File {
        configDest.writeText(json)
        return configDest
    }

    /** Читает stdout/stderr процесса в фоне (чтобы pipe не переполнился). */
    private fun startLogReader(p: Process) {
        Thread {
            try {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> Log.d(TAG, "[xray] $line") }
                }
            } catch (_: Exception) {
                // процесс умер — нормально
            }
        }.apply {
            isDaemon = true
            name = "xray-log-reader"
        }.start()
    }

    companion object {
        private const val TAG = "XrayCoreManager"

        // Ключи SharedPreferences для серверной пары Reality
        // (те же, что использует DashboardViewModel — единый источник правды).
        private const val PREFS = "vpn_server_prefs"
        private const val KEY_PRIV = "reality_private_key"
        private const val KEY_PBK = "reality_public_key"
        private const val KEY_SID = "reality_short_id"

        /**
         * Собирает JSON-конфиг Xray по текущему состоянию:
         * ключи из Room + серверная пара Reality из SharedPreferences.
         * @return "" если активных ключей нет.
         */
        suspend fun buildXrayConfig(context: Context): String = withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).keyDao()
            val keys: List<KeyEntity> = dao.observeAll().first()
            if (keys.isEmpty()) return@withContext ""

            val reality = ensureServerReality(context)
            val clients = keys
                .filter { it.isActive }
                .map { ClientInfo(uuid = it.uuid, email = it.name) }
            if (clients.isEmpty()) return@withContext ""

            XrayConfigGenerator.generate(
                XrayConfigInput(
                    port = 10808,
                    clients = clients,
                    realitySni = "www.google.com",
                    realityKeyPair = RealityKeyPair(reality.privateKey, reality.publicKey),
                    shortIds = listOf(reality.shortId, ""),
                )
            )
        }

        /** Гарантирует наличие серверной пары Reality (генерит при первом вызове). */
        private fun ensureServerReality(context: Context): Reality {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pub = prefs.getString(KEY_PBK, null)
            val priv = prefs.getString(KEY_PRIV, null)
            val sid = prefs.getString(KEY_SID, null)
            if (pub != null && priv != null && sid != null) {
                return Reality(priv, pub, sid)
            }
            val pair = XrayConfigGenerator.generateRealityKeyPair()
            val newSid = XrayConfigGenerator.generateShortId()
            prefs.edit()
                .putString(KEY_PRIV, pair.privateKey)
                .putString(KEY_PBK, pair.publicKey)
                .putString(KEY_SID, newSid)
                .apply()
            return Reality(pair.privateKey, pair.publicKey, newSid)
        }

        /** Внутренний держатель серверной пары Reality. */
        private data class Reality(
            val privateKey: String,
            val publicKey: String,
            val shortId: String,
        )
    }
}
