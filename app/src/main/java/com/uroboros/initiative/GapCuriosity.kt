package com.uroboros.initiative

import com.uroboros.llm.ConversationJournal
import com.uroboros.memory.DolmenCircle

/**
 * Любопытство разрыва: чем дольше не говорили, тем больше неизвестного — что
 * изменилось у собеседника с прошлого раза. Давление растёт само, со временем,
 * и поэтому не пересыхает, как пересыхают сны. Чистая логика; входы собирает
 * [GapSource].
 *
 * ПОРОГ ЗАВИСИТ ОТ ТОГО, ЕСТЬ ЛИ ТЕМА, И ОТ ТОГО, У КОГО ХОД:
 *  - у прошлого разговора есть тема (слова реплик собеседника,
 *    DolmenCircle.themeWords) и ход остался у агента (InitiativeHolder) —
 *    [AGENT_MOVE_MS]: разговор оборвался на агенте, продолжить естественно ему;
 *  - тема есть, ход у собеседника — [TOPIC_MS];
 *  - темы нет — [NO_TOPIC_MS]: спросить можно только «что нового».
 * Числа объявлены, не подобраны; проверять по строке «Первым:» и счёту
 * «Первым за сутки».
 *
 * НЕ ЧАЩЕ РАЗА ЗА МОЛЧАНИЕ: это общее условие «одно сообщение без ответа»
 * (InitiativeDecision), здесь не повторяется.
 *
 * ТЕМА — НЕ ФАКТ. Строка называет слова прошлого разговора как то, о чём
 * говорили, а не как сказанное о собеседнике: «говорили о рубанке» — не «ты
 * любишь рубанок». Как спросить, решает модель.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - тема — по словам реплик, а не по смыслу; служебные слова отсекаются тем
 *    же списком, что у отбора памяти;
 *  - не знает, изменилось ли у собеседника что-то на самом деле;
 *  - молчание считается от последней реплики собеседника, а не от того, когда
 *    он последний раз открывал экран.
 */
object GapCuriosity {

    /** Тема есть, ход у агента. Объявленное число. */
    const val AGENT_MOVE_MS = 6L * 60 * 60 * 1000

    /** Тема есть, ход у собеседника. Объявленное число. */
    const val TOPIC_MS = 12L * 60 * 60 * 1000

    /** Темы нет. Объявленное число. */
    const val NO_TOPIC_MS = 24L * 60 * 60 * 1000

    /** Сколько слов темы называть модели. Объявленное число. */
    const val TOPIC_WORDS = 3

    sealed class Decision {
        /** Спросить; [line] — строка модели, [what] — о чём, для прибора. */
        data class Ask(val line: String, val what: String) : Decision()

        /** Не спрашивать; [reason] — почему, словами для прибора. */
        data class Refuse(val reason: String) : Decision()
    }

    /** Слова темы прошлого разговора; пусто — темы нет. */
    fun topic(lastTalk: List<ConversationJournal.Turn>): List<String> =
        DolmenCircle.themeWords(lastTalk.map { it.question }, emptyList()).take(TOPIC_WORDS)

    /** Порог молчания (описание объекта). */
    fun threshold(hasTopic: Boolean, agentHasMove: Boolean): Long = when {
        !hasTopic -> NO_TOPIC_MS
        agentHasMove -> AGENT_MOVE_MS
        else -> TOPIC_MS
    }

    /**
     * @param silenceMs сколько молчит собеседник; null — ещё не писал.
     * @param lastTalk ходы прошлого разговора в порядке ленты.
     */
    fun decide(silenceMs: Long?, lastTalk: List<ConversationJournal.Turn>): Decision {
        if (silenceMs == null) return Decision.Refuse("разрыв — собеседник ещё не писал")
        val words = topic(lastTalk)
        val agentMove = InitiativeHolder.read(lastTalk)?.holder == InitiativeHolder.Holder.AGENT
        val need = threshold(words.isNotEmpty(), agentMove)
        if (silenceMs < need) {
            val kind = when {
                words.isEmpty() -> "темы нет"
                agentMove -> "тема есть, ход у агента"
                else -> "тема есть, ход у собеседника"
            }
            return Decision.Refuse("разрыв — молчание ${hours(silenceMs)} ч из ${hours(need)} ($kind)")
        }
        return Decision.Ask(line(silenceMs, words), if (words.isEmpty()) "что нового" else "разрыв: ${words.joinToString(", ")}")
    }

    /**
     * Строка модели: сколько не говорили, о чём говорили в прошлый раз (если
     * есть), и просьба спросить. Без рода агента: «мы говорили», «спроси».
     */
    fun line(silenceMs: Long, words: List<String>): String {
        val gap = "Мы не говорили ${hours(silenceMs)} ч."
        val about = if (words.isEmpty()) "" else " В прошлый раз говорили о: ${words.joinToString(", ") { "«$it»" }}."
        return "$gap$about Спроси пользователя, что у него нового, одним вопросом."
    }

    private fun hours(ms: Long): Long = ms.coerceAtLeast(0) / (60L * 60 * 1000)
}
