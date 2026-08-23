package com.example.myapplication

import android.app.Application

/**
 * Точка входа приложения.
 *
 * Здесь (Шаг 2) будет инициализация [com.example.myapplication.di.AppContainer]
 * с Room-базой, репозиториями и DataStore.
 */
class VpnHotspotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: VpnHotspotApp
            private set
    }
}
