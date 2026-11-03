package com.uroboros.gate

import com.uroboros.memory.nav.AddressSorter

/**
 * Отвечать ли агенту на сообщение чата сейчас — решение перед ходом модели.
 *
 * ОТВЕТ — МЕХАНИЗМ ТЕРМИНАЛЬНЫЙ: сказанное уходит с телефона и не
 * возвращается. Поэтому сомнительное не отправляется: любое «не знаю» —
 * молчание со строкой причины, а не ответ на удачу. Переспрос по кольцу
 * «сомнительно» — тоже отправка и проходит те же условия.
 *
 * ПОРЯДОК ПРОВЕРОК — порядок ARCHITECTURE.md §0: аварийный стоп и сторож
 * устройства первыми, затем согласие владельца, затем занятость и свежесть.
 *
 * ЧИСЛА — объявленные, не подобранные:
 *  - [MAX_BOT_ANSWERS_IN_A_ROW] — ответов ботам подряд без человека между: тот
 *    же предел 3, что у пакетов (GateTurns); документация телеграма требует
 *    предела от петель между ботами, чисел не даёт;
 *  - [STALE_MS], [STALE_NEWER] — «ветка ушла»: ответ через четверть часа или
 *    после двух десятков чужих сообщений читается ответом не на то. На живом
 *    чате не мерено;
 *  - [MAX_QUEUE] — ждущих ответа на чат.
 *
 * ЧЕГО НЕ УМЕЕТ: бота от человека отличает только по `is_bot` — агент на
 * обычном аккаунте для него человек, и предел глубины на него не действует;
 * суд не уступает телеграму — сообщение ждёт конца ночной работы, а
 * дождавшееся сверх срока ответа не получит.
 *
 * Чистый объект.
 */
object ChatReply {

    const val MAX_BOT_ANSWERS_IN_A_ROW = 3
    const val STALE_MS = 15 * 60_000L
    const val STALE_NEWER = 20
    const val MAX_QUEUE = 20

    /** Переспрос по кольцу «сомнительно» — строка кода, не модели. Объявленная. */
    const val ASK_TEXT = "Это мне?"

    data class Inputs(
        val answer: AddressSorter.Answer,
        val emergencyStop: Boolean,
        /** Почему сторож не даёт работать (зона не «норма», потолок), null — даёт. */
        val watchdogRefusal: String?,
        val consentValid: Boolean,
        val nightRunning: Boolean,
        val ageMs: Long,
        /** Чужих сообщений в ветке после этого. */
        val newer: Int,
        val senderIsBot: Boolean,
        /** Своих ответов подряд с последней реплики человека в ветке. */
        val answersSinceHuman: Int,
    )

    sealed class Decision {
        object Answer : Decision()
        object Ask : Decision()
        data class Silent(val why: String) : Decision()
    }

    fun decide(i: Inputs): Decision {
        if (i.answer == AddressSorter.Answer.SILENT) return Decision.Silent("кольцо велит молчать")
        if (i.emergencyStop) return Decision.Silent("аварийный стоп")
        i.watchdogRefusal?.let { return Decision.Silent("сторож: $it") }
        if (!i.consentValid) return Decision.Silent("нет разрешения отвечать (кнопка «Отвечать» на экране врат)")
        if (i.nightRunning) return Decision.Silent("идёт ночная работа")
        if (i.ageMs > STALE_MS) return Decision.Silent("ветка ушла: прошло ${i.ageMs / 60_000} мин")
        if (i.newer > STALE_NEWER) return Decision.Silent("ветка ушла: после него ${i.newer} сообщений")
        if (i.senderIsBot && i.answersSinceHuman >= MAX_BOT_ANSWERS_IN_A_ROW)
            return Decision.Silent("$MAX_BOT_ANSWERS_IN_A_ROW ответа ботам подряд без человека — молчу")
        return if (i.answer == AddressSorter.Answer.ASK) Decision.Ask else Decision.Answer
    }

    /** Строка ветки для [decide]: свой ли ответ и человек ли автор. */
    data class Turn(val isAgent: Boolean, val isBot: Boolean)

    /**
     * Своих ответов подряд с последней реплики человека: [turns] — строки ветки,
     * старые первыми. Реплика бота человеком не считается.
     */
    fun answersSinceHuman(turns: List<Turn>): Int {
        var n = 0
        for (t in turns.asReversed()) {
            if (t.isAgent) n++ else if (!t.isBot) break
        }
        return n
    }
}
