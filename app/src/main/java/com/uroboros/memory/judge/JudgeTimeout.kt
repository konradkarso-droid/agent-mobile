package com.uroboros.memory.judge

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

enum class JudgeStage(val label: String) {
    DIRECT("прямой судья"),
    PRINTED("печатный судья"),
}

/** Пара остаётся у человека после второй отсечки. Его решение хранится отдельно от вердиктов модели. */
@Entity(tableName = "judge_timeouts", primaryKeys = ["firstId", "secondId"])
data class JudgeTimeout(
    val firstId: Long,
    val secondId: Long,
    val stage: JudgeStage,
    val lastAt: Long,
    val repeated: Boolean = false,
    val humanDispute: Boolean? = null,
)

data class JudgePairIds(val firstId: Long, val secondId: Long)

@Dao
interface JudgeTimeoutDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: JudgeTimeout): Long

    @Query("UPDATE judge_timeouts SET repeated = 1, stage = :stage, lastAt = :at WHERE firstId = :first AND secondId = :second AND repeated = 0")
    suspend fun repeat(first: Long, second: Long, stage: JudgeStage, at: Long): Int

    /** Истина при второй отсечке и передаче пары человеку. */
    @Transaction
    suspend fun record(row: JudgeTimeout): Boolean {
        if (insert(row) != -1L) return false
        return repeat(row.firstId, row.secondId, row.stage, row.lastAt) == 1
    }

    @Query("SELECT * FROM judge_timeouts")
    suspend fun all(): List<JudgeTimeout>

    @Query("SELECT firstId, secondId FROM judge_timeouts WHERE repeated = 1 ORDER BY firstId, secondId")
    suspend fun held(): List<JudgePairIds>

    @Query("SELECT * FROM judge_timeouts WHERE repeated = 1 AND humanDispute IS NULL ORDER BY lastAt DESC, firstId, secondId")
    suspend fun pendingReview(): List<JudgeTimeout>

    @Query("DELETE FROM judge_timeouts WHERE firstId = :first AND secondId = :second AND repeated = 0")
    suspend fun clearFirstTimeout(first: Long, second: Long)

    @Query("DELETE FROM judge_timeouts WHERE firstId = :first AND secondId = :second AND repeated = 1 AND humanDispute IS NULL")
    suspend fun returnToQueue(first: Long, second: Long): Int

    @Query("UPDATE judge_timeouts SET humanDispute = :dispute WHERE firstId = :first AND secondId = :second AND repeated = 1 AND humanDispute IS NULL")
    suspend fun review(first: Long, second: Long, dispute: Boolean): Int

    @Query("DELETE FROM judge_timeouts WHERE firstId NOT IN (SELECT id FROM stickers) OR secondId NOT IN (SELECT id FROM stickers)")
    suspend fun forgetDeletedRecords()
}
