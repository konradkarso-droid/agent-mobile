package com.uroboros.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Признак «одни просьбы» (RiskTrigger.isOnlyRequests).
 *
 * Первая группа — просьбы из живой памяти устройства: они снились и служили
 * мостами, хотя ничего не утверждают.
 *
 * ГЛАВНОЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ, вторая группа. Каждая закрепляет
 * утверждение, которое обязано остаться утверждением: признак, принявший факт
 * за просьбу, однажды спрячет этот факт от ответов. Случайное расширение трафарета
 * сломает одну из них заметно.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ: что просьбы узнаются полно. Трафарет закрытый, и
 * просьба глаголом не из него остаётся утверждением — это закреплено как
 * граница, а не как цель.
 */
class RiskTriggerRequestsTest {

    @Test
    fun `просьбы из живой памяти узнаются`() {
        assertTrue(RiskTrigger.isOnlyRequests("Расскажи про правило"))
        assertTrue(RiskTrigger.isOnlyRequests("Назови цвета радуги"))
        assertTrue(RiskTrigger.isOnlyRequests("Перепроверь по памяти свой ответ"))
        assertTrue(RiskTrigger.isOnlyRequests("Расскажи, что ты помнишь"))
        assertTrue(RiskTrigger.isOnlyRequests("Расскажи кто ты и кто я"))
    }

    @Test
    fun `вводные слова и вежливая форма`() {
        assertTrue(RiskTrigger.isOnlyRequests("А что помнишь о желтом, расскажи? Ну расскажи."))
        assertTrue(RiskTrigger.isOnlyRequests("Давай, расскажи про станок"))
        assertTrue(RiskTrigger.isOnlyRequests("Пожалуйста, назовите цвета радуги"))
        assertTrue(RiskTrigger.isOnlyRequests("Расскажи-ка про правило"))
    }

    @Test
    fun `жаргонные просьбы узнаются наравне с обычными`() {
        assertTrue(RiskTrigger.isOnlyRequests("Запили список дел"))
        assertTrue(RiskTrigger.isOnlyRequests("запилИ список дел"))
        assertTrue(RiskTrigger.isOnlyRequests("Накидай идей для названия"))
        assertTrue(RiskTrigger.isOnlyRequests("Глянь, что там с погодой"))
    }

    @Test
    fun `формы трафарета - корень с приставкой`() {
        assertTrue(RiskTrigger.isOnlyRequests("Перескажи своими словами"))
        assertTrue(RiskTrigger.isOnlyRequests("Рассчитай расход на неделю"))
        assertTrue(RiskTrigger.isOnlyRequests("Допишите конец истории"))
        assertTrue(RiskTrigger.isOnlyRequests("Обдумай план"))
    }

    @Test
    fun `знак ударения не мешает`() {
        assertTrue(RiskTrigger.isOnlyRequests("Расскажи\u0301 про правило"))
    }

    @Test
    fun `просьба рядом с вопросом - одни просьбы`() {
        assertTrue(RiskTrigger.isOnlyRequests("Что ты помнишь? Расскажи."))
    }

    @Test
    fun `одни вопросы сюда не входят - у них свой признак`() {
        assertFalse(RiskTrigger.isOnlyRequests("Что ты помнишь?"))
    }

    // --- Проверки на молчание: утверждения, которые обязаны остаться ими. ---

    @Test
    fun `запомни несёт факт`() {
        assertFalse(RiskTrigger.isOnlyRequests("Запомни: станок называется Вымпел"))
    }

    @Test
    fun `запиши несёт факт`() {
        assertFalse(RiskTrigger.isOnlyRequests("Запиши: станок называется Вымпел"))
        assertFalse(RiskTrigger.isOnlyRequests("Пиши дальше, станок называется Вымпел"))
    }

    @Test
    fun `скажи и докажи с что несут факт`() {
        assertFalse(RiskTrigger.isOnlyRequests("Скажи, что станок называется Вымпел"))
        assertFalse(RiskTrigger.isOnlyRequests("Докажи, что станок называется Вымпел"))
    }

    @Test
    fun `смотри и поверь начинают утверждение`() {
        assertFalse(RiskTrigger.isOnlyRequests("Смотри, станок называется Вымпел"))
        assertFalse(RiskTrigger.isOnlyRequests("Поверь, станок называется Вымпел"))
    }

    @Test
    fun `напомни что несёт факт`() {
        assertFalse(RiskTrigger.isOnlyRequests("Напомни, что я работаю по субботам"))
    }

    @Test
    fun `запрет просьбой не считается`() {
        assertFalse(RiskTrigger.isOnlyRequests("Не путай, станок называется Вымпел"))
        assertFalse(RiskTrigger.isOnlyRequests("Не думай о правиле, забудь."))
    }

    @Test
    fun `глагол-носитель со словом что остаётся утверждением`() {
        assertFalse(RiskTrigger.isOnlyRequests("Напиши, что станок выключен"))
        assertFalse(RiskTrigger.isOnlyRequests("Допишите, что станок выключен"))
        assertFalse(RiskTrigger.isOnlyRequests("Проверь, что станок выключен"))
    }

    @Test
    fun `просьба рядом с утверждением - утверждение`() {
        assertFalse(RiskTrigger.isOnlyRequests("Расскажи про станок. Он называется Вымпел."))
    }

    @Test
    fun `вопросительное слово без знака - утверждение`() {
        assertFalse(RiskTrigger.isOnlyRequests("Как хорошо, что ты есть"))
    }

    @Test
    fun `слово из списка не в начале - утверждение`() {
        assertFalse(RiskTrigger.isOnlyRequests("Так, ну твою склонность к юмору я ощущаю, потом её разовьёшь"))
        assertFalse(RiskTrigger.isOnlyRequests("Я попросил его: расскажи про правило"))
    }

    @Test
    fun `просьба глаголом не из списка - граница`() {
        // Закреплённая граница: трафарет закрытый.
        assertFalse(RiskTrigger.isOnlyRequests("Помоги с задачей"))
    }

    @Test
    fun `пустая запись - не просьба`() {
        assertFalse(RiskTrigger.isOnlyRequests(""))
        assertFalse(RiskTrigger.isOnlyRequests("   "))
    }

    @Test
    fun `признак вопроса не изменился`() {
        assertTrue(RiskTrigger.isOnlyQuestions("Что ты помнишь?"))
        assertFalse(RiskTrigger.isOnlyQuestions("Расскажи про правило"))
    }
}
