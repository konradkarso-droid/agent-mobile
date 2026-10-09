package com.uroboros.initiative

import com.uroboros.llm.ConversationJournal
import com.uroboros.llm.ConversationTimes
import com.uroboros.llm.ConversationTurns

/**
 * Источник «что нового с прошлого раза» — любопытство разрыва (решение и
 * строка — [GapCuriosity], без своих порогов). Отметок не ставит: «не чаще
 * раза за молчание» держит общее условие одного сообщения без ответа.
 *
 * ПРОШЛЫЙ РАЗГОВОР — живая лента, если в ней есть ходы, иначе последний
 * разговор архива ленты.
 *
 * ЧЕГО НЕ УМЕЕТ: после комы лента в памяти пуста, пока тело не загрузит модель
 * и не поднимет её с диска; до этого прошлым разговором считается последний
 * архивный. После подъёма ленты условия спрашиваются заново (AgentService
 * .checkInitiative), и решение берётся уже по ней.
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
    private suspend fun lastTalk(): List<ConversationJournal.Turn>? {
        val live = turns.journal.history()
        if (live.isNotEmpty()) return live
        val archive = turns.store.readArchive() ?: return null
        val lastIndex = archive.maxOfOrNull { it.archiveIndex } ?: return emptyList()
        return archive.filter { it.archiveIndex == lastIndex }
            .sortedBy { it.turnIndex }
            .map { ConversationJournal.Turn(it.userContent, it.agentContent, it.question, at = it.at) }
    }
}
