package com.uroboros.initiative

import com.uroboros.llm.ConversationTurns
import com.uroboros.memory.dream.SelfGap
import com.uroboros.memory.dream.SelfGapReader

/**
 * Источник «выбрать себе имя»: тот же пробел о себе, что в ответе на вопрос
 * владельца об имени (dream.SelfGap), только без реплики — владелец молчит.
 * Решение и строка — SelfGap, без своих порогов; не чаще раза за разговор
 * (почему — у SelfGap.decideFirst).
 *
 * Отметок не ставит: что выбрать предлагалось, видно по самой ленте — строка
 * ложится в неё репликой хода.
 *
 * Зовётся раз в минуту бодрствования: читает ленту и архив (как экран на
 * каждом ходе), облако — только когда есть что сказать.
 */
class SelfGapSource(private val turns: ConversationTurns, private val reader: SelfGapReader) : InitiativeSource {

    override suspend fun offer(): InitiativeSource.Offer {
        val archive = turns.store.readArchive()
            ?: return InitiativeSource.Offer.Silent("имя — архив ленты не прочитался")
        val state = SelfGap.of(reader.turns(archive, turns.journal.history()))
        when (val d = SelfGap.decideFirst(state)) {
            is SelfGap.Decision.Refuse -> return InitiativeSource.Offer.Silent("имя — ${d.reason}")
            SelfGap.Decision.Offer -> Unit
        }
        val words = runCatching { reader.cloudWords(archive) }.getOrDefault(emptyList())
        return InitiativeSource.Offer.Say(
            line = InitiativeDecision.SILENCE_WORDS + " " + SelfGap.line(words),
            what = "выбрать себе имя",
            onSent = {},
            onAppended = {},
        )
    }
}
