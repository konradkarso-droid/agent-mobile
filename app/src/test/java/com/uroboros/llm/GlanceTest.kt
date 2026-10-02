package com.uroboros.llm

import org.junit.Assert.assertEquals
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
}
