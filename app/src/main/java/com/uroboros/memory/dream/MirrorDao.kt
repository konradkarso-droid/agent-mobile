package com.uroboros.memory.dream

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Таблица зеркала ([MirrorVariant]). Пишут её только шаг ночи ([MirrorStep])
 * и сверка в ходе ([MirrorChecker]); показ ([MirrorView]) и прибор только
 * читают.
 */
@Dao
interface MirrorDao {

    /** Варианты одной ночи — одной вставкой. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(variants: List<MirrorVariant>)

    /**
     * Варианты, с которыми сверяется реплика в момент [at]: не сбывшиеся, с
     * незакрытым окном ([window] — [Mirror.CHECK_WINDOW]) и из ночи не позже
     * реплики.
     */
    @Query(
        "SELECT * FROM mirror WHERE fulfilledAt IS NULL AND repliesSeen < :window " +
            "AND nightAt <= :at"
    )
    suspend fun unsettled(window: Int, at: Long): List<MirrorVariant>

    /** Реплика сверена с вариантом, вариант не сбылся. */
    @Query("UPDATE mirror SET repliesSeen = :repliesSeen WHERE id = :id")
    suspend fun markSeen(id: Long, repliesSeen: Int)

    /** Реплика сверена с вариантом, вариант сбылся. */
    @Query(
        "UPDATE mirror SET repliesSeen = :repliesSeen, fulfilledAt = :at, " +
            "fulfilledWords = :words, fulfilledReply = :reply WHERE id = :id"
    )
    suspend fun markFulfilled(id: Long, repliesSeen: Int, at: Long, words: String, reply: String)

    /** Последние [nights] ночей с вариантами, от новых к старым. */
    @Query("SELECT DISTINCT nightAt FROM mirror ORDER BY nightAt DESC LIMIT :nights")
    suspend fun lastNights(nights: Int): List<Long>

    @Query("SELECT * FROM mirror WHERE nightAt = :nightAt ORDER BY id")
    suspend fun ofNight(nightAt: Long): List<MirrorVariant>

    /** Сколько вариантов сверено хотя бы с одной репликой. */
    @Query("SELECT COUNT(*) FROM mirror WHERE repliesSeen > 0")
    suspend fun countChecked(): Int

    /** Сколько вариантов сбылось. */
    @Query("SELECT COUNT(*) FROM mirror WHERE fulfilledAt IS NOT NULL")
    suspend fun countFulfilled(): Int
}
