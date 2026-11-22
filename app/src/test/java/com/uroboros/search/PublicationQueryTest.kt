package com.uroboros.search

import java.time.Instant
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationQueryTest {
    private val now = ZonedDateTime.parse("2026-11-22T11:00:00-05:00[America/New_York]")

    @Test
    fun weeklyNewsUsesPublicationDatesAndKeepsTopicWhenSpecified() {
        val latest = PublicationQuery.parse("Что нового за неделю?", now)
        assertEquals("", latest.subject)
        val window = checkNotNull(latest.window)
        assertEquals(at("2026-11-15T16:00:00Z"), window.from)
        assertTrue(window.contains(at("2026-11-22T16:00:00Z")))
        assertFalse(window.contains(at("2026-11-22T16:00:01Z")))
        assertFalse(window.contains(null))

        val topic = PublicationQuery.parse("Телескопы за последние 3 дня", now)
        assertEquals("Телескопы", topic.subject)
        assertEquals(at("2026-11-19T16:00:00Z"), topic.window?.from)
    }

    @Test
    fun todayAndYesterdayUseLocalMidnightAndQuotedTitlesRemainSearchTerms() {
        val today = checkNotNull(PublicationQuery.parse("новости сегодня", now).window)
        val yesterday = checkNotNull(PublicationQuery.parse("новости вчера", now).window)
        assertEquals(at("2026-11-22T05:00:00Z"), today.from)
        assertEquals(today.from, yesterday.until)
        assertEquals(at("2026-11-21T05:00:00Z"), yesterday.from)
        assertNull(PublicationQuery.parse("Книга «Лес сегодня»", now).window)
    }

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
}
