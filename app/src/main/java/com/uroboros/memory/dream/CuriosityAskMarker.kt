package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.MemoryDatabase

/**
 * Исполнение выхода в базе: когда спрошено в последний раз и отметка
 * «спрошен» через [DreamServedDao]. Записи не читаются и не греются.
 */
class CuriosityAskMarker(
    private val dreams: DreamDao,
    private val served: DreamServedDao,
) {

    constructor(context: Context) : this(
        MemoryDatabase.getInstance(context).dreamDao(),
        MemoryDatabase.getInstance(context).dreamServedDao(),
    )

    /** Ждёт ли прошлый вопрос ответа — см. [CuriosityAsk.awaiting]. */
    suspend fun awaiting(): Boolean =
        CuriosityAsk.awaiting(dreams.lastAskedAt(), CuriosityAsk.lastOwnerReplyAt())

    /** Отметить сон лидера спрошенным. Звать, когда реплика ушла в движок. */
    suspend fun markAsked(leader: CuriosityPressure.Leader, at: Long) {
        served.markAsked(leader.dream.nightAt, leader.dream.recordIds, at)
    }
}
