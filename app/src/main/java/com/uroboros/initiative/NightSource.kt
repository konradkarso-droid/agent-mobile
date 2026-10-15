package com.uroboros.initiative

import android.content.Context
import com.uroboros.llm.ConversationTurns
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.dream.CuriosityAskMarker
import com.uroboros.memory.dream.CuriosityGauge
import com.uroboros.memory.dream.DreamTopic

/**
 * Источник «расскажу, что снилось»: описание последней ночи как несказанное
 * (memory.dream.DreamTopic, «НЕСКАЗАННАЯ НОЧЬ»), пока владелец молчит. Правило
 * и порог — DreamTopic.unpromptedRefusal, своих нет; реплики нет, поэтому
 * «в теме» не бывает, только «набралось».
 *
 * ОТМЕТКА — САМ ХОД. Описание уходит системным сообщением хода (Say.dreamNote)
 * и ложится в ход ленты: по нему ночь и считается рассказанной, как в ответе
 * на реплику. Своих отметок источник не ставит.
 *
 * Мысль этой ночи, если есть, входит в описание (DreamTopic.line) и
 * отмечается рассказанной, когда строка ушла в движок, — как в ответе на
 * реплику.
 *
 * ЧЕГО НЕ УМЕЕТ: на пустой ленте молчит — системное сообщение первым не
 * ставится (DreamTopic.refusal), ждёт хода владельца.
 */
class NightSource(context: Context, private val turns: ConversationTurns) : InitiativeSource {

    private val db = MemoryDatabase.getInstance(context.applicationContext)
    private val gauge = CuriosityGauge(context)
    private val marker = CuriosityAskMarker(context)

    override suspend fun offer(): InitiativeSource.Offer {
        val dreams = db.dreamDao()
        val night = dreams.lastNight() ?: return InitiativeSource.Offer.Silent("ночь — ночей не было")
        val topics = DreamTopic.load(night.dreamTopics)
        val thought = db.conclusionDao().lastAcceptedText(night.nightAt)
        val line = DreamTopic.line(topics, thought)
        val ribbon = turns.journal.history()
        val archive = turns.store.readArchive()
            ?: return InitiativeSource.Offer.Silent("ночь — архив ленты не прочитался")
        val lastTold = (ribbon.filter { it.dreamNote != null }.map { it.at } +
            archive.filter { it.dreamNote != null }.map { it.at }).filterNotNull().maxOrNull()
        val withTopics = dreams.nightsWithTopics().map { it.nightAt }
        DreamTopic.unpromptedRefusal(
            line, topics,
            lastNightUntold = lastTold == null || night.nightAt > lastTold,
            untold = DreamTopic.untoldNights(withTopics, lastTold),
            reply = "",
            ribbonEmpty = ribbon.isEmpty(),
        )?.let { return InitiativeSource.Offer.Silent("ночь — $it") }
        return InitiativeSource.Offer.Say(
            line = InitiativeDecision.SILENCE_WORDS + " " + DreamTopic.FIRST_LINE,
            what = "ночь «${topics.joinToString(", ")}»",
            onSent = { at ->
                thought?.let { text -> gauge.read().toTell.firstOrNull { it.text == text } }
                    ?.let { marker.markAsked(it.leader, at) }
            },
            onAppended = {},
            dreamNote = line,
        )
    }
}
