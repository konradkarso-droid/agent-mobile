package com.uroboros.initiative

import com.uroboros.llm.ConversationJournal
import com.uroboros.llm.ConversationTimes
import com.uroboros.llm.ConversationTurns

/**
 * Источник «что нового с прошлого раза» — любопытство разрыва (решение и
 * строка — [GapCuriosity], без своих порогов). Отметок не ставит: «не чаще
 * раза за молчание» держит общее условие одного сообщения без ответа.
 *
 * ПРОШЛЫЙ РАЗГОВОР — ConversationTurns.lastTalk: живая лента, если в ней
 * есть ходы, иначе последний разговор архива ленты.
 *
 * ЧЕГО НЕ УМЕЕТ: после комы прошлым разговором считается архивный, пока лента
 * не поднята (см. ConversationTurns.lastTalk). После подъёма ленты условия
 * спрашиваются заново (AgentService.checkInitiative), и решение берётся уже
 * по ней.
 */
class GapSource(
    private val turns: ConversationTurns,
    private val times: ConversationTimes,
) : InitiativeSource {

    override suspend fun offer(): InitiativeSource.Offer {
        val ownerAt = runCatching { times.read().ownerReplyAt }.getOrElse {
            return InitiativeSource.Offer.Silent("разрыв — не прочиталось, когда писал собеседник")
        }
        val last = lastTalk() ?: return InitiativeSource.Offer.Silent("разрыв — архив ленты не прочитался")
        return when (val d = GapCuriosity.decide(ownerAt?.let { System.currentTimeMillis() - it }, last)) {
            is GapCuriosity.Decision.Refuse -> InitiativeSource.Offer.Silent(d.reason)
            is GapCuriosity.Decision.Ask -> InitiativeSource.Offer.Say(
                line = d.line,
                what = d.what,
                onSent = {},
                onAppended = {},
            )
        }
    }

    /** Ходы прошлого разговора; null — архив не прочитался. */
    private suspend fun lastTalk(): List<ConversationJournal.Turn>? = turns.lastTalk()?.turns
}
