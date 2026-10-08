package com.uroboros.memory.dream

import com.uroboros.memory.RiskTrigger

/**
 * Описание последней ночи для вопроса, адресованного агенту: «Этой ночью мне
 * снилось: <тема>, <тема> и <тема>.»
 *
 * РАМКУ СОБИРАЕТ КОД, ТЕМУ НАЗЫВАЕТ МОДЕЛЬ. Сюжет модель не сочиняет: одно
 * готовое описание малая модель передаёт верно, а достраивая — сочиняет. Поэтому
 * ночью ([DreamTopicStep]) модель на повторяемой выдаче называет тему каждого
 * сна одним-тремя словами, код проверяет, что основы темы взяты из записей
 * сна ([check], как у строки о себе — SelfLine.checkTopic), и собирает фразу
 * ([line]).
 *
 * УЗКОЕ ИСКЛЮЧЕНИЕ из правила «сны в разговор не подаются»: описание подаётся
 * только на вопрос с адресом «агент» (nav.Coordinates.questionAddress; просьба
 * без второго лица такого адреса не даёт) и не больше одного раза за ленту.
 * Всё прочее по-прежнему: записи снов в разговор не идут.
 *
 * Чистый объект: ни базы, ни модели, ни Android.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - тема из слов записей, а не из смысла: «код» и «программа» — разные темы;
 *  - проверка ловит досочинённое, но не плохое: слово из записей, не несущее
 *    темы (сокращение, опечатка), проходит;
 *  - «уже подано в этой ленте» сравнивается по тексту строки: две ночи с теми
 *    же темами дадут одну строку, и вторая не подастся.
 */
object DreamTopic {

    /** Системная инструкция. Своя, не судьи, не выводов и не строки о себе. */
    const val SYSTEM =
        "Тебе дают записи, из которых сплетён сон. Назови тему сна одним-тремя словами из самих " +
            "записей. Только тему, без кавычек и пояснений."

    /** Потолок выдачи. Объявленное число, не подобранное. */
    const val ANSWER_TOKENS = 12

    /** Сколько тем ночи принимается. Объявленное число, не подобранное. */
    const val MAX_DREAMS = 3

    /**
     * Сколько снов ночи спрашивается о теме, пока не набралось [MAX_DREAMS]
     * принятых. Объявленное число: проверка ([check]) отбрасывает большую часть
     * тем, а выдача повторяемая — сон, отброшенный раз, отбрасывается каждую
     * ночь, пока не изменятся его записи. Идти дальше по снам, а не
     * останавливаться на первых трёх, — единственный способ, каким ночь без
     * новых записей вообще получает темы.
     */
    const val MAX_ATTEMPTS = 10

    /**
     * Порядок, в каком сны спрашиваются: сначала сны с самой новой записью
     * (номер записи растёт со временем), при равенстве — по списку номеров.
     * «Этой ночью мне снилось» — о недавнем, а не о случайных снах.
     */
    fun <T> newestFirst(dreams: List<T>, ids: (T) -> List<Long>, key: (T) -> String): List<T> =
        dreams.sortedWith(compareByDescending<T> { ids(it).maxOrNull() ?: Long.MIN_VALUE }.thenBy(key))

    /** Самая длинная тема в словах. */
    const val MAX_WORDS = 3

    private val QUOTES = setOf('«', '»', '"', '\'', '“', '”', '„')

    fun request(records: List<String>): String = records.joinToString("\n") { "«${it.trim()}»" }

    /** Ответ модели — в тему, или причина отказа. */
    sealed class Parsed {
        data class Topic(val text: String) : Parsed()
        data class Refused(val raw: String, val reason: String) : Parsed()
    }

    /**
     * Первая непустая строка ответа без кавычек по краям и точки в конце;
     * отказ — пусто или длиннее [MAX_WORDS] слов.
     */
    fun parse(answer: String): Parsed {
        val raw = answer.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        val text = raw.trim { it.isWhitespace() || it in QUOTES }
            .trimEnd('.', ' ', '!')
            .trim { it.isWhitespace() || it in QUOTES }
        if (text.isEmpty()) return Parsed.Refused(raw, "модель не назвала темы")
        if (text.split(Regex("\\s+")).size > MAX_WORDS) {
            return Parsed.Refused(raw, "тема длиннее $MAX_WORDS слов")
        }
        return Parsed.Topic(text)
    }

    /**
     * Взята ли тема из записей сна: значимые основы темы непусты и целиком
     * входят в основы записей. null — прошла; иначе причина словами.
     */
    fun check(topic: String, records: List<String>): String? {
        val own = RiskTrigger.significantStems(topic)
        if (own.isEmpty()) return "в теме «$topic» нет значимых слов"
        val extra = own - records.flatMapTo(HashSet()) { RiskTrigger.significantStems(it) }
        if (extra.isEmpty()) return null
        return "основ ${extra.sorted().joinToString(", ") { "«$it»" }} нет в записях сна"
    }

    // ---- Хранение в строке ночи (DreamNight.dreamTopics) ----

    fun store(topics: List<String>): String = topics.joinToString("\n")

    fun load(stored: String?): List<String> =
        stored?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()

    // ---- Фраза для модели ----

    /**
     * «Этой ночью мне снилось: A, B и C.» и, если ночь дала принятый вывод
     * [thought], — «Во сне я подумал, что …» ([Conclusion.dreamt]). Нет ни
     * тем, ни вывода — null. Вывод — один, последний принятый этой ночи: на
     * вопрос о снах отвечает описание, а не перечень мыслей.
     */
    fun line(topics: List<String>, thought: String? = null): String? {
        val night = if (topics.isEmpty()) null else {
            val joined = if (topics.size == 1) topics[0]
            else topics.dropLast(1).joinToString(", ") + " и " + topics.last()
            "Этой ночью мне снилось: $joined."
        }
        return listOfNotNull(night, thought?.let { Conclusion.dreamt(it) }).joinToString(" ").ifEmpty { null }
    }

    /**
     * Подавать ли описание на этом ходе; null — подавать, иначе причина
     * молчания для прибора.
     *
     * @param toAgent вопрос с адресом «агент».
     * @param ribbonEmpty лента пуста — системное сообщение первым не ставится
     *   никогда (ConversationJournal.messagesFor), ждать следующего хода.
     * @param alreadyInRibbon эта строка уже подана в этой ленте.
     * @param stored колонка тем последней ночи как есть (DreamNight.dreamTopics):
     *   null — шаг тем у этой ночи не проходил, пустая строка — прошёл и не
     *   принял ни одной темы. Причины отказов — только в отчёте ночи.
     */
    fun refusal(
        line: String?,
        toAgent: Boolean,
        ribbonEmpty: Boolean,
        alreadyInRibbon: Boolean,
        stored: String?,
    ): String? = when {
        !toAgent -> "вопрос не к агенту"
        line == null && stored == null -> "шага тем у последней ночи не было"
        line == null -> "ночью ни одна тема и ни один вывод не приняты"
        ribbonEmpty -> "лента пуста — первым системное не ставится"
        alreadyInRibbon -> "уже рассказан в этой ленте"
        else -> null
    }

    // ---- Прибор «Сон в зеркале:» ----

    /** Сутки для счёта рассказанного. */
    const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Сколько раз описание ночи ушло модели за сутки до [now]: по времени ходов,
     * где оно подано ([at] хода с непустым dreamNote). Ход без времени не
     * считается — неизвестно, в какие сутки он был.
     */
    fun toldWithinDay(told: List<Long?>, now: Long): Int = told.count { it != null && now - it in 0 until DAY_MS }

    /** Строка прибора: счёт за сутки и что было в этом ходе. */
    fun meter(toldToday: Int, refusal: String?): String =
        "Сон в зеркале: рассказан за сутки: $toldToday · " +
            (if (refusal == null) "в этом ходе подан" else "в этом ходе молчу — $refusal")

    // ---- Слова для отчёта ночи ----

    const val OUTCOME_HEAD = "Темы снов: "

    fun silentOutcome(reason: String): String =
        "${OUTCOME_HEAD}не делаю — " + reason.replaceFirstChar { it.lowercaseChar() }

    fun doneOutcome(accepted: List<String>, dropped: List<String>, stoppedBy: String? = null): String = buildString {
        append(OUTCOME_HEAD).append("принято ").append(accepted.size).append(", отброшено ").append(dropped.size)
        if (accepted.isNotEmpty()) append(" (").append(accepted.joinToString(", ")).append(")")
        val tail = dropped + listOfNotNull(stoppedBy?.let { "дальше не делаю: $it" })
        if (tail.isNotEmpty()) append(" — ").append(tail.joinToString("; ").take(OUTCOME_CHARS))
    }

    /** Сколько знаков причин печатать в итоге. Объявленное число. */
    const val OUTCOME_CHARS = 160
}
