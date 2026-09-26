package com.uroboros.memory.dream

import com.uroboros.memory.RiskTrigger

/**
 * Вывод из сна: ночью модель одной фразой называет, что связывает записи сна.
 * Здесь сборка запроса, разбор ответа, проверка слов, выбор снов на ночь и
 * слова для отчёта и экрана; ни базы, ни Android — по образцу [Mirror] и
 * [SelfLine]. Модель зовёт и пишет в базу [ConclusionStep], показ —
 * [ConclusionView].
 *
 * ЧТО ТАКОЕ ВЫВОД. Мысль агента о связи записей, которую сон нашёл по дешёвым
 * признакам. Это НЕ факт, НЕ память и НЕ подтверждено человеком: вывод не
 * лежит в записях памяти, очереди проверки не проходит, судье и модели не
 * подаётся. Единственная защита — проверка слов кодом ([check]).
 *
 * ПОЧЕМУ СВОЯ ТАБЛИЦА, а не сны и не записи, — в KDoc [ConclusionRow].
 *
 * ЗАЧЕМ. Вывод закрывает связь: сон, по которому есть принятый вывод, больше
 * не сжимает пружину любопытства (см. [CuriosityPressure], «РАЗРЯДКА»).
 * Отброшенный вывод сон не разряжает.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - проверка сравнивает слова, а не смысл: «X противоположно Y» пройдёт так
 *    же, как «X похоже на Y»;
 *  - банальный вывод («связаны с работой») не отсеется, если слова есть в
 *    записях;
 *  - фраза, оборванная потолком токенов ([ANSWER_TOKENS]), не отличается от
 *    законченной — это видно только на экране;
 *  - вывод из слов одной записи пройдёт, хотя ничего не связывает.
 */
object Conclusion {

    /** Системная инструкция. Своя, не судьи и не строки о себе. */
    const val SYSTEM =
        "Тебе дают несколько записей из памяти. Одной фразой скажи, что их связывает, — так, " +
            "чтобы фраза могла идти после слов «Я подумал, что». Бери слова из самих записей. " +
            "Без кавычек и пояснений."

    /** Потолок выдачи. Объявленное число, не подобранное. */
    const val ANSWER_TOKENS = 24

    /** Сколько снов пробуется за ночь. Объявленное число, не подобранное. */
    const val MAX_PER_NIGHT = 3

    /** Самый длинный вывод в словах. Объявленное число, не подобранное. */
    const val MAX_WORDS = 20

    /** Самый длинный вывод в знаках. Объявленное число, не подобранное. */
    const val MAX_CHARS = 160

    /**
     * Связующие слова. Фраза о связи почти всегда содержит слово самой связи,
     * а в записях его нет: без этого списка проверка отбросила бы почти каждый
     * вывод. Хранятся словами, а основы считаются из них тем же правилом, что
     * у вывода ([LINK_STEMS]), — чтобы не зависеть от устройства основы.
     * Объявленный список, не подобранный. Это не список поиска
     * (HourglassMemory.STOP_WORDS) и с ним не связан.
     */
    val LINK_WORDS = listOf(
        "связь", "связи", "связан", "связана", "связано", "связаны", "связывает", "связывают",
        "общее", "общий", "общая", "общего", "вместе", "похожи", "похоже", "похож", "одинаково",
        "записи", "запись",
        // Слово самой подсказки ([SYSTEM]: «одной фразой»): модель подхватывает
        // его и называет записи «фразами».
        "фраза", "фразы", "фразой",
    )

    /** Основы [LINK_WORDS]: при проверке они не считаются лишними. */
    val LINK_STEMS: Set<String> = LINK_WORDS.flatMapTo(HashSet()) { RiskTrigger.significantStems(it) }

    // ---- Выбор снов на ночь ----

    /** Какие сны пробовать этой ночью, или почему ни одного. */
    sealed class Pick {
        data class Dreams(val dreams: List<CuriosityPressure.Leader>) : Pick()
        data class Silent(val reason: String) : Pick()
    }

    /**
     * Сны на эту ночь: из ряда снов, дающих давление ([CuriosityPressure.Result.ranked],
     * лидер первым), пропустить пробовавшиеся ([tried]: есть строка вывода,
     * принятая или отброшенная) и взять первые [MAX_PER_NIGHT].
     *
     * [pressure] — давление целиком ([CuriosityPressure.Result.pressure]); нужно
     * только затем, чтобы честно назвать причину пустого ряда. Ряд пуст и при
     * ненулевом давлении, когда всё оно — от ответов о спрошенных снах: такой
     * сон разряжен и в ряд не входит (см. [CuriosityPressure], «РАЗРЯДКА»).
     *
     * Почему пробовавшийся сон не пробуется снова — в KDoc [ConclusionStep].
     */
    fun pick(ranked: List<CuriosityPressure.Leader>, tried: Set<ConclusionKey>, pressure: Int = 0): Pick {
        if (ranked.isEmpty()) {
            return Pick.Silent(
                if (pressure > 0) "давление только от ответов о спрошенных снах — связывать нечего"
                else "давление любопытства ноль — связывать нечего"
            )
        }
        val triedRecords = ConclusionKey.sameRecords(tried)
        val fresh = ranked.filter { it.dream.recordIds !in triedRecords }
        if (fresh.isEmpty()) return Pick.Silent("все сны, дающие давление, уже пробовались")
        return Pick.Dreams(fresh.take(MAX_PER_NIGHT))
    }

    // ---- Сборка запроса ----

    /** Живые записи сна — каждая с новой строки в «ёлочках», в порядке цепочки. */
    fun request(records: List<String>): String = records.joinToString("\n") { "«${it.trim()}»" }

    // ---- Разбор ответа ----

    sealed class Parsed {
        data class Text(val text: String) : Parsed()

        /** @property raw первая непустая строка ответа как есть; может быть пустой. */
        data class Refused(val raw: String, val reason: String) : Parsed()
    }

    /** Кавычки, которые снимаются с краёв вывода. */
    private const val QUOTES = "\"'«»„“”‘’`"

    /** Ведущее «Я подумал, что» / «Я подумала что», если модель его написала. */
    private val LEAD = Regex("^я\\s+подумала?\\s*,?\\s*что\\s*", RegexOption.IGNORE_CASE)

    /**
     * Ответ модели — в вывод. Берётся первая непустая строка; снимаются
     * кавычки по краям, ведущее «Я подумал, что» и точка в конце. Отказ —
     * пусто, длиннее [MAX_WORDS] слов или [MAX_CHARS] знаков.
     */
    fun parse(answer: String): Parsed {
        val raw = answer.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        var text = raw.trim { it.isWhitespace() || it in QUOTES }
        LEAD.find(text)?.let { text = text.substring(it.range.last + 1) }
        text = text.trim { it.isWhitespace() || it in QUOTES }
            .trimEnd('.', ' ')
            .trim { it.isWhitespace() || it in QUOTES }
        if (text.isEmpty()) return Parsed.Refused(raw, "модель не дала вывода")
        if (text.split(Regex("\\s+")).size > MAX_WORDS) {
            return Parsed.Refused(raw, "вывод длиннее $MAX_WORDS слов")
        }
        if (text.length > MAX_CHARS) return Parsed.Refused(raw, "вывод длиннее $MAX_CHARS знаков")
        return Parsed.Text(text)
    }

    // ---- Проверка ----

    /**
     * Взят ли вывод из записей сна: значимые основы вывода минус [LINK_STEMS]
     * непусты и целиком входят в основы записей [records]. null — прошёл;
     * иначе причина словами. Лишние основы называются: по ним видно, что
     * модель досочинила.
     */
    fun check(text: String, records: List<String>): String? {
        val own = RiskTrigger.significantStems(text) - LINK_STEMS
        if (own.isEmpty()) return "в выводе нет слов записей"
        val extra = own - records.flatMapTo(HashSet()) { RiskTrigger.significantStems(it) }
        if (extra.isEmpty()) return null
        return "основ ${extra.sorted().joinToString(", ") { "«$it»" }} нет в записях сна"
    }

    // ---- Слова для отчёта ночи и экрана ----

    /** Начало строки итога — по нему итог узнаётся в отчёте ночи и в приборе. */
    const val OUTCOME_HEAD = "Выводы: "

    /**
     * Сколько знаков причин отказа печатать в итоге ночи. Объявленное число,
     * не подобранное: чтобы строка итога оставалась строкой.
     */
    const val OUTCOME_CHARS = 160

    fun silentOutcome(reason: String): String =
        "${OUTCOME_HEAD}не делаю — " + reason.replaceFirstChar { it.lowercaseChar() }

    /**
     * Шаг звал модель. [dropped] — причины отброшенных выводов по порядку;
     * [stoppedBy] — почему шаг кончился раньше, чем прошёл все сны; null — прошёл.
     */
    fun doneOutcome(made: Int, dropped: List<String>, stoppedBy: String? = null): String = buildString {
        append(OUTCOME_HEAD).append("сделано ").append(made).append(", отброшено ").append(dropped.size)
        val tail = dropped + listOfNotNull(stoppedBy?.let { "дальше не делаю: $it" })
        if (tail.isNotEmpty()) append(" — ").append(cut(tail.joinToString("; "), OUTCOME_CHARS))
    }

    /** Вывод для владельца. Единственное место текста подписи. */
    fun shown(text: String): String = "Я подумал, что $text."

    internal fun cut(text: String, chars: Int): String {
        val flat = text.replace("\n", " ")
        return if (flat.length > chars) flat.take(chars) + "…" else flat
    }
}
