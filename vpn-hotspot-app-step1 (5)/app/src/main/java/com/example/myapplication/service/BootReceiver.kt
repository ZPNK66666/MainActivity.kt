package com.example.myapplication.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Авто-старт сервера после загрузки устройства.
 *
 * Шаг 3: здесь будет проверка настройки "auto-start on boot" (DataStore)
 * и запуск [VpnServerService].
 *
 * @author ZPNK666
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            // TODO(Шаг 3): if (autoStartEnabled) context.startForegroundService(...)
        }
    }
}
