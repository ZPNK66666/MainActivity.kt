package com.example.myapplication.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.example.myapplication.MainActivity
import com.example.myapplication.R

/**
 * ForegroundService, который держит VPN-сервер включённым.
 *
 * Делает три вещи:
 *  1. **PARTIAL_WAKE_LOCK** — процессор телефона не засыпает, пока крутится
 *     TCP-сервер Xray на порту 10808.
 *  2. **WifiLock (WIFI_MODE_FULL_HIGH_PERF)** — Wi-Fi-радио не уходит в
 *     power-save и 5GHz-канал не отваливается под нагрузкой.
 *  3. **Постоянное уведомление** — чтобы Android не убил сервис (обязательно
 *     для foreground-сервисов с API 26+, и для Android 14+ нужен тип `dataSync`).
 *
 * Дополнительно: запускает [XrayCoreManager] с конфигом из Room и
 * [TrafficMonitor] для опроса статистики + лимитов.
 *
 * @author ZPNK666
 */
class VpnServerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Короутин-скоуп для асинхронного запуска Xray. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Менеджер ядра Xray (subprocess). */
    private var xrayManager: XrayCoreManager? = null

    /** Монитор трафика (опрос `xray api stats`, обновление БД, лимиты). */
    private var trafficMonitor: TrafficMonitor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // ───────────────────────────────────────────────────────────────────
    //  Жизненный цикл
    // ───────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        xrayManager = XrayCoreManager(this)
        trafficMonitor = TrafficMonitor(this, xrayManager!!.binary())
        Log.i(TAG, "VpnServerService создан: WakeLock + WifiLock + XrayCoreManager + TrafficMonitor готовы")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Получен ACTION_STOP — останавливаю сервер")
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                // Перезагрузить конфиг (ключи добавились/удалились/исчерпали лимит).
                // startForeground обязателен, если сервис вдруг был убит и стартует заново.
                startForegroundCompat()
                scope.launch {
                    val config = XrayCoreManager.buildXrayConfig(this@VpnServerService)
                    if (config.isNotEmpty()) {
                        xrayManager?.restart(config)
                        trafficMonitor?.start()
                    }
                }
                return START_STICKY
            }
            else -> {
                startForegroundCompat()
                // Запускаем Xray с конфигом, собранным из Room + prefs.
                scope.launch {
                    xrayManager?.startWithCurrentConfig()
                    trafficMonitor?.start()   // опрос статистики после старта ядра
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        trafficMonitor?.stop()
        xrayManager?.stop()
        scope.cancel()
        releaseLocks()
        Log.i(TAG, "VpnServerService уничтожен: Xray+TrafficMonitor остановлены, WakeLock + WifiLock освобождены")
        super.onDestroy()
    }

    // ───────────────────────────────────────────────────────────────────
    //  Foreground + уведомление
    // ───────────────────────────────────────────────────────────────────

    private fun startForegroundCompat() {
        val notification = buildNotification("VPN-сервер активен · порт 10808")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: тип foreground-сервиса обязателен
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundAndSelf() {
        trafficMonitor?.stop()
        xrayManager?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Сервер раздачи интернета",
            NotificationManager.IMPORTANCE_LOW,   // LOW — без звука, но видно
        ).apply {
            description = "Постоянное уведомление, пока работает VPN-сервер ZPNK666"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        // Тап по уведомлению — открыть приложение
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Кнопка "Остановить" в уведомлении — послать ACTION_STOP
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VpnServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ZPNK666 · VPN-сервер")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)                            // несдвигаемое
            .setContentIntent(openIntent)
            .addAction(0, "Остановить", stopIntent)
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ───────────────────────────────────────────────────────────────────
    //  WakeLock + WifiLock
    // ───────────────────────────────────────────────────────────────────

    private fun acquireLocks() {
        // 1) PARTIAL_WAKE_LOCK — CPU не засыпает. Только экран может потухнуть.
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG,
        ).apply {
            setReferenceCounted(false)
            acquire()   // без таймаута: живёт пока сервис жив (отпустим в onDestroy)
        }

        // 2) WifiLock HIGH_PERF — Wi-Fi не уходит в power-save,
        //    5GHz-канал не отваливается при большой нагрузке.
        //    Примечание: WIFI_MODE_FULL_HIGH_PERF устарел в API 34, но работает.
        @Suppress("DEPRECATION")
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            WIFILOCK_TAG,
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    // ───────────────────────────────────────────────────────────────────
    //  Публичный API для запуска/остановки из UI
    // ───────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VpnServerService"
        private const val CHANNEL_ID = "vpn_server_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "VpnHotspotApp:ServerWakeLock"
        private const val WIFILOCK_TAG = "VpnHotspotApp:ServerWifiLock"

        const val ACTION_START = "com.example.myapplication.action.START"
        const val ACTION_STOP = "com.example.myapplication.action.STOP"
        const val ACTION_RELOAD = "com.example.myapplication.action.RELOAD"

        /** Запустить сервер из ViewModel/Activity. */
        fun start(context: Context) {
            val intent = Intent(context, VpnServerService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Остановить сервер. */
        fun stop(context: Context) {
            val intent = Intent(context, VpnServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
