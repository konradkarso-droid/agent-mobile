package com.uroboros.memory.dream

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Спрошенная пара фактов одного собеседника: отменяет ли новый старый. Что
 * спрашивается, зачем и что значит ответ — у nav.FactAging; ставит
 * [FactAgingStep]. Хранится с любым ответом: спрошенная пара второй раз не
 * спрашивается.
 *
 * Запись памяти пара не меняет: «отменяет» убирает старую строку только из
 * портрета.
 */
@Entity(tableName = "fact_pairs", primaryKeys = ["oldRecordId", "oldSentence", "newRecordId", "newSentence"])
data class FactPairRow(
    val oldRecordId: Long,
    val oldSentence: String,
    val newRecordId: Long,
    val newSentence: String,
    /** Имя nav.FactAging.Verdict. */
    val verdict: String,
    val askedAt: Long,
)

@Dao
interface FactPairDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: FactPairRow)

    @Query("SELECT * FROM fact_pairs")
    suspend fun all(): List<FactPairRow>
}
