package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class GlanceTest {

    @Test
    fun `описание инструмента стоит в стене последним, после пустой строки`() {
        val wall = BuildSelfDescription.compose("стена", listOf("а"), listOf("нажито"), tools = Glance.WALL_BLOCK)

        assertTrue(wall.startsWith("стена\nа\nнажито\n\n# Tools"))
        assertTrue(wall.endsWith("</tool_call>"))
        assertTrue(wall.contains("\"name\": \"${Glance.TOOL_NAME}\""))
    }

    @Test
    fun `без инструментов стена прежняя`() {
        assertEquals("стена\nа", BuildSelfDescription.compose("стена", listOf("а")))
    }

    @Test
    fun `вызов дописывается к сказанному до него`() {
        assertTrue(Glance.callMessage("").startsWith(Glance.CALL_OPEN))
        assertTrue(Glance.callMessage("Сейчас гляну. ").startsWith("Сейчас гляну.\n${Glance.CALL_OPEN}"))
        assertTrue(Glance.callMessage("").endsWith("</tool_call>"))
    }

    @Test
    fun `ответ приборов — шапка, время, доска в обёртке`() {
        val at = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 1, 20, 14) }.timeInMillis

        assertEquals(
            "<tool_response>\nМои приборы сейчас:\nСейчас: четверг, 1 октября 2026, 20:14\nЗона: норма\n</tool_response>",
            Glance.response("Зона: норма", at),
        )
    }

    @Test
    fun `пустая доска сказана словами`() {
        assertTrue(Glance.response("").contains("Приборы мне сейчас не видны."))
    }

    @Test
    fun `слово о приборах — доска по вопросу`() {
        assertEquals("записей", Glance.asksInstruments("Исправляю. Сейчас тоже.\nСколько у тебя записей в памяти?"))
        assertEquals("температура", Glance.asksInstruments("Какая у тебя температура?"))
        assertEquals("модели", Glance.asksInstruments("На какой ты модели?"))
        assertEquals("пообещал", Glance.asksInstruments("Что ты мне пообещал?"))
        assertEquals("перегреваешься", Glance.asksInstruments("Ты не перегреваешься?"))
        assertEquals("батарея", Glance.asksInstruments("Батарея не садится?"))
        assertEquals("час", Glance.asksInstruments("Который час?"))
        assertEquals("времени", Glance.asksInstruments("Сколько времени?"))
        assertEquals("заряд", Glance.asksInstruments("ЗАРЯД какой?"))
    }

    @Test
    fun `без слова о приборах — молчит`() {
        assertNull(Glance.asksInstruments("Привет, как дела?"))
        assertNull(Glance.asksInstruments("Пинг?"))
        assertNull(Glance.asksInstruments("Исправляю. Сейчас тоже."))
        assertNull(Glance.asksInstruments("Напомни мне завтра написать Насте."))
        assertNull(Glance.asksInstruments("Часть кода готова"))
        assertNull(Glance.asksInstruments(""))
    }
}
