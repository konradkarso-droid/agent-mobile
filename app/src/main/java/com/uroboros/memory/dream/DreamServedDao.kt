package com.uroboros.memory.dream

import androidx.room.Dao
import androidx.room.Query

/**
 * Единственный путь, который пишет отметку «подан» (см. [Dream.servedCount]).
 *
 * ОТДЕЛЬНО ОТ [DreamDao] намеренно. Показ снов на экране ходит через DreamDao и
 * обязан только читать: будь отметка там же, показ получил бы и её, и
 * просмотр раздела «Сны» однажды засчитался бы как подача модели. Здесь это
 * исключено устройством — у показа такого DAO нет.
 */
@Dao
interface DreamServedDao {

    /** @return сколько строк отмечено; 0 — такого сна в базе нет. */
    @Query(
        "UPDATE dreams SET servedCount = servedCount + 1, lastServedAt = :at " +
            "WHERE nightAt = :nightAt AND recordIds = :recordIds"
    )
    suspend fun markServed(nightAt: Long, recordIds: String, at: Long): Int
}
