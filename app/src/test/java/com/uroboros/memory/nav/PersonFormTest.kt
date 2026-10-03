package com.uroboros.memory.nav

import com.uroboros.memory.nav.PersonForm.Person
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Фразы придуманные, не из памяти владельца. */
class PersonFormTest {

    /** Таблица глаголов — как на телефоне (см. TestVerbs). */
    @Before
    fun verbs() = TestVerbs.install()

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

    /** Было ложным «я» через существительное на -у; по таблице «колодку» не глагол, остаётся прошедшее без лица. */
    @Test
    fun `купил колодку — не я, а не ясно`() {
        assertEquals(setOf(Person.UNCLEAR), persons("Купил колодку"))
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

    // --- Оборот темы ---

    @Test
    fun `оборот темы — лицо после предлога темы`() {
        assertEquals(setOf(PersonForm.Person.FIRST), PersonForm.topicPersons("Что знаешь обо мне?"))
        assertEquals(setOf(PersonForm.Person.SECOND), PersonForm.topicPersons("Что я говорил про тебя?"))
        assertEquals(setOf(PersonForm.Person.WE_WITH_YOU), PersonForm.topicPersons("Расскажи о нас"))
    }

    @Test
    fun `без оборота темы — пусто`() {
        assertEquals(emptySet<PersonForm.Person>(), PersonForm.topicPersons("Как меня зовут?"))
        assertEquals(emptySet<PersonForm.Person>(), PersonForm.topicPersons("Расскажи о себе"))
        assertEquals(emptySet<PersonForm.Person>(), PersonForm.topicPersons("Что знаешь о радуге?"))
        assertEquals(emptySet<PersonForm.Person>(), PersonForm.topicPersons("Мне бы про чай"))
    }

    // --- Глагол на -у/-ю — по таблице ---

    /**
     * Живые вопросы к агенту, получавшие адрес «владелец»: существительное на
     * -у/-ю читалось глаголом первого лица. По таблице — не глагол.
     */
    @Test
    fun `существительное на -у не даёт я — вопрос к агенту остаётся к агенту`() {
        assertFalse(Person.FIRST in PersonForm.ofSentence("Назови модель и температуру"))
        assertEquals(Coordinates.Address.AGENT, Coordinates.questionAddress("Привет, назови температуру и уровень заряда"))
        assertEquals(Coordinates.Address.AGENT, Coordinates.questionAddress("Ты имел ввиду - Африканский рог?"))
        // Просьба без лица — «не определён», а не «владелец», как было.
        assertEquals(Coordinates.Address.UNDEFINED, Coordinates.questionAddress("Посмотри свою модель LLM, пожалуйста."))
        assertEquals(Coordinates.Address.UNDEFINED, Coordinates.questionAddress("Процитируй поэму с начала."))
    }

    /** Молчание: глагол первого лица без местоимения — по-прежнему «я». */
    @Test
    fun `глагол из таблицы без местоимения — я`() {
        assertEquals(Coordinates.Address.OWNER, Coordinates.questionAddress("Да, скоро ложусь"))
        assertTrue(Person.FIRST in PersonForm.ofSentence("Пока да, отдыхаю - выходной."))
        assertEquals(Coordinates.Address.BOTH, Coordinates.questionAddress("Вообще, тобой) Твой код пишу."))
    }

    /**
     * Исключением, от известного: незнакомое слово на -у/-ю (сленг, опечатка)
     * — «не ясно», а не «не глагол»; знакомый не-глагол лица не даёт.
     */
    @Test
    fun `незнакомое слово на -у — не ясно, знакомое не-глагол — ничего`() {
        assertEquals(setOf(Person.UNCLEAR), PersonForm.ofSentence("Гуглю это"))
        assertEquals(setOf(Person.UNCLEAR), PersonForm.ofSentence("Обсужляю это"))
        assertEquals(setOf(Person.NONE), PersonForm.ofSentence("Какая температуру"))
        // Другое лицо в предложении есть — сомнение его не перебивает.
        assertEquals(setOf(Person.SECOND), PersonForm.ofSentence("Ты юзаю это?"))
    }

    /** Без трафарета незнакомое от знакомого не отличить: «нет в таблице» — не глагол. */
    @Test
    fun `без трафарета нет в таблице — не глагол`() {
        PersonForm.words = null
        try {
            assertEquals(setOf(Person.NONE), PersonForm.ofSentence("Гуглю это"))
            assertTrue(Person.FIRST in PersonForm.ofSentence("Да, скоро ложусь"))
        } finally {
            TestVerbs.install()
        }
    }

    /**
     * Без таблицы слово на -у/-ю сомнительно: «не ясно», а не «я» — и вопрос
     * не получает адрес «владелец» по догадке. Местоимение решает и без неё.
     */
    @Test
    fun `без таблицы слово на -у — не ясно, местоимение — я`() {
        PersonForm.verbs = null
        try {
            assertEquals(setOf(Person.UNCLEAR), PersonForm.ofSentence("Да, скоро ложусь"))
            assertEquals(Coordinates.Address.UNDEFINED, Coordinates.questionAddress("Да, скоро ложусь"))
            assertEquals(setOf(Person.SECOND), PersonForm.ofSentence("Ты имел ввиду рог?"))
            assertTrue(Person.FIRST in PersonForm.ofSentence("Я скоро ложусь"))
        } finally {
            TestVerbs.install()
        }
    }
}
