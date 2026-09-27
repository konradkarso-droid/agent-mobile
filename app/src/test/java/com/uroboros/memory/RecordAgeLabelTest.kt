package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Подпись времени у записи для модели ([ProvenanceLabels.ageForModel]):
 * ступени мельче суток от момента хода, дальше — каждая граница ступеней с
 * обеих сторон и счёт по календарным дням местного времени.
 */
class RecordAgeLabelTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private fun at(day: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(2026, 1, 1, hour, minute).plusDays(day.toLong()).atZone(zone).toInstant().toEpochMilli()

    private fun label(daysAgo: Int): String = ProvenanceLabels.ageForModel(at(0), at(daysAgo), zone)

    @Test
    fun `каждая граница ступеней`() {
        assertEquals("меньше часа назад", label(0))
        assertEquals("вчера днём", label(1))
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

    /** Разговор в 23:40, вопрос в 00:40: прошёл час, а не сутки. */
    @Test
    fun `за полночь меньше часа — не вчера`() {
        assertEquals("меньше часа назад", ProvenanceLabels.ageForModel(at(0, 23, 40), at(1, 0, 39), zone))
    }

    @Test
    fun `меньше трёх часов — пару часов назад`() {
        assertEquals("пару часов назад", ProvenanceLabels.ageForModel(at(0, 23, 40), at(1, 0, 40), zone))
        assertEquals("пару часов назад", ProvenanceLabels.ageForModel(at(0, 10, 0), at(0, 12, 59), zone))
    }

    @Test
    fun `вчера вечером — через полночь больше трёх часов`() {
        assertEquals("вчера вечером", ProvenanceLabels.ageForModel(at(0, 22, 0), at(1, 2, 0), zone))
    }

    @Test
    fun `части дня — по часу создания`() {
        assertEquals("сегодня утром", ProvenanceLabels.ageForModel(at(0, 5, 0), at(0, 20, 0), zone))
        assertEquals("сегодня днём", ProvenanceLabels.ageForModel(at(0, 12, 0), at(0, 20, 0), zone))
        assertEquals("сегодня ночью", ProvenanceLabels.ageForModel(at(0, 0, 10), at(0, 23, 50), zone))
        assertEquals("вчера вечером", ProvenanceLabels.ageForModel(at(0, 17, 0), at(1, 12, 0), zone))
        assertEquals("вчера ночью", ProvenanceLabels.ageForModel(at(0, 23, 0), at(1, 12, 0), zone))
    }

    /** Одно и то же мгновение в разных поясах бывает разным днём — считается по поясу устройства. */
    @Test
    fun `день считается в поясе устройства`() {
        val written = at(0, 20, 0) // 20:00 по Москве = 17:00 UTC того же дня
        val asked = at(1, 2, 30) // 02:30 по Москве = 23:30 UTC предыдущего дня
        assertEquals("вчера вечером", ProvenanceLabels.ageForModel(written, asked, zone))
        assertEquals("сегодня вечером", ProvenanceLabels.ageForModel(written, asked, ZoneId.of("UTC")))
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
            "${ProvenanceLabels.forModel(SourceKind.USER_STATED.name)} вчера днём: «колодка из берёзы».",
            ProvenanceLabels.recordForModel(record, at(1), zone),
        )
        assertEquals(
            "Записано на днях, источник не записан: «колодка из берёзы».",
            ProvenanceLabels.recordForModel(record.copy(source = "???"), at(3), zone),
        )
    }

    @Test
    fun `запись прошлого эпизода подписана в прошлом разговоре`() {
        val record = Sticker(
            id = 1, content = "колодка из берёзы", createdAt = at(0, 10, 0), lastAccessedAt = at(0),
            source = SourceKind.USER_STATED.name,
        )
        assertEquals(
            "Твои слова в прошлом разговоре, пару часов назад: «колодка из берёзы».",
            ProvenanceLabels.recordForModel(record, at(0, 12, 0), zone, recordEpisode = 3, currentEpisode = 4),
        )
    }

    @Test
    fun `тот же эпизод или неизвестный — без приставки`() {
        val record = Sticker(
            id = 1, content = "чай", createdAt = at(0, 10, 0), lastAccessedAt = at(0),
            source = SourceKind.USER_STATED.name,
        )
        assertEquals("Твои слова пару часов назад: «чай».", ProvenanceLabels.recordForModel(record, at(0, 12, 0), zone, 4, 4))
        assertEquals("Твои слова пару часов назад: «чай».", ProvenanceLabels.recordForModel(record, at(0, 12, 0), zone))
    }
}
