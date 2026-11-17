package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CeilingLineTest {

    private fun dot(s: String) = s.replace(',', '.')

    @Test
    fun `обычный прогон - сколько работала модель и сколько ждал`() {
        assertEquals(
            " Потолок: модель работала на прогон до 42.5 с из 300. До движка ждал 0.0 с.",
            dot(CeilingLine.of(maxHeldMs = 42_500, waitMs = 0, reachedEngine = true)),
        )
    }

    @Test
    fun `ожидание за чужой работой видно`() {
        assertTrue(dot(CeilingLine.of(30_000, 95_400, true)).endsWith(" До движка ждал 95.4 с."))
    }

    @Test
    fun `слепой потолок - прогон дошёл до движка, а работы не видно`() {
        assertEquals(
            " Потолок: работы модели не видел ни разу — потолок слеп.",
            CeilingLine.of(maxHeldMs = 0, waitMs = -1, reachedEngine = true),
        )
    }

    @Test
    fun `молчит о слепоте - прогон до движка не дошёл`() {
        val line = CeilingLine.of(maxHeldMs = 0, waitMs = -1, reachedEngine = false)
        assertEquals(" Потолок: до работы модели прогон не дошёл.", line)
        assertFalse(line.contains("слеп"))
    }

    @Test
    fun `молчит об ожидании - оно неизвестно`() {
        assertFalse(CeilingLine.of(10_000, -1, true).contains("ждал"))
    }
}
