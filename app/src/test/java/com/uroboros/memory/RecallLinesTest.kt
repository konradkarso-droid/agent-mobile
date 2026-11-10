package com.uroboros.memory

import com.uroboros.memory.nav.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RecallLinesTest {
    private val now = 1_800_000_000_000L
    private val utc = ZoneId.of("UTC")
    private val record = Sticker(id = 7, content = "По профессии я химик.", createdAt = now - 40L * 24 * 60 * 60 * 1000)

    @Test
    fun `строка — та же, что давала подпись записи напрямую`() {
        val line = RecallLines.of(record, Coordinates.Address.UNDEFINED, null, now, recordEpisode = 0, currentEpisode = 1, zone = utc)
        assertEquals(ProvenanceLabels.recordForModel(record, now, utc, 0, 1), line.text)
        assertFalse(line.retold)
        assertEquals(ProvenanceLabels.recordParts(record), line.parts)
    }

    @Test
    fun `запись из прошлого разговора так и подписана, из нынешнего — нет`() {
        val past = RecallLines.of(record, Coordinates.Address.UNDEFINED, null, now, 0, 1, utc).text
        val same = RecallLines.of(record, Coordinates.Address.UNDEFINED, null, now, 1, 1, utc).text
        assertTrue(past, past.contains(ProvenanceLabels.PAST_EPISODE))
        assertFalse(same, same.contains(ProvenanceLabels.PAST_EPISODE))
    }
}
