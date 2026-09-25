package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Подпись времени у записи для модели ([ProvenanceLabels.ageForModel]):
 * каждая граница ступеней с обеих сторон и счёт по календарным дням местного
 * времени, а не по суткам от момента записи.
 */
class RecordAgeLabelTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private fun at(day: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(2026, 1, 1, hour, minute).plusDays(day.toLong()).atZone(zone).toInstant().toEpochMilli()

    private fun label(daysAgo: Int): String = ProvenanceLabels.ageForModel(at(0), at(daysAgo), zone)

    @Test
    fun `каждая граница ступеней`() {
        assertEquals("сегодня", label(0))
        assertEquals("вчера", label(1))
        assertEquals("на днях", label(2))
        assertEquals("на днях", label(5))
        assertEquals("с неделю назад", label(6))
        assertEquals("с неделю назад", label(13))
        assertEquals("пару недель назад", label(14))
        assertEquals("пару недель назад", label(24))
        assertEquals("около месяца назад", label(25))
        assertEquals("около месяца назад", label(59))
        assertEquals("давно", label(60))
        assertEquals("давно", label(400))
    }

    /** Сказано в 23:50, спрошено в 00:10 — это уже вчера, хотя прошло двадцать минут. */
    @Test
    fun `счёт идёт по календарным дням, а не по суткам`() {
        assertEquals("вчера", ProvenanceLabels.ageForModel(at(0, 23, 50), at(1, 0, 10), zone))
        assertEquals("сегодня", ProvenanceLabels.ageForModel(at(0, 0, 10), at(0, 23, 50), zone))
    }

    /** Одно и то же мгновение в разных поясах бывает разным днём — считается по поясу устройства. */
    @Test
    fun `день считается в поясе устройства`() {
        val written = at(0, 23, 30) // 23:30 по Москве = 20:30 UTC того же дня
        val asked = at(1, 2, 0) // 02:00 по Москве = 23:00 UTC предыдущего дня
        assertEquals("вчера", ProvenanceLabels.ageForModel(written, asked, zone))
        assertEquals("сегодня", ProvenanceLabels.ageForModel(written, asked, ZoneId.of("UTC")))
    }

    @Test
    fun `запись из будущего называется сегодня`() {
        assertEquals("сегодня", ProvenanceLabels.ageForModel(at(3), at(0), zone))
    }

    @Test
    fun `строка записи несёт происхождение и время`() {
        val record = Sticker(
            id = 1, content = "колодка из берёзы", createdAt = at(0), lastAccessedAt = at(0),
            source = SourceKind.USER_STATED.name,
        )
        assertEquals(
            "${ProvenanceLabels.forModel(SourceKind.USER_STATED.name)} вчера: «колодка из берёзы».",
            ProvenanceLabels.recordForModel(record, at(1), zone),
        )
        assertEquals(
            "Записано на днях, источник не записан: «колодка из берёзы».",
            ProvenanceLabels.recordForModel(record.copy(source = "???"), at(3), zone),
        )
    }
}
