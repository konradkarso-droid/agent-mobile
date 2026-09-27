package com.uroboros.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFoldTest {

    @Test
    fun `русская ё приводится к е`() {
        assertEquals("желтый", TextFold.fold("жёлтый"))
    }

    @Test
    fun `латинская ë приводится к е`() {
        assertEquals("желтый", TextFold.fold("жëлтый"))
    }

    @Test
    fun `заглавные обеих ё приводятся к е`() {
        assertEquals("елка", TextFold.fold("Ёлка"))
        assertEquals("елка", TextFold.fold("Ëлка"))
    }

    @Test
    fun `весь капс приводится к нижнему регистру`() {
        assertEquals("мнемоническое правило", TextFold.fold("МНЕМОНИЧЕСКОЕ Правило"))
    }

    @Test
    fun `текст без ё и заглавных не меняется`() {
        val text = "у паука 8 ног, ставка 7,5 — и всё?"
            .replace('ё', 'е')
        assertEquals(text, TextFold.fold(text))
    }

    @Test
    fun `другие латинские буквы не трогаются`() {
        assertEquals("cafe e", TextFold.fold("cafe e"))
    }

    /**
     * Форма запроса поиска: слово вопроса приводится тем же правилом, что
     * колонка contentFolded, и ищется в ней подстрокой (LIKE '%слово%').
     */
    @Test
    fun `слово желтый находит запись с латинской ë`() {
        val record = "Жëлтый шарф висит в прихожей"
        val word = TextFold.fold("желтый")
        assertTrue(TextFold.fold(record).contains(word))
        assertTrue(TextFold.fold(record).contains(TextFold.fold("жёлтый")))
    }
}
