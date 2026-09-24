package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.llm.ConversationTimes
import com.uroboros.memory.MemoryDatabase

/**
 * Исполнение выхода в базе: когда спрошено в последний раз и отметка
 * «спрошен» через [DreamServedDao]. Записи не читаются и не греются.
 *
 * @param ownerReplyAt когда ушла последняя реплика владельца (см. «ГДЕ ЖИВЁТ»
 *   у [CuriosityAsk]); бросает, если прочитать нельзя.
 */
class CuriosityAskMarker(
    private val dreams: DreamDao,
    private val served: DreamServedDao,
    private val ownerReplyAt: suspend () -> Long?,
) {

    constructor(context: Context) : this(
        MemoryDatabase.getInstance(context).dreamDao(),
        MemoryDatabase.getInstance(context).dreamServedDao(),
        ConversationTimes(context).let { times -> suspend { times.ownerReplyAt() } },
    )

    /** Ждёт ли прошлый вопрос ответа — см. [CuriosityAsk.awaiting]. Бросает, если не прочиталось. */
    suspend fun awaiting(): Boolean =
        CuriosityAsk.awaiting(dreams.lastAskedAt(), ownerReplyAt())

    /** Отметить сон лидера спрошенным. Звать, когда реплика ушла в движок. */
    suspend fun markAsked(leader: CuriosityPressure.Leader, at: Long) {
        served.markAsked(leader.dream.nightAt, leader.dream.recordIds, at)
    }
}
