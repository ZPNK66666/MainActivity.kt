package com.example.myapplication.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.myapplication.data.db.dao.KeyDao
import com.example.myapplication.data.db.entity.KeyEntity

/**
 * Локальная база данных приложения.
 * Хранит ключи VLESS+Reality, их лимиты (ГБ/дни) и счётчики трафика.
 *
 * На Шаге 2 здесь один DAO ([KeyDao]).
 * Позже добавятся [com.example.myapplication.data.db.dao.SessionDao] (активные
 * подключения ПК) и [com.example.myapplication.data.db.dao.TrafficStatDao].
 *
 * @author ZPNK666
 */
@Database(
    entities = [KeyEntity::class],
    version = 2,   // v2: добавлены поля country + device
    exportSchema = false,   // включить перед release-билдом
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun keyDao(): KeyDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Синглтон базы. Потокобезопасный double-check.
         * В dev-режиме — [fallbackToDestructiveMigration], чтобы не писать
         * миграции при изменении схемы. Перед release добавить миграции.
         */
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vpn_hotspot.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
