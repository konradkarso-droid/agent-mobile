package com.uroboros.search

/**
 * Открытый каталог реплик, в которых сам вопрос задаёт внешний поиск.
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
    private val news = Regex(
        """^(?:(?:есть ли|появились ли)\s+(?:(?:свежие|новые)\s+)?новости|какие\s+(?:(?:есть|были|вышли|сейчас)\s+)?(?:свежие\s+)?новости|что\s+(?:там\s+)?(?:нового|случилось|произошло)|свежие\s+новости|последние\s+новости|подборка\s+(?:(?:свежих|последних)\s+)?новостей|новости(?=\s))(?=$|[\s,.:!?—–-])""",
        RegexOption.IGNORE_CASE,
    )
    private val lookup = Regex(
        """^(?:(?:где\s+(?:можно\s+)?(?:почитать|узнать|посмотреть)|можно\s+ли\s+почитать|(?:есть ли|найдутся ли)\s+(?:статьи|публикации|источники|материалы))\s+|(?:что\s+пишут(?:\s+в\s+(?:интернете|сети))?|что\s+известно\s+в\s+(?:интернете|сети))\s+)(?:об?\s+|про\s+|по\s+теме\s+)?(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val dynamicQuestion = Regex(
        """^(?:(?:кто\s+(?:(?:сейчас|на\s+данный\s+момент)\s+)?(?:возглавляет|руководит)|(?:какая|какой|какие)\s+сейчас\s+(?:последн\p{L}+|актуальн\p{L}+|новейш\p{L}+)\s+(?:версия|релиз|выпуск))\s+\S.+)$""",
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

    fun forbidsSearch(text: String): Boolean = offline.containsMatchIn(text)
    fun hasLink(text: String): Boolean = link.containsMatchIn(text)

    fun query(text: String, previousQuery: String? = null): String? {
        if (!validSearchPhrase(text) || forbidsSearch(text) || personal.containsMatchIn(text) || hasLink(text) ||
            text.any { it in "\"«»“”" }
        ) return null
        val phrase = text.replaceFirst(opening, "").trimEnd(' ', '.', '?', '!')
        if (phrase.isBlank()) return null
        val date = temporal.find(phrase)
        if (date?.value == phrase && previousQuery != null) {
            val previous = SearchRoute.parse(previousQuery)
            if (previous.publication.news || previous.publication.subject.isNotBlank()) {
                return buildQuery(previous.publication.subject, date.groupValues[1], previous.source, previous.publication.news)
            }
        }
        news.find(phrase)?.let { match ->
            val tail = phrase.substring(match.range.last + 1)
                .trim(' ', ',', ':', '—', '–', '-')
            val period = temporal.find(tail)
            val content = (if (period == null) tail else tail.removeRange(period.range)).trim(' ', ',')
            val topic = if (SearchRoute.startsWithSource(content)) content else content.replaceFirst(topicOpening, "").trim()
            if (topic.isEmpty() && continuation.containsMatchIn(text) && previousQuery != null) {
                val previous = SearchRoute.parse(previousQuery)
                if (previous.publication.news) {
                    return buildQuery(previous.publication.subject, period?.groupValues?.get(1), previous.source)
                }
            }
            if (topic.isEmpty() && period == null && match.value.startsWith("что ", ignoreCase = true)) return null
            return buildQuery(topic, period?.groupValues?.get(1))
        }
        lookup.matchEntire(phrase)?.groupValues?.get(1)?.let { return it }
        return phrase.takeIf { dynamicQuestion.matches(it) }
    }

    private fun buildQuery(
        topic: String, period: String?, source: WebFeedSource? = null, news: Boolean = true,
    ): String = listOfNotNull("новости".takeIf { news }, topic.takeIf { it.isNotBlank() },
        source?.let { "из ${it.name}" }, period).joinToString(" ")
}
