package com.uroboros.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Когда запись хода значит «на диске чужой разговор» (JournalOverwrite). */
class JournalOverwriteTest {

    @Test
    fun `следующий ход живой ленты - в конец, архива нет`() {
        assertFalse(JournalOverwrite.occupied(listOf(0, 1, 2), 3))
    }

    @Test
    fun `первый ход на пустом диске - архива нет`() {
        assertFalse(JournalOverwrite.occupied(emptyList(), 0))
    }

    @Test
    fun `новый разговор с нуля поверх неподнятого - в архив`() {
        assertTrue(JournalOverwrite.occupied((0..39).toList(), 0))
    }

    @Test
    fun `строка называет число ходов и номер архива`() {
        val line = JournalOverwrite.line(40, 126, 0L)
        assertTrue(line, line.startsWith("Лента: прежний разговор (40 ходов) не поднялся и ушёл в архив №126 перед новым ходом ("))
    }
}
