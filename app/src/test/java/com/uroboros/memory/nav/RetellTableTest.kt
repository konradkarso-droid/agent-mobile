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

    /** Файл основ, собранный из пар, читается в ту же таблицу. */
    @Test
    fun `пары — файл основ — та же таблица`() {
        val pairs = table("работаю работаешь", "займусь займёшься", "лечу летишь", "лечу лечишь", "пишу пишешь")
        val back = RetellTable.readStems(RetellTable.stemsFileLines(pairs).asSequence())
        for (w in listOf("работаю", "работаешь", "займешься", "займусь", "летишь", "пишу", "температуру")) {
            assertEquals(w, pairs.pairOf(w), back.pairOf(w))
            assertEquals(w, pairs.isAmbiguous(w), back.isAmbiguous(w))
            assertEquals(w, pairs.knowsVerb(w), back.knowsVerb(w))
        }
        assertEquals(5, back.pairCount)
        assertEquals(1, back.ambiguousCount)
    }

    /** Основа файла с общим началом предыдущей и строка не по формату. */
    @Test
    fun `файл основ — общее начало и кривая строка`() {
        val t = RetellTable.readStems(
            sequenceOf(RetellTable.FORMAT, "1", "ю ешь", "0работа 0", "5ал 0", "кривая", "9х 0"),
        )
        assertEquals("работаешь", t.pairOf("работаю"))
        assertEquals("работалешь", t.pairOf("работалю"))
        assertEquals(2, t.pairCount)
        assertEquals(2, t.skippedLines)
    }

    @Test(expected = IllegalStateException::class)
    fun `не файл основ — отказ`() {
        RetellTable.readStems(sequenceOf("работаю работаешь"))
    }

    /** Молчание: слово не из таблицы, но с окончанием образца — не глагол. */
    @Test
    fun `чужое слово с тем же окончанием — не глагол`() {
        val t = table("работаю работаешь")
        assertFalse(t.knowsVerb("пою"))
        assertNull(t.pairOf("стою"))
    }

    @Test
    fun `пустая таблица — пар нет`() {
        assertNull(RetellTable.EMPTY.pairOf("работаю"))
        assertEquals(0, RetellTable.EMPTY.pairCount)
    }
}
