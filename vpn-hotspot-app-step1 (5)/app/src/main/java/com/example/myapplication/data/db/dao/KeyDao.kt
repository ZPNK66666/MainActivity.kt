package com.example.myapplication.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.myapplication.data.db.entity.KeyEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO для работы с ключами VLESS+Reality.
 * @author ZPNK666
 */
@Dao
interface KeyDao {

    /** Создать ключ. Возвращает id новой строки. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(key: KeyEntity): Long

    /** Обновить ключ целиком. */
    @Update
    suspend fun update(key: KeyEntity)

    /** Удалить ключ. */
    @Delete
    suspend fun delete(key: KeyEntity)

    /** Удалить ключ по id. */
    @Query("DELETE FROM keys WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Все ключи, отсортированные от новых к старым (для UI-списка). */
    @Query("SELECT * FROM keys ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<KeyEntity>>

    /** Активные ключи (для проверки при подключении ПК). */
    @Query("SELECT * FROM keys WHERE isActive = 1 ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<KeyEntity>>

    /** Один ключ по id. */
    @Query("SELECT * FROM keys WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): KeyEntity?

    /** Один ключ по UUID (быстрый lookup при авторизации подключения). */
    @Query("SELECT * FROM keys WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): KeyEntity?

    /** Активный ключ по UUID — используется сервером при handshake'е. */
    @Query("SELECT * FROM keys WHERE uuid = :uuid AND isActive = 1 LIMIT 1")
    suspend fun getActiveByUuid(uuid: String): KeyEntity?

    /**
     * Атомарно добавить потраченные байты к счётчику ключа
     * и обновить время последней активности.
     * Вызывается из TrafficMonitor после опроса статистики Xray.
     */
    @Query(
        """
        UPDATE keys
        SET bytesUsed = bytesUsed + :bytes,
            lastUsedAt = :now
        WHERE uuid = :uuid
        """
    )
    suspend fun addBytesUsed(uuid: String, bytes: Long, now: Long = System.currentTimeMillis())

    /** Включить/выключить ключ. */
    @Query("UPDATE keys SET isActive = :active WHERE uuid = :uuid")
    suspend fun setActive(uuid: String, active: Boolean)

    /** Сбросить счётчик трафика (например, при продлении). */
    @Query("UPDATE keys SET bytesUsed = 0 WHERE uuid = :uuid")
    suspend fun resetTraffic(uuid: String)

    /** Кол-во активных ключей (для notification-виджета). */
    @Query("SELECT COUNT(*) FROM keys WHERE isActive = 1")
    fun activeCount(): Flow<Int>

    /** Все ключи, у которых исчерпан лимит трафика (для авто-отключения). */
    @Query(
        """
        SELECT * FROM keys
        WHERE isActive = 1
          AND trafficLimitGb IS NOT NULL
          AND bytesUsed >= trafficLimitGb * 1073741824
        """
    )
    suspend fun getExpiredByTraffic(): List<KeyEntity>

    /** Все ключи, у которых истёк срок (для авто-отключения). */
    @Query(
        """
        SELECT * FROM keys
        WHERE isActive = 1
          AND expiresAt IS NOT NULL
          AND expiresAt <= :now
        """
    )
    suspend fun getExpiredByTime(now: Long = System.currentTimeMillis()): List<KeyEntity>
}
