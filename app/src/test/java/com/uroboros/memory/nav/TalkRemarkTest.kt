package com.uroboros.memory.nav

import com.uroboros.memory.Sticker
import com.uroboros.memory.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Замечание о самом разговоре (TalkRemark), версия 0. Случаи — из живых
 * записей владельца; известные промахи закреплены как граница, чтобы их
 * «починка» была видна.
 */
class TalkRemarkTest {

    /** Таблица глаголов — как на телефоне (см. TestVerbs). */
    @Before
    fun verbs() = TestVerbs.install()

    @Test
    fun `замечания о разговоре узнаются`() {
        assertTrue(TalkRemark.isRemark("Я спросил кто я, а не что я говорил."))
        assertTrue(TalkRemark.isRemark("это говорил я"))
        assertTrue(TalkRemark.isRemark("Я имею в виду - вообще, не в этом разговоре."))
        assertTrue(TalkRemark.isRemark("Повторять реплику запрещено"))
    }

    @Test
    fun `признаки и веса называются`() {
        val names = TalkRemark.features("Я спросил кто я, а не что я говорил.").map { it.name to it.weight }
        assertEquals(listOf("я + глагол речи" to 2, "«а не»" to 1), names)
    }

    // Проверки на молчание: факты о себе остаются фактами.

    @Test
    fun `факты о себе - не замечания`() {
        assertFalse(TalkRemark.isRemark("Я работаю по субботам"))
        assertFalse(TalkRemark.isRemark("Я - Админ"))
        assertFalse(TalkRemark.isRemark("Я состою в клубе исторического фехтования."))
        assertFalse(TalkRemark.isRemark("Мой любимый инструмент — ножовка, а не рубанок"))
        assertFalse(TalkRemark.isRemark("У меня - ничего особенного."))
        assertFalse(TalkRemark.isRemark("Если коротко, я интересуюсь проактивным искусственным интеллектом."))
    }

    @Test
    fun `пустое предложение - не замечание`() {
        assertFalse(TalkRemark.isRemark(""))
        assertFalse(TalkRemark.isRemark("…"))
    }

    // Границы версии 0 (KDoc «ЧЕГО НЕ УМЕЕТ»).

    @Test
    fun `граница - короткий отклик о себе снимается как замечание`() {
        assertTrue(TalkRemark.isRemark("Да, я помню это."))
    }

    @Test
    fun `граница - уточнение без слов о речи проходит`() {
        assertFalse(TalkRemark.isRemark("Я про сны."))
    }

    @Test
    fun `портрет снимает замечание и считает его`() {
        fun rec(id: Long, t: String) = Sticker(id = id, content = t, createdAt = id, source = SourceKind.USER_STATED.name, accessCount = 1)
        val r = Portrait.of(listOf(rec(1, "Я работаю по субботам"), rec(2, "Я спросил кто я, а не что я говорил.")))
        assertEquals(listOf("Я работаю по субботам"), r.passed.map { it.sentence })
        assertEquals(1, r.aboutTalk)
    }
}
