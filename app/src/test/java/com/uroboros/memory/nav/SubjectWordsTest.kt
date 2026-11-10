package com.uroboros.memory.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectWordsTest {
    private val dictionary = TestVerbs.lemmas

    @Test
    fun `существительное, прилагательное и числительное — предмет`() {
        for (word in listOf("билет", "полотенце", "стоматолога", "исторического", "восемь")) {
            assertTrue(word, SubjectWords.isSubject(word, dictionary))
        }
    }

    @Test
    fun `глагол, наречие, частица и междометие — не предмет`() {
        for (word in listOf("попробую", "говорил", "помнишь", "хорошо", "потом", "кстати", "теперь", "например", "надеюсь")) {
            assertFalse(word, SubjectWords.isSubject(word, dictionary))
        }
    }

    @Test
    fun `притяжательное и указательное — не предмет, хоть и склоняется как прилагательное`() {
        for (word in listOf("твое", "своих", "такой", "этих")) {
            assertFalse(word, SubjectWords.isSubject(word, dictionary))
        }
    }

    @Test
    fun `незнакомое словарю слово — предмет`() {
        assertTrue(SubjectWords.isSubject("praxis", dictionary))
        assertTrue(SubjectWords.isSubject("фалькон", dictionary))
    }

    @Test
    fun `среди совпавших слов хватает одного предмета`() {
        assertTrue(SubjectWords.hasSubject(listOf("хорошо", "билет"), dictionary))
        assertFalse(SubjectWords.hasSubject(listOf("хорошо", "попробую"), dictionary))
        assertFalse(SubjectWords.hasSubject(emptyList(), dictionary))
    }

    @Test
    fun `без словаря правило молчит`() {
        assertTrue(SubjectWords.hasSubject(listOf("хорошо", "попробую"), null))
    }
}
