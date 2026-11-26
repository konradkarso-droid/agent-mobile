package com.uroboros.search

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.Sentences
import com.uroboros.memory.nav.LemmaHolder
import com.uroboros.util.TextFold

/**
 * Предмет и действие выделяются по словам и их начальным формам. Вид
 * предложения определяет, просит ли владелец о действии или о сведениях.
 * Источник полномочий — вход чата. Темы из предыдущего поиска берём только
 * для короткого уточнения, привязанного каналом к результату того поиска.
 */
internal object SearchIntent {
    data class RecentReply(val bot: Long, val chat: Long, val message: Long, val query: String, val sentAt: Long) {
        fun topicFor(botId: Long, chatId: Long, messageId: Long, replyTo: Long?, now: Long): String? {
            if (bot != botId || chat != chatId || messageId <= message ||
                now - sentAt !in 0L..FOLLOWUP_NS
            ) return null
            return query.takeIf { replyTo == message || (replyTo == null && messageId == message + 1) }
        }
    }

    // Объявленный срок, в течение которого «а вчера?» относится к последней выдаче.
    private const val FOLLOWUP_NS = 15L * 60L * 1_000_000_000L

    private val opening = Regex(
        """^(?:(?:пожалуйста|уроборос|агент)[,!:\s]+|(?:а|и)\s+|(?:слушай|кстати|скажи|подскажи|расскажи|покажи)[,\s]+|(?:мне интересно|хочу узнать|можешь узнать)[, :]+){0,4}""",
        RegexOption.IGNORE_CASE,
    )
    private val personal = Regex(
        """(?:^|[\s,])(?:у\s+(?:тебя|меня|нас|не[её]|него|них|вас)|в\s+(?:(?:твоей|моей|нашей|тво[её]м|мо[её]м|нашем)\s+)?(?:памяти|разговоре|чате|журнале|семье)|в\s+(?:наших|моих|твоих)\s+(?:записях|файлах)|с\s+тобой)(?=$|[\s,.:!?])""",
        RegexOption.IGNORE_CASE,
    )
    private val offline = Regex(
        """(?:^|[\s,;—–])(?:не\s+(?:ищи|поищи|гугли|проверяй|смотри|открывай)(?:те)?|без\s+(?:поиска|интернета|сети)|(?:только\s+)?(?:из\s+памяти|по\s+памяти))(?=$|[\s,.:!?;—–])""",
        RegexOption.IGNORE_CASE,
    )
    private val link = Regex("""https?://|\bwww[.]""", RegexOption.IGNORE_CASE)
    private val continuation = Regex("""^(?:а|и)\s+""", RegexOption.IGNORE_CASE)
    private val temporal = Regex(
        """(?:^|\s+)(${PublicationQuery.PERIOD})(?=$|[\s,.:!?])""",
        RegexOption.IGNORE_CASE,
    )
    private val topicOpening = Regex("""^(?:были|есть|появились|сейчас)(?:\s+|$)|^(?:у|с)\s+""", RegexOption.IGNORE_CASE)

    private val word = Regex("""[\p{L}\p{N}]+""")
    private val index = Regex("""индекс(?:а|у|ом|е|ы|ов)?""")
    private val sources = Regex("""источник(?:и|ов|ам|ами|ах)?""")
    private val refresh = Regex("""(?:пере)?обнов\p{L}*|освеж\p{L}+|актуализ\p{L}+|переоб(?:ход\p{L}*|ой\p{L}+)""")
    private val freshness = Regex("""нов(?:ый|ая|ое|ые|ого|ому|ым|ом|ых)|свеж\p{L}+|актуальн\p{L}+""")
    private val auto = Regex("""автообнов\p{L}*""")
    private val search = Regex("""поиск\p{L}*|запрос\p{L}*""")
    private val queries = Regex("""запрос\p{L}*""")
    private val history = Regex("""истори\p{L}+""")
    private val recent = Regex("""последн\p{L}+|предыдущ\p{L}+""")
    private val results = Regex("""результат\p{L}*|выдач\p{L}*|итог\p{L}*|законч\p{L}+""")
    private val links = Regex("""ссылк\p{L}+""")
    private val show = Regex("""(?:покажи|пришли|скинь|дай|перечисли|выведи|напомни)(?:те)?""")
    private val doRefresh = Regex("""(?:(?:пере)?обнови|обновляй|освежи|актуализируй|переобойди)(?:те)?""")
    private val infinitive = Regex("""(?:обновить|обновлять|освежить|актуализировать|переобойти)""")
    private val start = Regex("""(?:включи|запусти|возобнови)(?:те)?""")
    private val stop = Regex("""(?:выключи|отключи|останови|прекрати|перестань)(?:те)?""")
    private val startInfinitive = Regex("""включить|запустить|возобновить""")
    private val stopInfinitive = Regex("""выключить|отключить|остановить|прекратить|перестать""")
    private val stopRepeated = Regex("""(?:^|[\s,])больше\s+не\s+(?:(?:сам|автоматически)\s+)?обновляй(?:те)?(?=$|[\s,.!?])""")
    private val reported = Regex("""(?:по)?(?:просил|говорил|советовал|рекомендовал)\p{L}*|(?:сказал|написал|спросил|обещал|предложил|цитир)\p{L}*""")
    private val outsideIndex = Regex("""биржев\p{L}*|электронн\p{L}*|почтов\p{L}*|(?:потребительских|котировок|цен|snp)""")
    private val localContext = Regex("""(?:^|[\s,])(?:в|по)\s+(?:(?:моей|твоей|нашей|локальной)\s+)?(?:памяти|разговоре|чате|журнале|доске|файлах|записях)(?=$|[\s,.:!?])""")
    private val newsWord = Regex("""новост\p{L}+""")
    private val newsFrame = Regex("""(?:какие|есть|были|будут|вышли|появились|нашлись|ли|бы|сейчас|ищу|хочу|подборка|подборку|свежие|свежих|новые|новых|последние|последних|новости|новостей|новостями)""")
    private val eventFrame = Regex("""что|нового|произошло|случилось|там|сейчас""")
    private val topicMarker = Regex("""^(?:об?|про|по|из|на|у|в)\s+""", RegexOption.IGNORE_CASE)
    private val searchVerb = Regex("""(?:поищи|найди|проверь|посмотри|(?:по)?гугли)(?:те)?|(?:поискать|найти|проверить|посмотреть)""")
    private val webMedium = Regex("""(?:^|[\s,])(?:в\s+(?:интернете|сети)|онлайн)(?=$|[\s,.:!?—–-])""", RegexOption.IGNORE_CASE)
    private val questionFind = Regex("""^(?:как|кто|что|где|како[ейм]|какая|какие|сколько)\s+""", RegexOption.IGNORE_CASE)
    private val readings = Regex("""стать(?:я|и|ю|ей|ям|ями|ях)|статей|публикаци\p{L}+|материал\p{L}*|источник\p{L}*""")
    private val readingFrame = Regex("""(?:где|можно|ли|есть|найдутся|свежие|надежные|какие|что|пишут|известно|в|интернете|сети|онлайн|почитать|узнать|посмотреть)|${readings.pattern}""")
    private val readingTopic = Regex("""^(?:(?:по\s+теме|об?|про)\s+)""", RegexOption.IGNORE_CASE)
    private val versions = Regex("""верси\p{L}+|релиз\p{L}*|выпуск\p{L}*""")
    private val polite = setOf("можешь", "можете", "сможешь", "сможете", "надо", "нужно", "пора", "бы", "давай", "давайте")
    private val foundQuestion = setOf("а", "и", "что", "ты", "у", "тебя", "в", "интернете", "сети", "поиске", "уже", "нашел", "нашлось")
    private val linkRequest = setOf("а", "и", "пожалуйста", "ссылки", "мне", "уроборос", "агент")

    private class Form(val text: String) {
        val folded = TextFold.fold(text)
        val words = word.findAll(folded).map { it.value }.toList()
        private val lemmas by lazy {
            LemmaHolder.loaded?.let { dictionary -> words.flatMap(dictionary::lemmas) }.orEmpty()
        }
        private val kind = SentenceKind.of(text).kind
        val question = kind == SentenceKind.Kind.QUESTION ||
            SentenceKind.of(text.replaceFirst(opening, "")).kind == SentenceKind.Kind.QUESTION
        val request = kind == SentenceKind.Kind.REQUEST
        fun has(vararg values: String) = words.any { it in values }
        fun surfaceMatches(pattern: Regex) = words.any(pattern::matches)
        fun matches(pattern: Regex) = surfaceMatches(pattern) || lemmas.any(pattern::matches)
        fun hasPoliteVerb() = words.any { it in polite }
    }

    private fun form(text: String): Form? {
        if (!validSearchPhrase(text) || forbidsSearch(text) || hasLink(text) ||
            text.any { it in "\"«»“”" } || localContext.containsMatchIn(TextFold.fold(text))
        ) return null
        val sentence = Sentences.split(text).filterNot { SentenceKind.of(it).kind == SentenceKind.Kind.SOCIAL }
            .singleOrNull() ?: return null
        return Form(sentence).takeUnless { it.matches(reported) }
    }

    fun operation(text: String): SearchCommand? {
        val form = form(text) ?: return null
        val indexIntent = form.matches(index) || form.matches(sources) || form.matches(auto)
        if (indexIntent && !form.matches(outsideIndex)) indexOperation(form)?.let { return it }
        val querying = form.question || form.request || form.matches(show)
        if (!querying || form.has("не")) return null
        val searchTopic = form.matches(search)
        if (searchTopic && form.matches(history) ||
            form.question && form.has("что", "какие") && (form.has("искал", "искали", "поискал", "поискали") ||
                form.matches(queries) && form.has("делали", "задавали", "выполняли", "отправляли", "формулировали"))
        ) return SearchCommand.History
        val askingFound = form.question && form.has("что") && form.has("нашел", "нашлось") &&
            form.words.all { it in foundQuestion }
        val resultLinks = form.matches(show) && form.matches(links) &&
            (searchTopic || form.matches(newsWord) || form.has("найденное", "найденных", "источники", "источников") ||
                form.words.all { it in linkRequest || show.matches(it) })
        return SearchCommand.Last.takeIf {
            askingFound || resultLinks || (searchTopic && (form.matches(recent) || form.matches(results)))
        }
    }

    private fun indexOperation(form: Form): SearchCommand? {
        val changing = form.matches(refresh)
        val automatic = form.matches(auto) || form.has("сам", "самостоятельно", "автоматически", "расписанию")
        val explicit = !(form.has("ли", "если", "еслибы") || form.question && form.has("как", "почему", "зачем"))
        val later = SearchCommand.hasDelay(form.folded)
        val negated = form.has("не")
        val imperative = form.surfaceMatches(doRefresh)
        val refreshInfinitive = form.surfaceMatches(infinitive)
        val startRequested = form.surfaceMatches(start) || form.surfaceMatches(startInfinitive) && form.hasPoliteVerb()
        val stopRequested = form.surfaceMatches(stop) || form.surfaceMatches(stopInfinitive) && form.hasPoliteVerb()
        if (explicit && !later) {
            if (automatic && (form.matches(auto) || changing)) {
                if (!negated && stopRequested || stopRepeated.containsMatchIn(form.folded)) {
                    return SearchCommand.AutoRefresh.Disable
                }
                if (!negated && (startRequested || imperative || form.has("пусть"))) {
                    return SearchCommand.AutoRefresh.Enable
                }
            }
            val directed = imperative || refreshInfinitive && form.hasPoliteVerb() ||
                form.has("обновим", "освежим") && form.has("давай", "давайте")
            if (changing && directed && !negated && !automatic) return SearchCommand.Refresh
        }
        val question = form.question || form.has("когда", "сколько", "насколько")
        val status = form.has("статус", "состояние", "состоянии") && (!negated || question)
        val info = changing || automatic || form.matches(freshness) || form.has("страниц", "страницы", "там", "что")
        return SearchCommand.AutoRefresh.Status.takeIf {
            status || question && info && !imperative && !startRequested && !stopRequested &&
                !refreshInfinitive && !form.surfaceMatches(startInfinitive) && !form.surfaceMatches(stopInfinitive)
        }
    }

    private val widening = setOf("шире", "расширь", "расширить", "других", "другие")
    private val wideningFrame = setOf("поиск", "источники", "источниках", "поищи", "поищите", "поискать",
        "посмотри", "посмотреть", "проверь", "проверить", "в", "и", "еще", "пожалуйста", "да")

    fun asksToWiden(text: String): Boolean {
        val action = form(text.replaceFirst(opening, "")) ?: return false
        if (action.words.none { it in widening } ||
            action.words.any { it !in widening && it !in wideningFrame && it !in polite }
        ) return false
        val scopesSearch = action.has("шире", "поиск", "источники", "источниках")
        val direct = action.has("расширь", "поищи", "поищите", "посмотри", "проверь") ||
            action.hasPoliteVerb() && (action.matches(searchVerb) || action.has("расширить"))
        return scopesSearch && direct
    }

    fun forbidsSearch(text: String): Boolean = offline.containsMatchIn(text)
    fun hasLink(text: String): Boolean = link.containsMatchIn(text)

    fun query(text: String, previousQuery: String? = null): String? {
        if (personal.containsMatchIn(text)) return null
        val form = form(text) ?: return null
        val phrase = form.text.replaceFirst(opening, "").trimEnd(' ', '.', '?', '!')
        if (phrase.isBlank()) return null
        val date = temporal.find(phrase)
        val period = date?.groupValues?.get(1)?.let(TextFold::fold)
        if (date?.value == phrase && previousQuery != null) {
            val previous = SearchRoute.parse(previousQuery)
            if (previous.publication.news || previous.publication.subject.isNotBlank()) {
                return buildQuery(previous.publication.subject, period, previous.source, previous.publication.news)
            }
        }
        val aboutNews = form.matches(newsWord)
        val aboutEvents = form.has("что") && form.has("нового", "случилось", "произошло")
        if (!form.has("не") && (aboutNews || aboutEvents)) {
            val withoutDate = if (date == null) phrase else phrase.removeRange(date.range)
            val frame = if (aboutNews) newsFrame else eventFrame
            val content = removeFrame(withoutDate, frame)
            val topic = content.replaceFirst(topicOpening, "").trim()
                .takeUnless { SearchRoute.startsWithSource(content) } ?: content
            val requested = form.question || form.request || form.has("что", "какие", "подборка", "подборку", "бы") ||
                PublicationQuery.isNewsRequest(phrase) &&
                (content.isEmpty() || topicMarker.containsMatchIn(content) || SearchRoute.startsWithSource(content))
            if (!requested) return null
            if (topic.isEmpty() && continuation.containsMatchIn(text) && previousQuery != null) {
                val previous = SearchRoute.parse(previousQuery)
                if (previous.publication.news) {
                    return buildQuery(previous.publication.subject, period, previous.source)
                }
            }
            if (topic.isEmpty() && date == null && aboutEvents) return null
            return buildQuery(topic, period)
        }
        if (form.has("не", "если")) return null
        val sourceQuestion = form.has("почитать") && form.has("где", "можно") ||
            form.matches(readings) && (form.has("ли", "где", "какие") || form.question && form.has("есть", "найдутся")) ||
            form.has("что") && (form.has("пишут") || form.has("известно") && webMedium.containsMatchIn(phrase))
        if (sourceQuestion) {
            removeFrame(phrase, readingFrame).replaceFirst(readingTopic, "").takeIf { it.isNotBlank() }?.let { return it }
        }
        val currentLeader = form.has("кто") && form.has("возглавляет", "руководит")
        val currentVersion = form.has("какая", "какой", "какие") && form.matches(versions) &&
            (form.has("сейчас", "новейшая", "новейший") || form.matches(recent) || form.matches(freshness))
        return phrase.takeIf { (currentLeader || currentVersion) && form.words.size >= 3 }
    }

    private fun removeFrame(phrase: String, frame: Regex): String {
        val parts = word.findAll(phrase).toList()
        val first = parts.indexOfFirst { !frame.matches(TextFold.fold(it.value)) }
        if (first < 0) return ""
        val last = parts.indexOfLast { !frame.matches(TextFold.fold(it.value)) }
        val start = parts.getOrNull(first - 1)?.range?.last?.plus(1) ?: 0
        val end = parts.getOrNull(last + 1)?.range?.first ?: phrase.length
        return phrase.substring(start, end).trim(' ', ',', ':', '—', '–')
    }

    // Источник и действие могут стоять в любом порядке перед паузой, отделяющей вопрос.
    fun webQuestion(text: String): Pair<String, String>? {
        if (!webMedium.containsMatchIn(text) || personal.containsMatchIn(text)) return null
        val phrase = form(text)?.text?.replaceFirst(opening, "") ?: return null
        val comma = phrase.indexOf(',')
        if (comma < 0) return null
        val lead = phrase.substring(0, comma)
        val action = Form(lead)
        if (!webMedium.containsMatchIn(lead) || !action.matches(searchVerb) || action.has("не", "ли", "если") ||
            !(action.hasPoliteVerb() || action.request || action.words.any { searchVerb.matches(it) && !it.endsWith("ть") })
        ) return null
        return phrase.substring(comma + 1).trim().takeIf(questionFind::containsMatchIn)?.let { lead to it.trimEnd('?') }
    }

    private fun buildQuery(
        topic: String, period: String?, source: WebFeedSource? = null, news: Boolean = true,
    ): String = listOfNotNull("новости".takeIf { news }, topic.takeIf { it.isNotBlank() },
        source?.let { "из ${it.name}" }, period).joinToString(" ")
}
