package com.uroboros.memory.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamTopicTest {

    // --- Фраза ---

    @Test
    fun `фраза из тем собирается кодом`() {
        assertEquals("Этой ночью мне снилось: радуга.", DreamTopic.line(listOf("радуга")))
        assertEquals("Этой ночью мне снилось: радуга и чай.", DreamTopic.line(listOf("радуга", "чай")))
        assertEquals(
            "Этой ночью мне снилось: радуга, чай и рубанок.",
            DreamTopic.line(listOf("радуга", "чай", "рубанок")),
        )
    }

    @Test
    fun `тем нет — строки нет`() {
        assertNull(DreamTopic.line(emptyList()))
        assertNull(DreamTopic.line(DreamTopic.load("")))
        assertNull(DreamTopic.line(DreamTopic.load(null)))
    }

    @Test
    fun `хранение тем туда и обратно`() {
        assertEquals(listOf("радуга", "чай"), DreamTopic.load(DreamTopic.store(listOf("радуга", "чай"))))
    }

    // --- Разбор и проверка ответа модели ---

    @Test
    fun `ответ модели — первая строка без кавычек и точки`() {
        assertEquals(DreamTopic.Parsed.Topic("цвета радуги"), DreamTopic.parse("«цвета радуги».\nещё что-то"))
    }

    @Test
    fun `слишком длинная тема отброшена`() {
        assertTrue(DreamTopic.parse("сон про цвета большой радуги") is DreamTopic.Parsed.Refused)
    }

    @Test
    fun `тема из слов записей проходит, досочинённая — нет`() {
        val records = listOf("Цвета радуги: красный, оранжевый", "Колодка рубанка из берёзы")
        assertNull(DreamTopic.check("цвета радуги", records))
        assertTrue(DreamTopic.check("морской прибой", records)!!.contains("нет в записях сна"))
    }

    // --- Подача ---

    @Test
    fun `подаётся только на вопрос к агенту`() {
        val line = "Этой ночью мне снилось: радуга."
        assertNull(DreamTopic.refusal(line, toAgent = true, ribbonEmpty = false, alreadyInRibbon = false))
        assertEquals("вопрос не к агенту", DreamTopic.refusal(line, toAgent = false, ribbonEmpty = false, alreadyInRibbon = false))
    }

    @Test
    fun `не первым в ленте`() {
        val line = "Этой ночью мне снилось: радуга."
        assertEquals(
            "лента пуста — первым системное не ставится",
            DreamTopic.refusal(line, toAgent = true, ribbonEmpty = true, alreadyInRibbon = false),
        )
    }

    @Test
    fun `не второй раз в той же ленте`() {
        val line = "Этой ночью мне снилось: радуга."
        assertEquals(
            "уже рассказан в этой ленте",
            DreamTopic.refusal(line, toAgent = true, ribbonEmpty = false, alreadyInRibbon = true),
        )
    }

    @Test
    fun `без тем молчит с причиной`() {
        assertEquals("тем последней ночи нет", DreamTopic.refusal(null, toAgent = true, ribbonEmpty = false, alreadyInRibbon = false))
    }

    @Test
    fun `просьба без второго лица адреса агент не даёт — и сна не получает`() {
        val address = com.uroboros.memory.nav.Coordinates.questionAddress("Расскажи сон")
        val line = "Этой ночью мне снилось: радуга."
        val toAgent = address == com.uroboros.memory.nav.Coordinates.Address.AGENT
        assertEquals("вопрос не к агенту", DreamTopic.refusal(line, toAgent, ribbonEmpty = false, alreadyInRibbon = false))
    }

    // --- Прибор ---

    @Test
    fun `счёт за сутки — только ходы с временем в пределах суток`() {
        val now = 10 * DreamTopic.DAY_MS
        assertEquals(2, DreamTopic.toldWithinDay(listOf(now - 1000, now - DreamTopic.DAY_MS + 1, now - DreamTopic.DAY_MS - 1, null), now))
    }

    @Test
    fun `строка прибора`() {
        assertEquals("Сон в зеркале: рассказан за сутки: 1 · в этом ходе подан", DreamTopic.meter(1, null))
        assertEquals(
            "Сон в зеркале: рассказан за сутки: 0 · в этом ходе молчу — вопрос не к агенту",
            DreamTopic.meter(0, "вопрос не к агенту"),
        )
    }
}
