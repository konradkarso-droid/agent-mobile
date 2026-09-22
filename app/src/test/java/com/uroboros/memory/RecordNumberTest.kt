package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Запрос по номеру. Большая часть проверок — на МОЛЧАНИЕ: где разбор обязан
 * ответить «это не номер» и оставить запрос поиску по словам. Случайная
 * «починка» в сторону щедрости сломает их заметно.
 */
class RecordNumberTest {

    @Test
    fun `номер со знаком читается`() {
        assertEquals(75L, RecordNumber.parse("№75"))
        assertEquals(75L, RecordNumber.parse("#75"))
        assertEquals(75L, RecordNumber.parse("№ 75"))
        assertEquals(75L, RecordNumber.parse("  №75  "))
    }

    @Test
    fun `голое число — поиск по словам, а не номер`() {
        assertNull(RecordNumber.parse("75"))
        assertNull(RecordNumber.parse("7,5"))
    }

    @Test
    fun `знак номера среди слов — поиск по словам`() {
        assertNull(RecordNumber.parse("№75 рубанок"))
        assertNull(RecordNumber.parse("ставка №75"))
        assertNull(RecordNumber.parse("№75 №57"))
    }

    @Test
    fun `пустое и знак без числа — не номер`() {
        assertNull(RecordNumber.parse(null))
        assertNull(RecordNumber.parse(""))
        assertNull(RecordNumber.parse("№"))
        assertNull(RecordNumber.parse("№-5"))
    }

    @Test
    fun `число длиннее Long не роняет разбор`() {
        assertNull(RecordNumber.parse("№" + "9".repeat(19)))
    }
}
