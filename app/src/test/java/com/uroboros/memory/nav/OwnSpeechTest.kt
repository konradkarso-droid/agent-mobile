package com.uroboros.memory.nav

import com.uroboros.llm.EchoCheck
import com.uroboros.memory.DolmenCircle
import com.uroboros.memory.Layer
import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class OwnSpeechTest {

    // --- Своё и не своё ---

    @Test
    fun `эхо реплики владельца — не своё`() {
        val own = EchoCheck.ownSentences(
            answer = "Сегодня отдыхаю, выходной. Радуга состоит из семи цветов.",
            question = "Сегодня отдыхаю, выходной",
            records = emptyList(),
        )
        assertEquals(listOf("Радуга состоит из семи цветов."), own)
    }

    @Test
    fun `пересказ поданной записи — не своё`() {
        val own = EchoCheck.ownSentences(
            answer = "Колодка рубанка сделана из берёзы. Мне нравится думать о дереве и инструментах.",
            question = "Из чего колодка?",
            records = listOf("Твои слова вчера: «Колодка рубанка сделана из берёзы»."),
        )
        assertEquals(listOf("Мне нравится думать о дереве и инструментах."), own)
    }

    @Test
    fun `короткое предложение своим не считается`() {
        assertTrue(EchoCheck.ownSentences("Помню.", "что помнишь?", emptyList()).isEmpty())
    }

    // --- Поиск ---

    @Test
    fun `поиск по словам вопроса — больше общих основ выше`() {
        val said = listOf(
            OwnSpeech.Said("Радуга бывает двойной.", 1L),
            OwnSpeech.Said("Цвета радуги я перечислял семь.", 2L),
            OwnSpeech.Said("Рубанок строгает доску.", 3L),
        )
        val found = OwnSpeech.search(said, "Какие цвета радуги ты называл?")
        assertEquals(listOf("Цвета радуги я перечислял семь.", "Радуга бывает двойной."), found.map { it.sentence })
    }

    @Test
    fun `нет общих слов — пусто`() {
        assertTrue(OwnSpeech.search(listOf(OwnSpeech.Said("Рубанок строгает доску.", 1L)), "Что снилось?").isEmpty())
    }

    @Test
    fun `своя речь собирается по ходам без эха`() {
        val said = OwnSpeech.said(
            listOf(OwnSpeech.Turn("Люблю чай. Рассказывал про цвета радуги подробно.", "Люблю чай", emptyList(), 7L)),
        )
        assertEquals(listOf(OwnSpeech.Said("Рассказывал про цвета радуги подробно.", 7L)), said)
    }

    @Test
    fun `слова агента о собеседнике в свою речь не идут`() {
        val said = OwnSpeech.said(
            listOf(
                OwnSpeech.Turn(
                    "Что у тебя с садом и грядками? Ты давно копаешь землю. Мы с тобой говорили о саде. Люблю думать о грядках и земле.",
                    "Вожусь с твоим садом и грядками",
                    emptyList(),
                    7L,
                )
            ),
        )
        assertEquals(listOf(OwnSpeech.Said("Люблю думать о грядках и земле.", 7L)), said)
    }

    @Test
    fun `неясное лицо остаётся своим`() {
        val said = OwnSpeech.said(
            listOf(OwnSpeech.Turn("Тренируемся рисовать радугу каждый вечер.", "", emptyList(), 7L)),
        )
        assertEquals(1, said.size)
    }

    // --- Места ---

    private fun sticker(id: Long) = Sticker(id = id, content = "запись $id", layer = Layer.GREEN.name)

    @Test
    fun `без параметра раздача прежняя`() {
        val q = (1L..5L).map { sticker(it) }
        val seating = DolmenCircle.seat(5, q, emptyList(), emptyList())
        assertEquals(5, seating.question.size)
        assertEquals(0, seating.ownSpeech)
    }

    @Test
    fun `своя речь берёт одно место из гарантированных мест вопроса`() {
        val q = (1L..5L).map { sticker(it) }
        val t = listOf(sticker(10))
        val c = listOf(sticker(20))
        val seating = DolmenCircle.seat(5, q, t, c, ownSpeech = 3)
        assertEquals(1, seating.ownSpeech)
        assertEquals(2, seating.question.size)
        assertEquals(1, seating.theme.size)
        assertEquals(1, seating.cold.size)
    }

    @Test
    fun `своя речь не нашла — места не держит`() {
        val q = (1L..5L).map { sticker(it) }
        assertEquals(5, DolmenCircle.seat(5, q, emptyList(), emptyList(), ownSpeech = 0).question.size)
    }

    @Test
    fun `строка круга про свою речь печатается и при нуле`() {
        assertEquals("своя речь: нашло 0, усажено 0", DolmenCircle.ownSpeechPart(0, 0))
        val line = DolmenCircle.meter(
            DolmenCircle.Window.NoWords, DolmenCircle.Window.NoWords, emptyList(),
            DolmenCircle.Window.LayerEmpty, false, 0,
        )
        assertTrue(line.contains(DolmenCircle.OWN_SPEECH_NOT_ASKED))
    }

    // --- Подпись ---

    @Test
    fun `строка своей речи для модели`() {
        val zone = ZoneId.of("Europe/Moscow")
        fun at(h: Int) = LocalDateTime.of(2026, 1, 1, h, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            "Я говорил в прошлом разговоре, пару часов назад: «Радуга бывает двойной.».",
            ProvenanceLabels.ownSpeechForModel("Радуга бывает двойной.", at(10), at(12), zone),
        )
        assertEquals(
            "Я говорил в прошлом разговоре, когда-то: «Радуга бывает двойной.».",
            ProvenanceLabels.ownSpeechForModel("Радуга бывает двойной.", null, at(12), zone),
        )
    }
}
