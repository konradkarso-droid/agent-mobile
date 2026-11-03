package com.uroboros.gate

import com.uroboros.llm.ConversationJournal
import com.uroboros.memory.nav.Prochnost

/**
 * Что модель читает на ходе чата телеграма: лента своего чата и больше ничего
 * из памяти. Стену ставит движок сам (LlmEngine.generateChatFlow).
 *
 * ПОЧЕМУ БЕЗ ЗАПИСЕЙ ПАМЯТИ. Решение владельца: в телеграме агент опирается
 * на стену и ленту чата. Записи с экрана — разговоры владельца; поданные
 * здесь, они ушли бы пересказом к другому человеку (агент принимает «можно
 * делиться» на свой счёт), а угонщик телеграма владельца вытянул бы память
 * вопросами. Граница — здесь: сюда не приходит ничего, кроме строк ленты чата.
 *
 * ГОЛОСА — правило подачи моста агента: агент — ассистент; все остальные, и
 * владелец в телеграме тоже, — собеседник с подписью «Имя:». Подряд идущие
 * чужие строки — одним сообщением собеседника, по строке на реплику, подпись
 * с первого знака (пробел перед ней сдвигает границу реплики на токен).
 *
 * ЧТО ЧИТАЕТ МОДЕЛЬ — только строки, которые кольцо отдаёт модели
 * (AddressSorter.modelReads), и свои ответы агента; общее группы — нет.
 *
 * ОКНО — с конца ленты, пока влезает в [budgetChars]; текущая строка — всегда.
 * Начинается окно со строки собеседника: ответ агента без реплики, на которую
 * он отвечал, читался бы его собственной затравкой.
 *
 * СТРОКИ СИСТЕМЫ — блоками «[Мне от системы: …]» в сообщении собеседника, как
 * у экрана (ConversationJournal.toAgent): где агент (первым сообщением окна) и
 * проверка на прочность (перед текущей репликой). Собирает их код, не текст
 * людей.
 *
 * ЧЕГО НЕ УМЕЕТ: окно режется по знакам, а не по токенам; длинная лента чата
 * дальше окна для модели не существует; строк о себе, снов, записей — нет.
 *
 * Чистый объект.
 */
object ChatFeed {

    /** Объявленное число, не подобранное: окно ленты чата для модели, знаков. */
    const val BUDGET_CHARS = 2400

    /** Строка ленты для подачи: свой ответ агента или реплика с подписью. */
    data class Line(val isAgent: Boolean, val signature: String, val text: String, val modelReads: Boolean)

    /**
     * Сообщения модели для ответа на последнюю строку [lines] (по порядку,
     * старые первыми). [place] — строка «где я» (см. [placeLine]); [prochnost]
     * — строка проверки на прочность к текущей реплике или null.
     */
    fun messages(lines: List<Line>, place: String, prochnost: String?, budgetChars: Int = BUDGET_CHARS): List<Pair<String, String>> {
        require(lines.isNotEmpty() && !lines.last().isAgent) { "последней должна быть реплика собеседника" }
        val read = lines.filter { it.isAgent || it.modelReads }.let { if (it.lastOrNull() === lines.last()) it else it + lines.last() }
        val window = ArrayList<Line>()
        var used = 0
        for (line in read.asReversed()) {
            val cost = line.text.length + line.signature.length + 2
            if (window.isNotEmpty() && used + cost > budgetChars) break
            window += line
            used += cost
        }
        window.reverse()
        while (window.isNotEmpty() && window.first().isAgent) window.removeAt(0)

        val out = ArrayList<Pair<String, String>>()
        val pending = ArrayList<String>()
        fun flush(last: Boolean) {
            if (pending.isEmpty()) return
            val blocks = ArrayList<String>(3)
            if (out.isEmpty()) blocks += ConversationJournal.toAgent(place)
            if (last && !prochnost.isNullOrBlank()) {
                // Строка прочности — перед текущей репликой: её «ниже» указывает на неё.
                if (pending.size > 1) blocks += pending.dropLast(1).joinToString("\n")
                blocks += ConversationJournal.toAgent(prochnost)
                blocks += pending.last()
            } else blocks += pending.joinToString("\n")
            out += ConversationJournal.ROLE_USER to blocks.joinToString("\n\n")
            pending.clear()
        }
        for ((i, line) in window.withIndex()) {
            if (line.isAgent) {
                flush(last = false)
                out += ConversationJournal.ROLE_ASSISTANT to line.text
            } else {
                pending += "${line.signature}: ${line.text}"
                if (i == window.lastIndex) flush(last = true)
            }
        }
        return out
    }

    /**
     * Строка проверки на прочность к реплике чата [line] или null. Реплика из
     * телеграма никогда не «от владельца»: права даёт экран за замком, а не
     * аккаунт телеграма, — даже отмеченный «это я». [agentSaid] — свои ответы
     * агента в этом чате, по порядку; [selfLines] — строки о себе (стена).
     */
    fun prochnostNote(line: String, agentSaid: List<String>, selfLines: List<String>): String? {
        val trigger = prochnostTrigger(line)
        val found = if (trigger is Prochnost.Trigger.Active && Prochnost.Kind.OWN_WORDS in trigger.kinds)
            Prochnost.foundIn(agentSaid, line) else emptyList()
        return Prochnost.note(trigger, line, selfLines, found)
    }

    /** Включение проверки на прочность для реплики чата — всегда как не от владельца (см. [prochnostNote]). */
    fun prochnostTrigger(line: String): Prochnost.Trigger = Prochnost.trigger(line, fromOwner = false, painShift = null)

    /**
     * Строка «где я» — первым блоком окна. [chatTitle] — название группы;
     * [ownerSignature] — подпись владельца, если он отмечен и пишет в этом окне.
     */
    fun placeLine(direct: Boolean, chatTitle: String?, ownerSignature: String?): String {
        val where = if (direct) "Это телеграм, личка. Собеседник подписан своим именем из телеграма."
        else "Это телеграм, группа${chatTitle?.let { " «$it»" } ?: ""}. Каждая реплика подписана именем автора; не всё в группе сказано мне."
        return if (ownerSignature == null) where else "$where «$ownerSignature» — мой владелец, отмечен на экране телефона."
    }
}
