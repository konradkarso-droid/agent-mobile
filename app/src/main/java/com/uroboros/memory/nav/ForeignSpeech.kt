package com.uroboros.memory.nav

import com.uroboros.memory.Sentences

/**
 * Чья речь в предложении — ось спектрометра рядом с видом предложения
 * (memory.SentenceKind) и лицом (PersonForm). Чистая логика.
 *
 * ЗАЧЕМ. Владелец пересказывает агенту чужие слова: вставляет реплику
 * Клода с подписью, передаёт, что сказала мама. Всё, что пришло с экрана,
 * сейчас считается его речью (Coordinates.speakerOf), и чужое «я» ложится как
 * его: «Клод: я тоже программа» спорит в суде с его «я», попадает в его
 * портрет, могло бы переписать его имя. Отличить нужно по смыслу — это вид
 * предложения, а не подпись по форме: «Клод сказал, что я хорошо объясняю» и
 * «Клод написал: «я хорошо объясняю»» оба про Клода, но «я» в них разное.
 *
 * ВИДЫ ([Kind]) — как их размечает владелец:
 *  - [Kind.OWN] — его речь, в том числе «я говорил», «ты сказал»: ссылка на
 *    сказанное в разговоре — тоже его слова;
 *  - [Kind.DIRECT] — говорит другой, его голосом: подпись «Клод: …»,
 *    «Передаю слова Клода: …» в начале. Рамки владельца нет;
 *  - [Kind.REPORTED] — пересказ: рамка владельца с глаголом речи или мнения
 *    («Мама говорит, что…», «по словам Дениса…», «Мама сказала: «…»»).
 *    Цитата внутри рамки — голос того, кого цитируют, рамка — голос владельца;
 *  - [Kind.TEXT_QUOTE] — цитата текста, а не реплика собеседника: правило,
 *    поговорка, фраза для оценки.
 *
 * ГОЛОСА ([Result.voices]) — главное для потребителей: чьё «я» в каком куске.
 * Голос с `speaker = null` — владелец. В пересказе без кавычек («Мама
 * говорит, что я похудел») голос один, владельца: «я» там — он.
 *
 * ГЛАГОЛ РЕШАЕТ, РЕЧЬ ЭТО ИЛИ СОБЫТИЕ. Пересказ — только при глаголах речи и
 * мнения ([SPEECH_VERBS]) и с «что», двоеточием или кавычками после них.
 * «Сосед жалуется, что я шумлю», «Друг согласился» — рассказ владельца о
 * событии, его речь. «Большинство считает успехом…» без «что» — его
 * рассуждение. Если перед глаголом стоит «я» или «ты» («Ты вчера сказал,
 * что…», «Я сказал Клоду, что…») — это ссылка на сказанное в разговоре.
 *
 * ПОДПИСЬ ДЕЙСТВУЕТ НА ВСЮ ЗАПИСЬ ([ofRecord]). Реплика, вставленная под
 * «Клод:», — несколько предложений одного голоса; рамка «Пишет Клод, передаю
 * дословно:» — тоже, всё после неё. Ярлык — не подпись: «Итог:», «Важно:»,
 * «Вопрос:» ([LABELS]) — слова владельца.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - по словам, без разбора по ролям: подлежащее пересказа — слово прямо перед
 *    глаголом речи; «Вчера Клод мне сказал, что…» отдаст имя «мне»;
 *  - заголовок вставленного документа («Сводка обсуждения…») — цитата текста
 *    для владельца, а здесь — его речь: признака у заголовка нет;
 *  - имя говорящего — как написано, только родительный после «слова»
 *    («Передаю слова Клода») приводится к именительному отбрасыванием «а»;
 *    «Клоду», «Клодом» одним именем с «Клод» не считаются;
 *  - «Говорят, что…» без подлежащего — пересказ с говорящим «кто-то»;
 *  - подпись — слово с заглавной перед двоеточием в начале, если оно не ярлык
 *    и может быть говорящим ([isSpeakerWord]); без загруженного словаря
 *    «Купил: молоко, хлеб» прочтётся как реплика говорящего «Купил».
 *
 * Область, где сверено: слепая разметка владельцем 38 предложений (живые из
 * его памяти и сочинённые по их образцу; в тесте живые заменены такими же по
 * строению, без личного); расходятся 2 — «Это ты сказал. Разве
 * нет?» (у владельца «не ясно») и заголовок документа. На всей памяти
 * владельца (481 запись его слов) чужой голос найден ровно в семи записях,
 * вставленных от Клода, и ни в одной другой. Перепроверять тем же набором
 * (тест ForeignSpeechTest) при любой правке правил.
 */
object ForeignSpeech {

    enum class Kind { OWN, DIRECT, REPORTED, TEXT_QUOTE }

    /** Кусок предложения и чей он; `speaker = null` — владелец. */
    data class Voice(val text: String, val speaker: String?)

    /**
     * Вид предложения, говорящий (для [Kind.DIRECT] и [Kind.REPORTED]),
     * уверенность и голоса по кускам.
     */
    data class Result(val kind: Kind, val speaker: String?, val sure: Boolean, val voices: List<Voice>)

    /**
     * Глаголы речи и мнения — начала слов. Объявленный список, не
     * подобранный; дополняется здесь и только здесь.
     */
    val SPEECH_VERBS = listOf(
        "говорит", "говорил", "сказал", "сказала", "пишет", "писал", "написал", "считает", "считал",
        "думает", "утверждает", "утверждал", "ответил", "ответила", "сообщил", "уверяет", "полагает",
        "говорят", "пишут", "считают",
    )

    /** Ярлыки в начале строки, которые не подпись говорящего. Объявленные. */
    val LABELS = setOf(
        "итог", "итого", "важно", "вопрос", "ответ", "внимание", "задача", "пример", "примечание",
        "правило", "кстати", "совет", "смысл", "суть", "цитата", "заметка", "напоминание", "план",
        "предложение", "текст", "фраза", "слово", "строка", "пункт", "часть", "шаг", "тема", "порядок",
    )

    /** Слова, при которых кавычки — цитата текста, а не реплика. Объявленные. */
    val TEXT_MARKS = listOf("фраз", "правил", "цитат", "поговорк", "пословиц", "звучит", "выражени", "девиз")

    /** Разбор одного предложения. */
    fun of(sentence: String): Result {
        val s = sentence.trim()
        // 1. Подпись в начале: «Клод: …», «Пишет Денис: …», «Передаю слова Клода: …».
        SIGNATURE.matchEntire(s)?.let { m ->
            val lead = m.groupValues[1]
            val name = m.groupValues[2]
            val rest = m.groupValues[3]
            if (!isSpeakerWord(name)) return ofLabelled(s, rest)
            if (fold(name) in LABELS) {
                return if (fold(name) == "цитата") Result(Kind.TEXT_QUOTE, null, true, listOf(Voice(s, null)))
                else of(rest).let { r -> if (r.kind == Kind.OWN) Result(Kind.OWN, null, true, listOf(Voice(s, null))) else r }
            }
            val speaker = if (fold(lead).contains("слова") && name.length > 3 && name.endsWith("а")) name.dropLast(1) else name
            return Result(Kind.DIRECT, speaker, true, listOf(Voice(rest, speaker)))
        }
        val folded = fold(s)
        // 2. Цитата текста: правило, фраза, поговорка; «как говорил дед, …» без «что».
        if (TEXT_MARKS.any { folded.contains(it) } && QUOTE.containsMatchIn(s))
            return Result(Kind.TEXT_QUOTE, null, true, listOf(Voice(s, null)))
        if (TEXT_MARKS.any { folded.contains(it) } && s.contains(':'))
            return Result(Kind.TEXT_QUOTE, null, false, listOf(Voice(s, null)))
        if (SAYING.containsMatchIn(folded) && !folded.contains(" что "))
            return Result(Kind.TEXT_QUOTE, null, false, listOf(Voice(s, null)))
        // 3. Цитата, потом рамка: «„Я устал“, — сказал Пётр».
        QUOTE_THEN_FRAME.find(s)?.let { m ->
            if (isSpeechVerb(m.groupValues[2])) {
                val speaker = m.groupValues[3]
                val frame = s.substring(s.indexOf(m.groupValues[1]) + m.groupValues[1].length).trimStart('»', '"', '“')
                return Result(Kind.REPORTED, speaker, true, listOf(Voice(m.groupValues[1], speaker), Voice(frame, null)))
            }
        }
        // 4. Рамка с двоеточием: «Мама сказала: «я приеду»», «Пишет Клод, передаю дословно: …».
        FRAME_COLON.matchEntire(s)?.let { m ->
            val frame = m.groupValues[1]
            val words = WORD.findAll(frame).map { it.value }.toList()
            val verbAt = words.indexOfFirst { isSpeechVerb(it) || fold(it).startsWith("передаю") }
            if (verbAt >= 0 && words.take(verbAt + 1).none { fold(it) in FIRST_SECOND }) {
                val speaker = subjectOf(words, verbAt)
                val quoted = m.groupValues[2].trim().removePrefix("«").removePrefix("\"").removeSuffix("»").removeSuffix("\"")
                return Result(Kind.REPORTED, speaker, true, listOf(Voice(frame, null), Voice(quoted, speaker)))
            }
        }
        // 5. Пересказ без кавычек: «Мама говорит, что…», «по словам Дениса…».
        BY_WORDS.find(folded)?.let { m ->
            val speaker = WORD.findAll(s.substring(m.range.first)).map { it.value }.drop(2).firstOrNull() ?: "кто-то"
            return Result(Kind.REPORTED, speaker, true, listOf(Voice(s, null)))
        }
        val words = WORD.findAll(s).map { it.value }.toList()
        val verbAt = words.indexOfFirst { isSpeechVerb(it) }
        if (verbAt >= 0) {
            val before = words.take(verbAt)
            val after = folded.substringAfter(fold(words[verbAt]))
            val hasThat = AFTER_VERB_THAT.containsMatchIn(after)
            if (hasThat && before.none { fold(it) in FIRST_SECOND }) {
                return Result(Kind.REPORTED, subjectOf(words, verbAt), true, listOf(Voice(s, null)))
            }
        }
        return Result(Kind.OWN, null, true, listOf(Voice(s, null)))
    }

    /**
     * Разбор записи по предложениям. Подпись или рамка с двоеточием в первом
     * предложении действуют на всю запись: остальные предложения — голос того
     * же говорящего (см. «ПОДПИСЬ ДЕЙСТВУЕТ НА ВСЮ ЗАПИСЬ»).
     */
    fun ofRecord(text: String): List<Result> {
        val sentences = Sentences.split(text)
        if (sentences.isEmpty()) return emptyList()
        val first = of(sentences.first())
        val carried = when {
            first.kind == Kind.DIRECT -> first.speaker
            first.kind == Kind.REPORTED && first.voices.size > 1 && first.voices.first().speaker == null -> first.speaker
            else -> null
        }
        return listOf(first) + sentences.drop(1).map { sentence ->
            if (carried != null) Result(Kind.DIRECT, carried, true, listOf(Voice(sentence, carried))) else of(sentence)
        }
    }

    /** Есть ли в записи голос не владельца. */
    fun hasForeignVoice(text: String): Boolean = ofRecord(text).any { r -> r.voices.any { it.speaker != null } }

    /**
     * Может ли слово перед двоеточием быть говорящим: имя, фамилия или
     * существительное не место («Мама:», «Агент:»). Глагол («Купил: молоко,
     * хлеб»), место («Москва: дождь») — нет. Решает словарь начальных форм
     * (PersonForm.lemmas); незнакомое словарю слово («Нортон») и любое слово
     * без загруженного словаря — говорящий.
     */
    private fun isSpeakerWord(word: String): Boolean {
        val analyses = PersonForm.lemmas?.analyze(fold(word)) ?: return true
        if (analyses.isEmpty()) return true
        return analyses.any { a ->
            "Name" in a.tags || "Surn" in a.tags || (a.partOfSpeech == "NOUN" && "Geox" !in a.tags)
        }
    }

    /** «Купил: молоко…» — двоеточие без говорящего: вид решает остальная часть, голос владельца. */
    private fun ofLabelled(whole: String, rest: String): Result =
        of(rest).let { r -> if (r.kind == Kind.OWN) Result(Kind.OWN, null, true, listOf(Voice(whole, null))) else r }

    private fun isSpeechVerb(word: String): Boolean {
        val w = fold(word)
        return SPEECH_VERBS.any { w.startsWith(it) }
    }

    /** Подлежащее пересказа — слово прямо перед глаголом речи (см. «ЧЕГО НЕ УМЕЕТ»). */
    private fun subjectOf(words: List<String>, verbAt: Int): String {
        val verb = fold(words[verbAt])
        // «Пишет Клод, …» — подлежащее после глагола.
        if (verbAt == 0 || verb.startsWith("пишет") && verbAt + 1 < words.size && words[verbAt + 1].first().isUpperCase())
            return words.getOrNull(verbAt + 1) ?: "кто-то"
        return words[verbAt - 1]
    }

    private fun fold(text: String): String = text.lowercase().replace('ё', 'е')

    private val FIRST_SECOND = setOf("я", "ты", "мы", "вы", "мне", "тебе")
    // Граница слова — через \p{L}, а не \b: в регулярках Java \b кириллицу
    // за буквы не считает.
    private val WORD = Regex("""[\p{L}][\p{L}\-]*""")
    private val QUOTE = Regex("""[«"„].+[»"“]""")
    private val SIGNATURE = Regex("""^((?:(?:Пишет|Передаю\s+слова|Слова)\s+)?)(\p{Lu}[\p{L}\-]{1,30})\s*:\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val FRAME_COLON = Regex("""^([^:«"„]{2,120}?):\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)
    private val QUOTE_THEN_FRAME = Regex("""^[«"„](.+?)[»"“]\s*,?\s*[—–-]\s*(\p{L}+)\s+(\p{L}+)""")
    private val SAYING = Regex("""^как\s+(говорил|говорила|говорят|говаривал|сказал)\p{L}*\s+\p{L}+\s*,""")
    private val BY_WORDS = Regex("""по\s+словам\s+\p{L}+""")
    private val AFTER_VERB_THAT = Regex("""^[\s,]*(\p{L}+\s+){0,2}?что(?![\p{L}])|^\s*:""")
}
