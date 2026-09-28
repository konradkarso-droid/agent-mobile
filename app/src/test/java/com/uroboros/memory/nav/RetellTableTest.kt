package com.uroboros.memory.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetellTableTest {

    private fun table(vararg lines: String) = RetellTable.parse(lines.asSequence())

    @Test
    fun `пара ищется в обе стороны`() {
        val t = table("работаю работаешь")
        assertEquals("работаешь", t.pairOf("работаю"))
        assertEquals("работаю", t.pairOf("работаешь"))
        assertEquals("работаешь", t.pairOf("Работаю"))
    }

    @Test
    fun `ключ с русской и с латинской ё находит ту же пару, значение — как в таблице`() {
        val t = table("займусь займёшься")
        assertEquals("займусь", t.pairOf("займёшься"))
        assertEquals("займусь", t.pairOf("займëшься"))
        assertEquals("займусь", t.pairOf("займешься"))
        assertEquals("займёшься", t.pairOf("займусь"))
    }

    @Test
    fun `две пары у одной формы — неоднозначная, в словаре пар её нет`() {
        val t = table("лечу летишь", "лечу лечишь", "работаю работаешь")
        assertNull(t.pairOf("лечу"))
        assertTrue(t.isAmbiguous("лечу"))
        assertTrue(t.isAmbiguous("Лечу"))
        assertEquals("лечу", t.pairOf("летишь"))
        assertFalse(t.isAmbiguous("работаю"))
        assertEquals(1, t.ambiguousCount)
    }

    @Test
    fun `кривая строка пропущена и посчитана, пустая — не считается`() {
        val t = table("работаю работаешь", "одно", "три слова тут", "", "пишу пишешь")
        assertEquals(2, t.skippedLines)
        assertEquals(2, t.pairCount)
        assertEquals("пишешь", t.pairOf("пишу"))
    }

    @Test
    fun `пустая таблица — пар нет`() {
        assertNull(RetellTable.EMPTY.pairOf("работаю"))
        assertEquals(0, RetellTable.EMPTY.pairCount)
    }
}
