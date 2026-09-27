package com.uroboros.memory.nav

import com.uroboros.memory.nav.PersonForm.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Фразы придуманные, не из памяти владельца. */
class PersonFormTest {

    private fun persons(sentence: String) = PersonForm.ofSentence(sentence)

    // --- Ловит ---

    @Test
    fun `смешанная запись из двух предложений — о говорящем и о собеседнике`() {
        val form = PersonForm.of("Сегодня отдыхаю. Вечером займусь твоими настройками")
        assertTrue(form.aboutSpeaker)
        assertTrue(form.aboutAddressee)
        assertEquals(setOf(Person.FIRST), form.sentences[0].persons)
        assertEquals(setOf(Person.FIRST, Person.SECOND), form.sentences[1].persons)
    }

    @Test
    fun `работаю над твоей памятью — оба`() {
        assertEquals(setOf(Person.FIRST, Person.SECOND), persons("Работаю над твоей памятью"))
    }

    @Test
    fun `я был у врача — о говорящем`() {
        assertEquals(setOf(Person.FIRST), persons("Я был у врача"))
    }

    @Test
    fun `будет тебе новый опыт — о собеседнике без чужого я`() {
        val form = PersonForm.of("Будет тебе новый опыт, не волнуйся")
        assertTrue(form.aboutAddressee)
        assertFalse(form.aboutSpeaker)
    }

    @Test
    fun `как тебя зовут — о собеседнике`() {
        assertEquals(setOf(Person.SECOND), persons("Как тебя зовут?"))
    }

    @Test
    fun `помнишь — о собеседнике по окончанию`() {
        assertEquals(setOf(Person.SECOND), persons("Помнишь что-нибудь?"))
    }

    @Test
    fun `назови — просьба, а не о собеседнике`() {
        assertEquals(setOf(Person.IMPERATIVE), persons("Назови цвета радуги"))
    }

    @Test
    fun `повелительные из готового списка ловятся`() {
        for (s in listOf("Попробуй ещё раз", "Назови три цвета", "Расскажи про чай", "Перечисли цвета")) {
            assertTrue(s, Person.IMPERATIVE in persons(s))
        }
    }

    @Test
    fun `вопрос без лица — никто`() {
        assertEquals(setOf(Person.NONE), persons("Что снилось?"))
    }

    @Test
    fun `прошедшее время без местоимения — не ясно`() {
        assertEquals(setOf(Person.UNCLEAR), persons("Спал неплохо"))
    }

    @Test
    fun `голое мы — не ясно`() {
        assertEquals(setOf(Person.UNCLEAR), persons("Тренируемся по субботам"))
    }

    @Test
    fun `мы с тобой — оба вместе`() {
        assertEquals(setOf(Person.WE_WITH_YOU), persons("Мы с тобой говорили"))
        val form = PersonForm.of("Мы с тобой говорили")
        assertTrue(form.aboutSpeaker)
        assertTrue(form.aboutAddressee)
    }

    @Test
    fun `латинская ë приводится`() {
        assertEquals(setOf(Person.SECOND), persons("Как твоë имя"))
    }

    @Test
    fun `надеюсь — первое лицо`() {
        assertTrue(Person.FIRST in persons("Надеюсь, получится"))
    }

    // --- Молчит ---

    @Test
    fun `безличная фраза — ни первого, ни второго лица`() {
        assertEquals(setOf(Person.NONE), persons("Вода кипит при ста градусах"))
    }

    @Test
    fun `существительное на у после предлога — не глагол`() {
        val p = persons("У рубанка деревянная колодка")
        assertFalse(Person.FIRST in p)
        assertFalse(Person.SECOND in p)
    }

    @Test
    fun `почему — не глагол первого лица`() {
        assertEquals(setOf(Person.NONE), persons("Почему небо синее?"))
    }

    @Test
    fun `пустой текст — ни одного предложения`() {
        val form = PersonForm.of("")
        assertFalse(form.aboutSpeaker)
        assertFalse(form.aboutAddressee)
    }

    // --- Чего не умеет (закреплено, чтобы не забылось) ---

    @Test
    fun `купил колодку — ложное первое лицо через существительное на у`() {
        assertTrue(Person.FIRST in persons("Купил колодку"))
    }

    @Test
    fun `обобщённое ты читается как о собеседнике`() {
        assertTrue(Person.SECOND in persons("Всегда носишь с собой полотенце"))
    }

    @Test
    fun `повелительное на мягкий знак ловится по первому слову`() {
        for (s in listOf("Проверь настройки", "Ответь коротко", "Поставь чайник")) {
            assertEquals(s, setOf(Person.IMPERATIVE), persons(s))
        }
    }

    @Test
    fun `мягкий знак не делает повелительным -шь, -сь, -ть и слово не первым`() {
        assertFalse(Person.IMPERATIVE in persons("Знаешь дорогу?"))
        assertFalse(Person.IMPERATIVE in persons("Надеюсь на лучшее"))
        assertFalse(Person.IMPERATIVE in persons("Приснилось что-нибудь?"))
        assertFalse(Person.IMPERATIVE in persons("Есть новости?"))
        assertFalse(Person.IMPERATIVE in persons("Опять дождь"))
        assertFalse(Person.IMPERATIVE in persons("Сегодня дверь открыта"))
    }

    @Test
    fun `безличный вопрос о сне сохраняет адрес агент`() {
        assertEquals(Coordinates.Address.AGENT, Coordinates.questionAddress("Приснилось что-нибудь?"))
    }

    @Test
    fun `просьба на мягкий знак не становится вопросом к агенту`() {
        assertEquals(Coordinates.Address.UNDEFINED, Coordinates.questionAddress("Проверь настройки"))
        assertEquals(
            Coordinates.Address.UNDEFINED,
            Coordinates.questionAddress("Проверь настройки", previous = Coordinates.Address.AGENT)
        )
    }

    @Test
    fun `первое слово на мягкий знак — ложное повелительное`() {
        assertTrue(Person.IMPERATIVE in persons("Теперь поговорим о погоде"))
    }

    @Test
    fun `повелительное на -сь не ловится`() {
        assertFalse(Person.IMPERATIVE in persons("Брось мяч"))
    }
}
