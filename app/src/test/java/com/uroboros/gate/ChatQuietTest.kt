package com.uroboros.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разговор в телеграме для решений о долгой работе (ChatQuiet). Главное —
 * молчание: без разговора и после тишины телеграм ничего не держит.
 */
class ChatQuietTest {

    private val minute = 60_000L

    @Test
    fun `разговор — то, на что агент отвечает или переспрашивает`() {
        assertEquals(listOf("TO_AGENT", "DOUBTFUL"), ChatQuiet.dialogRings)
    }

    @Test
    fun `строк разговора нет — телеграм не держит`() {
        assertEquals(ChatQuiet.Reading.None, ChatQuiet.reading(100 * minute, null))
        assertNull(ChatQuiet.refusal(ChatQuiet.Reading.None, 15 * minute))
    }

    @Test
    fun `тихо меньше порога — держит и говорит сколько`() {
        val r = ChatQuiet.reading(10 * minute, 7 * minute)
        assertEquals("в телеграме разговор: тихо 3 мин из 15", ChatQuiet.refusal(r, 15 * minute))
    }

    @Test
    fun `порог набран — не держит, ровно порог тоже`() {
        assertNull(ChatQuiet.refusal(ChatQuiet.reading(30 * minute, 15 * minute), 15 * minute))
        assertNull(ChatQuiet.refusal(ChatQuiet.reading(60 * minute, 0), 15 * minute))
    }

    @Test
    fun `часы, ушедшие назад, не дают отрицательной тишины`() {
        assertEquals(ChatQuiet.Reading.Quiet(0), ChatQuiet.reading(5 * minute, 6 * minute))
    }

    @Test
    fun `лента не прочиталась — держит, как разговор`() {
        assertEquals(
            "лента чатов телеграма не прочиталась (SQLiteException)",
            ChatQuiet.refusal(ChatQuiet.Reading.Failed("SQLiteException"), 15 * minute),
        )
    }
}
