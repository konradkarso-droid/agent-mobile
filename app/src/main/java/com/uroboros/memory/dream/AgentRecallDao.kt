package com.uroboros.memory.dream

import androidx.room.Dao
import androidx.room.Query

/**
 * Единственный путь, который пишет отметки вспоминания агентом (см.
 * Sticker.agentRecallCount и Sticker.lastAgentRecallAt).
 *
 * ОТДЕЛЬНО ОТ StickerDao намеренно, по той же причине, что [DreamServedDao]:
 * всё, что ходит через StickerDao, — выдача, судья, сон, показ — получило бы и
 * право согревать записи от имени агента. Здесь это право есть только у
 * [AgentRecaller].
 */
@Dao
interface AgentRecallDao {

    /** Прибавить счёт вспоминаний. Слой, срок и момент прогрева не трогаются. */
    @Query("UPDATE stickers SET agentRecallCount = agentRecallCount + 1 WHERE id = :id")
    suspend fun touchAgentRecall(id: Long)

    /**
     * Прогрев воспоминанием: слой, срок и момент прогрева одним запросом.
     * Порознь запрос мог бы оборваться посередине, и запись согрелась бы без
     * отметки, то есть обошла бы суточный предел на следующем же ходе.
     */
    @Query(
        "UPDATE stickers SET layer = :layer, expiryTime = :expiryTime, lastAgentRecallAt = :at " +
            "WHERE id = :id"
    )
    suspend fun warmByAgentRecall(id: Long, layer: String, expiryTime: Long?, at: Long)
}
