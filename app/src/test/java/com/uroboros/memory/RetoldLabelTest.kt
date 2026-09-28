package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Строка развёрнутой записи владельца для модели ([ProvenanceLabels.retoldForModel]). */
class RetoldLabelTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private fun at(day: Int, hour: Int): Long =
        LocalDateTime.of(2026, 1, 1, hour, 0).plusDays(day.toLong()).atZone(zone).toInstant().toEpochMilli()

    private val record = Sticker(
        id = 1, content = "Я много работаю над твоими настройками.", createdAt = at(0, 14), lastAccessedAt = at(0, 14),
        source = SourceKind.USER_STATED.name,
    )
    private val retold = "ты много работаешь над моими настройками."

    @Test
    fun `прошлый эпизод — с твоих слов в прошлом разговоре, без кавычек`() {
        assertEquals(
            "С твоих слов в прошлом разговоре, вчера днём: ты много работаешь над моими настройками.",
            ProvenanceLabels.retoldForModel(record, retold, at(1, 12), zone, recordEpisode = 3, currentEpisode = 4),
        )
    }

    @Test
    fun `тот же эпизод или неизвестный — без приставки`() {
        val expected = "С твоих слов пару часов назад: ты много работаешь над моими настройками."
        assertEquals(expected, ProvenanceLabels.retoldForModel(record, retold, at(0, 16), zone, 4, 4))
        assertEquals(expected, ProvenanceLabels.retoldForModel(record, retold, at(0, 16), zone))
    }
}
