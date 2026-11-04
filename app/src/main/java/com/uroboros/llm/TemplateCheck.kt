package com.uroboros.llm

/**
 * Дошло ли до модели всё, что код отдал движку: каждое сообщение запроса
 * ищется в запросе, собранном шаблоном модели (его движок отдаёт как
 * «последний запрос», `nativeGetLastConversationPrompt` в `gguf_lib.cpp`).
 *
 * ЗАЧЕМ. Шаблон разговора лежит в файле модели, и код его не выбирает. Шаблон
 * вправе выбросить или переписать сообщение — и делает это молча: запрос
 * уходит, ответ приходит, на экране всё как обычно. Так шаблон Falcon-H1
 * выбрасывает все системные сообщения, кроме первого. Без сверки это видно
 * только по выгрузке запроса.
 *
 * КАК. Сообщения ищутся по порядку: каждое — после места, где нашлось
 * предыдущее дошедшее. Текст сравнивается без пробелов по краям: шаблон
 * вправе их срезать.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - сообщения короче [MIN_CHARS] знаков не сверяются вовсе: короткое («Да.»,
 *    «679.») находится внутри чужого текста, сходит за дошедшее и сдвигает
 *    поиск за собой — тогда выброшенным назвалось бы соседнее, дошедшее.
 *    Выброшенное короткое прибор не видит;
 *  - длинное сообщение, целиком стоящее внутри другого, найдётся и там;
 *  - роль не сверяется: сообщение, дошедшее под другой ролью, считается
 *    дошедшим;
 *  - сообщение, которое шаблон переписал (экранировал, обрезал), считается
 *    выброшенным — промах в сторону тревоги, его видно;
 *  - стену движок ставит сам, в отданных сообщениях её нет, и она здесь не
 *    сверяется.
 *
 * Чистый объект.
 */
object TemplateCheck {

    /** Сообщения [sent] (роль — текст), которых нет в собранном запросе [prompt], по порядку. */
    fun lost(sent: List<Pair<String, String>>, prompt: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var from = 0
        for (message in sent) {
            val text = message.second.trim()
            if (text.length < MIN_CHARS) continue
            val at = prompt.indexOf(text, from)
            if (at < 0) out += message else from = at + text.length
        }
        return out
    }

    /**
     * Последний запрос, где шаблон что-то выбросил: [clock] — когда, [whose] —
     * чей запрос словами, [lost] — выброшенные сообщения, [total] — сколько
     * сообщений было отдано.
     */
    data class Loss(val clock: String, val whose: String, val lost: List<Pair<String, String>>, val total: Int)

    /**
     * Счёт сверок с подъёма процесса: [checked] — сверено запросов, [withLoss]
     * — из них с выброшенным, [unchecked] — не сверено (движок не отдал
     * собранный запрос), [lastLoss] — последний случай с выброшенным. Ряд, а не
     * один последний запрос: запрос без системных сообщений между двумя с
     * ними иначе прятал бы потерю.
     */
    data class Tally(val checked: Int = 0, val withLoss: Int = 0, val unchecked: Int = 0, val lastLoss: Loss? = null)

    /** [t] после сверки ещё одного запроса; пустой [prompt] — движок запроса не отдал. */
    fun add(t: Tally, clock: String, whose: String, sent: List<Pair<String, String>>, prompt: String): Tally {
        if (prompt.isEmpty()) return t.copy(unchecked = t.unchecked + 1)
        val lost = lost(sent, prompt)
        if (lost.isEmpty()) return t.copy(checked = t.checked + 1)
        return t.copy(checked = t.checked + 1, withLoss = t.withLoss + 1, lastLoss = Loss(clock, whose, lost, sent.size))
    }

    /**
     * Строка прибора для «Подробно». [source] — чей шаблон собирает запрос
     * словами (ChatTemplates); null — модель ещё не загружалась.
     */
    fun line(t: Tally, source: String?): String {
        val head = "Шаблон" + (source?.let { " ($it)" } ?: "")
        val skipped = if (t.unchecked > 0) " · не сверено: ${t.unchecked}" else ""
        if (t.checked == 0) return "$head: сверенных запросов с подъёма ещё нет$skipped"
        val loss = t.lastLoss
            ?: return "$head: запросов с подъёма ${t.checked}, все сообщения дошли до модели$skipped"
        val shown = loss.lost.take(SHOWN_LOST).joinToString("; ") { (role, text) -> "${roleWord(role)} «${quote(text)}»" }
        val more = loss.lost.size - SHOWN_LOST
        return "$head выбрасывает сообщения: запросов с подъёма ${t.checked}, с выброшенным ${t.withLoss}$skipped · " +
            "последний раз ${loss.clock}, ${loss.whose}: из ${loss.total} выброшено ${loss.lost.size} — $shown" +
            (if (more > 0) " и ещё $more" else "")
    }

    private fun roleWord(role: String): String = when (role) {
        "system" -> "системное"
        "user" -> "собеседника"
        "assistant" -> "агента"
        else -> role
    }

    private fun quote(text: String): String {
        val one = text.trim().replace('\n', ' ')
        return if (one.length <= QUOTE_CHARS) one else one.take(QUOTE_CHARS).trimEnd() + "…"
    }

    /**
     * Сообщения короче — не сверяются (почему — в шапке). Объявленное число, не
     * подобранное: длиннее частых коротких ответов («Понял.», «Хорошо.»).
     */
    const val MIN_CHARS = 12

    /** Объявленные числа, не подобранные: сколько выброшенных назвать и сколько знаков каждого. */
    private const val SHOWN_LOST = 2
    private const val QUOTE_CHARS = 40
}
