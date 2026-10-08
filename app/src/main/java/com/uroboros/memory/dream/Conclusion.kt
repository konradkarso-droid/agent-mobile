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
 * ПОЧЕМУ ПРОВЕРКА ДОПУСКАЕТ СВОИ СЛОВА. Сон связывает записи БЕЗ общих слов
 * (см. [DreamWeaver]: записи с общими словами не снятся), значит, фраза о
 * связи почти всегда называет то, чего нет ни в одной из них. Требование
 * «все слова вывода — из записей» отсекало бы как раз то, ради чего вывод
 * делается. Поэтому проверка требует другого: вывод держится за слова хотя
 * бы двух разных записей сна ([MIN_SUPPORT]) — иначе он ничего не связывает,
 * — и досочинённого в нём немного ([MAX_OWN_STEMS]). Остальное держит не
 * проверка, а подпись: вывод — мысль во сне, а не факт (см. [shown]).
 *
 * ПОЧЕМУ СВОЯ ТАБЛИЦА, а не сны и не записи, — в KDoc [ConclusionRow].
 *
 * ЗАЧЕМ. Вывод закрывает связь: сон, по которому есть принятый вывод, больше
 * не сжимает пружину любопытства (см. [CuriosityPressure], «РАЗРЯДКА»).
 * Отброшенный вывод сон не разряжает.
 *
 * ВТОРОЙ ШАНС У ОТБРОШЕННОГО — ДВА, И ОБА БЕЗ ПРОБЫ ВСЛЕПУЮ. Ответ модели на
 * тот же вопрос о тех же записях при той же загрузке повторяется дословно
 * (см. [ConclusionStep], «ВЫДАЧА ПОВТОРЯЕМАЯ»), а тексты записей не
 * переписываются. Поэтому повтор «через столько-то ночей» дал бы тот же ответ
 * и тот же отказ. Меняется ответ только с загрузкой или вопросом — тогда сон
 * пробуется снова ([askPrint], [pick]). Меняется приговор только с правилом
 * проверки — и для этого модель не нужна: прежний ответ лежит в таблице и
 * перепроверяется кодом ([recheck]).
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - проверка сравнивает слова, а не смысл: «X противоположно Y» пройдёт так
 *    же, как «X похоже на Y»;
 *  - банальный вывод («связаны с работой») не отсеется, если слова есть в
 *    записях;
 *  - опора на запись — общая основа слова, а не смысл: «ключ» от двери и
 *    «ключ» родника опорой засчитаются одинаково;
 *  - фраза, оборванная потолком токенов ([ANSWER_TOKENS]), не отличается от
 *    законченной — это видно только на экране;
 *  - отпечаток вопроса ([QUESTION_PRINT]) — только текст [SYSTEM] и потолок
 *    выдачи. Правка обвязки вопроса в другом месте (как записи собираются в
 *    запрос, как его оформляет движок) ответ изменит, а отпечаток — нет, и
 *    отброшенные сны снова не попробуются, пока не сменится загрузка.
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

    /**
     * Отпечаток вопроса к модели: всё, что задаёт ответ на те же записи, кроме
     * самой модели. Часть [askPrint]. Чего не охватывает — в KDoc объекта.
     */
    val QUESTION_PRINT: String = Integer.toHexString((SYSTEM + "|" + ANSWER_TOKENS).hashCode())

    /**
     * Отпечаток ответа: загрузка модели ([com.uroboros.llm.LlmEngine.loadFingerprint]
     * — модель, её файл, параметры, накладка) и вопрос ([QUESTION_PRINT]).
     * null — отпечатка загрузки нет; тогда сравнивать не с чем, и сны,
     * пробовавшиеся хоть раз, не пробуются (см. [pick]).
     */
    fun askPrint(loadFingerprint: String?): String? = loadFingerprint?.let { "$it|q=$QUESTION_PRINT" }

    /**
     * Сколько разных записей сна должны дать выводу хоть одну свою основу.
     * Меньше двух — вывод пересказывает одну запись и ничего не связывает.
     * Граница по смыслу, а не подбор.
     */
    const val MIN_SUPPORT = 2

    /**
     * Сколько основ вывода может не встречаться ни в одной записи сна, не
     * считая связующих ([LINK_WORDS]). Объявленное число, не подобранное:
     * фразе о связи нужно слово-другое для самой связи, а вывод, где
     * своего больше, уже скорее сочинён, чем выведен. Перепроверяется
     * экраном выводов: отказы с причиной «своих основ» показывают, где
     * граница легла.
     */
    const val MAX_OWN_STEMS = 3

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
     * принятая или отброшенная, с нынешним отпечатком — или любая, когда
     * отпечатка нет, см. [askPrint]) и взять первые [MAX_PER_NIGHT].
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

    // ---- Перепроверка без модели ----

    /**
     * @property checked сколько прежних ответов прошло через проверку заново;
     * @property passed новые строки для прошедших — по одной на набор записей.
     */
    data class Recheck(val checked: Int, val passed: List<ConclusionRow>)

    /**
     * Прежние отброшенные ответы ([rejected], от старых к новым) — ещё раз
     * через [parse] и [check] нынешними правилами, без модели. Зачем — в KDoc
     * объекта, «ВТОРОЙ ШАНС». Прошедший ответ даёт новую строку ночи [nightAt]
     * с пометкой [ConclusionRow.rechecked]; строка отказа остаётся как была.
     *
     * Пропускаются: наборы записей, где принятый вывод уже есть
     * ([acceptedRecords], и те, что прошли здесь же, — один вывод на набор);
     * повторы того же ответа о том же наборе; ответы, чьих записей нет в
     * [recordsOf] — звено молчит ([Dream.silences]), и вывод выдал бы слова
     * скрытой записи. Записи берутся нынешние, по номерам сна.
     *
     * @param recordsOf набор записей ([ConclusionRow.dreamRecordIds]) → живые
     *        тексты в порядке цепочки.
     */
    fun recheck(
        rejected: List<ConclusionRow>,
        acceptedRecords: Set<String>,
        recordsOf: Map<String, List<String>>,
        nightAt: Long,
    ): Recheck {
        var checked = 0
        val passed = mutableListOf<ConclusionRow>()
        val done = HashSet(acceptedRecords)
        val seen = HashSet<Pair<String, String>>()
        for (row in rejected) {
            if (row.dreamRecordIds in done) continue
            if (!seen.add(row.dreamRecordIds to row.text)) continue
            val records = recordsOf[row.dreamRecordIds] ?: continue
            checked++
            val text = (parse(row.text) as? Parsed.Text)?.text ?: continue
            if (check(text, records) != null) continue
            passed += row.copy(id = 0, nightAt = nightAt, text = text, accepted = true, reason = null, rechecked = true)
            done += row.dreamRecordIds
        }
        return Recheck(checked, passed)
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
     * Держится ли вывод за записи сна (почему так — «ПОЧЕМУ ПРОВЕРКА
     * ДОПУСКАЕТ СВОИ СЛОВА» в KDoc объекта). Значимые основы вывода минус
     * [LINK_STEMS]: непусты; не встречающихся в записях [records] — не больше
     * [MAX_OWN_STEMS]; основы есть хотя бы у [MIN_SUPPORT] разных записей.
     * null — прошёл; иначе причина словами. Лишние основы называются при
     * отказе: по ним видно, что модель досочинила.
     */
    fun check(text: String, records: List<String>): String? {
        val words = RiskTrigger.significantStems(text) - LINK_STEMS
        if (words.isEmpty()) return "в выводе нет слов записей"
        val stemsOf = records.map { RiskTrigger.significantStems(it) }
        val extra = words - stemsOf.flatMapTo(HashSet()) { it }
        if (extra.size > MAX_OWN_STEMS) {
            return "своих основ ${extra.size} — больше $MAX_OWN_STEMS: ${extra.sorted().joinToString(", ") { "«$it»" }}"
        }
        val support = stemsOf.count { stems -> stems.any { it in words } }
        if (support == 0) return "в выводе нет слов записей"
        if (support < MIN_SUPPORT) return "вывод держится за слова одной записи сна"
        return null
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
     * [retried] — сколько из ответивших снов пробовалось прежде с другим
     * отпечатком (см. [askPrint]).
     */
    fun doneOutcome(made: Int, dropped: List<String>, stoppedBy: String? = null, retried: Int = 0): String = buildString {
        append(OUTCOME_HEAD).append("сделано ").append(made).append(", отброшено ").append(dropped.size)
        if (retried > 0) append(" (повторных проб ").append(retried).append(")")
        val tail = dropped + listOfNotNull(stoppedBy?.let { "дальше не делаю: $it" })
        if (tail.isNotEmpty()) append(" — ").append(cut(tail.joinToString("; "), OUTCOME_CHARS))
    }

    /**
     * Итог шага с перепроверкой впереди: сколько прежних ответов прошло через
     * проверку заново и сколько из них прошло. Без перепроверенных — итог как
     * есть. Печатается и при нуле прошедших: иначе «перепроверять было нечего»
     * не отличить от «перепроверено, не прошло ни одного».
     */
    fun withRecheck(outcome: String, checked: Int, passed: Int): String =
        if (checked == 0) outcome
        else OUTCOME_HEAD + "перепроверено без модели $checked, прошло $passed; " + outcome.removePrefix(OUTCOME_HEAD)

    /**
     * Вывод для владельца. Единственное место текста подписи. Подписи без
     * грамматического рода («мне подумалось», а не «я подумал»): рода агенту
     * не задано, и строка, которую ставит код, не должна задавать его за него.
     */
    fun shown(text: String): String = "Мне подумалось, что $text."

    /**
     * Вывод для агента — с подписью сна. Единственное место этой подписи:
     * вывод — мысль, пришедшая во сне, а не факт и не слова собеседника, и
     * подпись говорит это модели прямо. Без неё малая модель пересказала бы
     * мысль как случившееся. Где подаётся — [CuriosityAsk.tellLine] и
     * [DreamTopic.line].
     */
    fun dreamt(text: String): String = "Во сне мне подумалось, что $text."

    internal fun cut(text: String, chars: Int): String {
        val flat = text.replace("\n", " ")
        return if (flat.length > chars) flat.take(chars) + "…" else flat
    }
}
