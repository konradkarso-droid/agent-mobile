package com.uroboros.memory.dream

import androidx.room.Dao
import androidx.room.Query

/**
 * Единственный путь, который пишет отметки «подан» (см. [Dream.servedCount]),
 * «подхвачен» (см. [Dream.pickedUpCount]), «спрошен» и «ответ» (см.
 * [Dream.askedAt]).
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

    /**
     * Отметить сон подхваченным владельцем (см. [DreamPickup]). Только счёт и
     * время у самого сна: ни записи, ни слои не трогаются.
     */
    @Query(
        "UPDATE dreams SET pickedUpCount = pickedUpCount + 1, lastPickedUpAt = :at " +
            "WHERE nightAt = :nightAt AND recordIds = :recordIds"
    )
    suspend fun markPickedUp(nightAt: Long, recordIds: String, at: Long)

    /**
     * Когда сон спрошен; null — не спрашивался или такого сна нет. Читается
     * в момент отметки подхвата (см. [DreamPickupMarker]), а не берётся из
     * снимка, снятого при подаче.
     */
    @Query("SELECT askedAt FROM dreams WHERE nightAt = :nightAt AND recordIds = :recordIds")
    suspend fun askedAt(nightAt: Long, recordIds: String): Long?

    /**
     * Отметить, что владельцу предложено спросить о сне (см. [CuriosityAsk]).
     * @return сколько строк отмечено; 0 — такого сна в базе нет.
     */
    @Query("UPDATE dreams SET askedAt = :at WHERE nightAt = :nightAt AND recordIds = :recordIds")
    suspend fun markAsked(nightAt: Long, recordIds: String, at: Long): Int

    /**
     * Отметить ответ владельца о спрошенном сне (см. [Dream.answeredCount]).
     * Только счёт и время у самого сна, как у подхвата.
     */
    @Query(
        "UPDATE dreams SET answeredCount = answeredCount + 1, lastAnsweredAt = :at " +
            "WHERE nightAt = :nightAt AND recordIds = :recordIds"
    )
    suspend fun markAnswered(nightAt: Long, recordIds: String, at: Long)
}
