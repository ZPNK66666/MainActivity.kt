package com.example.myapplication.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ключ VLESS + XTLS-Reality.
 *
 * Один ключ = одна строка в таблице `keys`. Когда ПК (Hiddify/happ)
 * подключается по VLESS-ссылке, сервер ищет ключ по UUID и проверяет
 * лимиты (трафик ГБ / срок дни).
 *
 * @author ZPNK666
 */
@Entity(
    tableName = "keys",
    indices = [Index(value = ["uuid"], unique = true)]   // UUID уникален
)
data class KeyEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** VLESS UUID — уникальный идентификатор ключа (vless://uuid@ip:port). */
    val uuid: String,

    /** Человекочитаемое имя ключа (например "ПК-стол", "Ноутбук ZPNK666"). */
    val name: String,

    /** Страна-маркер для отображения в Hiddify (например "🇷🇺 RU", "🇺🇸 US"). */
    val country: String = "🇷🇺 RU",

    /** Какое устройство использует этот ключ (например "Ноутбук", "ПК-стол"). */
    val device: String = "Телефон",

    /**
     * Публичный ключ Reality (base64 x25519).
     * Генерируется один раз при создании ключа и вшивается в VLESS-ссылку (pbk=...).
     * Приватный ключ (sid) хранится отдельно в конфиге Xray.
     */
    val pbk: String,

    /**
     * Лимит трафика в ГБ, либо null = бесконечно.
     * Проверка: bytesUsed >= trafficLimitGb * 1_073_741_824 → ключ блокируется.
     */
    val trafficLimitGb: Long? = null,

    /**
     * Срок действия в днях от [createdAt], либо null = навсегда.
     * Поле [expiresAt] хранит вычисленную дату окончания.
     */
    val timeLimitDays: Int? = null,

    /** Потрачено байт на момент последней проверки TrafficMonitor'ом. */
    val bytesUsed: Long = 0L,

    /** Дата создания ключа (epoch millis). */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Дата окончания действия (epoch millis), либо null = бессрочно.
     * Вычисляется как createdAt + timeLimitDays * 86_400_000, если [timeLimitDays] != null.
     */
    val expiresAt: Long? = null,

    /** Последняя активность по этому ключу (epoch millis). */
    val lastUsedAt: Long? = null,

    /** Включён ли ключ (можно выключать вручную, не удаляя). */
    val isActive: Boolean = true,
)
