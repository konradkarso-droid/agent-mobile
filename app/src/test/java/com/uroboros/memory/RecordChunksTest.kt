package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RecordChunksTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private val now = at(20, 3)

    @Test
    fun `строка ночи собирается тем же правилом, что строка хода из прошлого эпизода`() {
        val c = RecordChunks.Candidate(ProvenanceLabels.RETOLD_FOR_MODEL, " ты работаешь по субботам.", at(16, 14))
        assertEquals(
            listOf("С твоих слов в прошлом разговоре, на днях: ты работаешь по субботам."),
            RecordChunks.linesFor(listOf(c), now, zone),
        )
    }

    @Test
    fun `сегодняшние записи ночью не собираются — их подпись меняется днём`() {
        val today = RecordChunks.Candidate("Твои слова", " «Привет».", at(20, 1))
        val yesterday = RecordChunks.Candidate("Твои слова", " «Пока».", at(19, 22))
        assertEquals(
            listOf("Твои слова в прошлом разговоре, вчера вечером: «Пока»."),
            RecordChunks.linesFor(listOf(today, yesterday), now, zone),
        )
    }

    @Test
    fun `чаще бравшиеся — первыми, и не больше предела`() {
        val rare = RecordChunks.Candidate("Твои слова", " «Редко».", at(10, 12), uses = 1, lastUsedAt = at(19, 12))
        val often = RecordChunks.Candidate("Твои слова", " «Часто».", at(10, 12), uses = 5, lastUsedAt = at(18, 12))
        assertEquals(
            listOf("Твои слова в прошлом разговоре, с неделю назад: «Часто»."),
            RecordChunks.linesFor(listOf(rare, often), now, zone, limit = 1),
        )
    }

    @Test
    fun `запоминание — счёт растёт у известной строки, новая встаёт с одним`() {
        val known = RecordChunks.Candidate("Твои слова", " «А».", 1L, uses = 2, lastUsedAt = 10L)
        val result = RecordChunks.remember(
            listOf(known),
            listOf(known.copy(uses = 1), RecordChunks.Candidate("Твои слова", " «Б».", 2L)),
            now = 100L,
        )
        assertEquals(listOf(3 to 100L, 1 to 100L), result.map { it.uses to it.lastUsedAt })
    }

    @Test
    fun `запоминание держит не больше предела, давние уходят`() {
        val many = (1..RecordChunks.MAX_CANDIDATES + 5).map {
            RecordChunks.Candidate("Твои слова", " «$it».", it.toLong(), lastUsedAt = it.toLong())
        }
        val result = RecordChunks.remember(many, emptyList(), now = 0L)
        assertEquals(RecordChunks.MAX_CANDIDATES, result.size)
        assertEquals(" «${RecordChunks.MAX_CANDIDATES + 5}».", result.first().body)
    }

    @Test
    fun `строка без источника кандидатом не становится`() {
        assertEquals(null, RecordChunks.candidate(null, 1L))
    }

    @Test
    fun `части строки хода дают ту же строку, что и прежняя сборка`() {
        val sticker = Sticker(content = "Я Иван", tag = "t", source = SourceKind.USER_STATED.name, confidence = "HIGH", createdAt = at(16, 14))
        assertEquals(
            ProvenanceLabels.recordForModel(sticker, now, zone, recordEpisode = 1, currentEpisode = 2),
            ProvenanceLabels.line(ProvenanceLabels.recordParts(sticker)!!, ProvenanceLabels.pastEpisodeTime(sticker.createdAt, now, zone)),
        )
    }
}
