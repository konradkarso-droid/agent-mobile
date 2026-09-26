package com.uroboros.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Предложения из одних приветствий и междометий не делают запись утверждением
 * (RiskTrigger.isFillerSentence).
 *
 * Первые три записи — из живой базы устройства: каждая шла в ответ как
 * сведения, и модель произносила её как свою реплику.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ: что список междометий полон. Он объявленный,
 * и граница закреплена ниже как граница.
 */
class RiskTriggerFillerTest {

    @Test
    fun `междометие перед вопросом — запись из одних вопросов`() {
        assertTrue(RiskTrigger.isOnlyQuestions("Хмм. Совсем ничего не помнишь?"))
        assertTrue(RiskTrigger.isOnlyQuestions("Привет. Что нового?"))
        assertTrue(RiskTrigger.isOnlyQuestions("Мда. Ладно. А сколько сторон света ты знаешь?"))
    }

    @Test
    fun `повтор буквы и смайлик не мешают`() {
        assertTrue(RiskTrigger.isOnlyQuestions("Хммммм... Ну привет! Как ты?"))
        assertTrue(RiskTrigger.isOnlyQuestions("Что нового? :)"))
    }

    @Test
    fun `приветствие перед просьбой — запись из одних просьб`() {
        assertTrue(RiskTrigger.isOnlyRequests("Привет снова! Расскажи про правило."))
    }

    @Test
    fun `молчит - приветствие рядом с утверждением`() {
        // «Оцени» не просьба по трафарету, значит, это утверждение: запись
        // остаётся сведениями и идёт в ответ, как и прежде.
        val evaluate = "Привет снова. Оцени фразу - \"Поединок тоже диалог\"."
        assertFalse(RiskTrigger.isOnlyQuestions(evaluate))
        assertFalse(RiskTrigger.isOnlyRequests(evaluate))
        assertFalse(RiskTrigger.isOnlyQuestions("Привет. Станок называется Вымпел. Как дела?"))
    }

    @Test
    fun `молчит - междометие внутри утверждения`() {
        assertFalse(RiskTrigger.isOnlyQuestions("Ну и станок же Вымпел"))
        assertFalse(RiskTrigger.isOnlyRequests("Ну и станок же Вымпел"))
        assertFalse(RiskTrigger.isOnlyQuestions("Добрый день выдался. Ты как?"))
    }

    @Test
    fun `молчит - запись из одного приветствия вопросом не становится`() {
        assertFalse(RiskTrigger.isOnlyQuestions("Привет."))
        assertFalse(RiskTrigger.isOnlyRequests("Привет."))
        assertFalse(RiskTrigger.isOnlyQuestions(""))
    }

    @Test
    fun `молчит - да и нет перед вопросом это ответ, а не пустое`() {
        assertFalse(RiskTrigger.isOnlyQuestions("Нет. Станок называется Вымпел?"))
        assertFalse(RiskTrigger.isOnlyQuestions("Да. А ты?"))
    }

    @Test
    fun `граница - междометие не из списка остаётся утверждением`() {
        // Упадёт, когда список расширят, — и это будет видно.
        assertFalse(RiskTrigger.isOnlyQuestions("Ёлки. Что нового?"))
    }
}
