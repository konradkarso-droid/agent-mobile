package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Слова реплики для поиска: предложения-обычаи не ищутся (meaningfulWords в HourglassMemory.kt). */
class MeaningfulWordsTest {

    @Test
    fun `реплика из одного обычая не ищет ничего`() {
        assertEquals(emptyList<String>(), meaningfulWords("Доброй ночи."))
        assertEquals(emptyList<String>(), meaningfulWords("Привет! Доброй ночи"))
        assertEquals(emptyList<String>(), meaningfulWords("Понял, спасибо."))
    }

    @Test
    fun `обычай рядом с делом - ищется только дело`() {
        val words = meaningfulWords("Привет снова. Оцени фразу про поединок.")
        assertFalse(words.toString(), "привет" in words)
        assertTrue(words.toString(), "поединок" in words)
    }

    @Test
    fun `молчит - обычай внутри вопроса ищется, вид предложения вопрос`() {
        assertTrue("привет" in meaningfulWords("Привет, рубанок наточил?"))
        assertTrue("рубанок" in meaningfulWords("У рубанка ручка треснула, рубанок старый"))
    }
}
