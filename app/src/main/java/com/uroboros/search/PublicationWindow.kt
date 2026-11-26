package com.uroboros.search

import java.time.ZonedDateTime

internal data class PublicationWindow(val from: Long, val until: Long, val label: String) {
    fun contains(at: Long?): Boolean = at != null && at >= from && at < until
}

internal data class PublicationQuery(
    val subject: String, val window: PublicationWindow?, val news: Boolean = false, val summary: Boolean = false,
) {
    companion object {
        private const val DAYS = """последние\s+([1-9][0-9]?)\s+(?:день|дня|дней)"""
        internal const val PERIOD = """сегодня|вчера|за\s+(?:(?:последнюю\s+)?неделю|$DAYS|сутки|последние\s+24\s+часа)"""
        private val time = Regex(
            """(?:^|\s+)($PERIOD)[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val headlineRequest = Regex(
            """^(?:подборк[ау]\s+)?(?:(?:(?:свежие|свежих|последние|последних)\s+)?новост(?:и|ей)|что нового|последние события)(?=$|[\s,.!?;:—–-])""",
            RegexOption.IGNORE_CASE,
        )
        private val summaryRequest = Regex(
            """(?:^|[,\s])(?:кратко|с\s+кратким\s+пересказом|с\s+краткой\s+сводкой)[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val topicPrefix = Regex("""^(?:по\s+теме|об?|про|по)\s+""", RegexOption.IGNORE_CASE)

        fun isNewsRequest(query: String): Boolean = headlineRequest.containsMatchIn(query.trim())

        fun parse(query: String, now: ZonedDateTime = ZonedDateTime.now()): PublicationQuery {
            val news = isNewsRequest(query)
            val summary = if (news) summaryRequest.find(query.trimEnd()) else null
            val phrase = if (summary == null) query.trim() else query.substring(0, summary.range.first).trimEnd(',', ' ')
            val match = time.find(phrase)
            val dayStart = now.toLocalDate().atStartOfDay(now.zone)
            val label = match?.groupValues?.get(1).orEmpty()
            val count = match?.groupValues?.get(2)?.toLongOrNull() ?: 7L
            val from = when {
                label.equals("сегодня", true) -> dayStart
                label.equals("вчера", true) -> now.toLocalDate().minusDays(1).atStartOfDay(now.zone)
                label.endsWith("сутки", true) || label.endsWith("24 часа", true) -> now.minusHours(24)
                match != null -> now.minusDays(count)
                news -> now.minusHours(24)
                else -> return PublicationQuery(query, null)
            }
            val until = if (label.equals("вчера", true)) dayStart.toInstant().toEpochMilli()
                else now.toInstant().toEpochMilli() + 1
            val period = label.ifBlank { "последние 24 часа" }
            val prefix = if (match == null) phrase else phrase.take(match.range.first)
            val subject = if (news) prefix.replaceFirst(headlineRequest, "").trim(' ', ',', '—', '–', ':', '.', '?', '!')
                .replaceFirst(topicPrefix, "") else prefix.trim().trimEnd(' ', ',', '—', '–')
            return PublicationQuery(subject, PublicationWindow(from.toInstant().toEpochMilli(), until, period),
                news, summary != null)
        }
    }
}
