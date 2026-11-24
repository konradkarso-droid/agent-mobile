package com.uroboros.search

import java.time.ZonedDateTime

internal data class PublicationWindow(val from: Long, val until: Long, val label: String) {
    fun contains(at: Long?): Boolean = at != null && at >= from && at < until
}

internal data class PublicationQuery(val subject: String, val window: PublicationWindow?) {
    companion object {
        private val time = Regex(
            """(?:^|\s+)(сегодня|вчера|за\s+(?:(?:последнюю\s+)?неделю|последние\s+([1-9][0-9]?)\s+(?:день|дня|дней)))[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val allNews = Regex("(?:что нового|(?:свежие )?новости|последние события)", RegexOption.IGNORE_CASE)

        fun parse(query: String, now: ZonedDateTime = ZonedDateTime.now()): PublicationQuery {
            val match = time.find(query.trimEnd())
            val dayStart = now.toLocalDate().atStartOfDay(now.zone)
            if (match == null) {
                val phrase = query.trim()
                val implicit = allNews.containsMatchIn(phrase)
                if (!implicit) return PublicationQuery(query, null)
                val subject = phrase
                    .replace(Regex("(?:что нового|(?:свежие )?новости|последние события)", RegexOption.IGNORE_CASE), "")
                    .trim().trimEnd(' ', ',', '—', '–')
                val until = now.toInstant().toEpochMilli() + 1
                val from = now.minusDays(30).toInstant().toEpochMilli()
                return PublicationQuery(subject, PublicationWindow(from, until, "последние 30 дней"))
            }
            val label = match.groupValues[1]
            val count = match.groupValues[2].toLongOrNull() ?: 7L
            val from = when {
                label.equals("сегодня", true) -> dayStart
                label.equals("вчера", true) -> now.toLocalDate().minusDays(1).atStartOfDay(now.zone)
                else -> now.minusDays(count)
            }
            val until = if (label.equals("вчера", true)) dayStart.toInstant().toEpochMilli()
                else now.toInstant().toEpochMilli() + 1
            val subject = query.take(match.range.first).trim().trimEnd(' ', ',', '—', '–')
            return PublicationQuery(
                subject.takeUnless(allNews::matches).orEmpty(),
                PublicationWindow(from.toInstant().toEpochMilli(), until, label),
            )
        }
    }
}
