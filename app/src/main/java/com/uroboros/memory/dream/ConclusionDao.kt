package com.uroboros.memory.dream

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Таблица выводов ([ConclusionRow]) и итог шага выводов в строке ночи
 * ([DreamNight.conclusionsOutcome]). Пишет только шаг ночи
 * ([ConclusionStep]); показ ([ConclusionView]) и счёт разрядки любопытства
 * ([CuriosityGauge]) только читают.
 *
 * Итог ночи живёт здесь, а не в [DreamDao], чтобы у того и его подделок в
 * тестах ничего не менялось.
 */
@Dao
interface ConclusionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: ConclusionRow): Long

    /** Строки последних [nights] ночей с выводами: от новых ночей к старым, внутри ночи по порядку. */
    @Query(
        "SELECT * FROM conclusions WHERE nightAt IN " +
            "(SELECT DISTINCT nightAt FROM conclusions ORDER BY nightAt DESC LIMIT :nights) " +
            "ORDER BY nightAt DESC, id"
    )
    suspend fun lastNights(nights: Int): List<ConclusionRow>

    /** Ключи всех снов, по которым вывод пробовался, — принятый или отброшенный. */
    @Query("SELECT DISTINCT dreamNightAt, dreamRecordIds FROM conclusions")
    suspend fun triedKeys(): List<ConclusionKey>

    /** Ключи снов с принятым выводом — они разряжены (см. [CuriosityPressure]). */
    @Query("SELECT DISTINCT dreamNightAt, dreamRecordIds FROM conclusions WHERE accepted = 1")
    suspend fun acceptedKeys(): List<ConclusionKey>

    @Query("SELECT COUNT(*) FROM conclusions WHERE accepted = 1")
    suspend fun countAccepted(): Int

    /**
     * Дописать итог шага выводов в ночь [nightAt]. Ноль тронутых строк —
     * строки этой ночи нет (сон сорвался), итог остался только в отчёте.
     */
    @Query("UPDATE nights SET conclusionsOutcome = :outcome WHERE nightAt = :nightAt")
    suspend fun setOutcome(nightAt: Long, outcome: String): Int

    /** Итог шага выводов последней ночи, где он был; null — ни разу. */
    @Query(
        "SELECT conclusionsOutcome FROM nights WHERE conclusionsOutcome IS NOT NULL " +
            "ORDER BY nightAt DESC LIMIT 1"
    )
    suspend fun lastOutcome(): String?
}
