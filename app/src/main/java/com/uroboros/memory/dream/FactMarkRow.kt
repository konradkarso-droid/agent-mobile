package com.uroboros.memory.dream

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Ночная отметка у предложения записи: факт это о собеседнике или реплика
 * момента. Что отмечается, зачем и чего отметка не умеет — у
 * nav.PersonFacts; ставит её [FactMarkStep].
 *
 * Запись памяти отметка не меняет: ни текст, ни слой, ни касания. Отвергнутая
 * или удалённая запись своих отметок не теряет, но и в портрет не идёт —
 * портрет её не берёт.
 */
@Entity(tableName = "fact_marks", primaryKeys = ["recordId", "sentence"])
data class FactMarkRow(
    val recordId: Long,
    val sentence: String,
    /** Имя nav.PersonFacts.Verdict. */
    val verdict: String,
    val markedAt: Long,
)

@Dao
interface FactMarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: FactMarkRow)

    @Query("SELECT * FROM fact_marks")
    suspend fun all(): List<FactMarkRow>
}
