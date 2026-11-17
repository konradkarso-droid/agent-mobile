package com.uroboros.memory

import com.uroboros.memory.nav.TestVerbs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Подпись говорящего не делает запись утверждением (RiskTrigger.speech).
 *
 * Первые три записи — живые, из памяти устройства: слова людей из телеграма с
 * подписью; каждая шла в ответ, в сон и в суд как утверждение.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ: что подпись узнаётся всегда — что ею считается,
 * решает nav.ForeignSpeech и закрепляет его тест.
 */
class RiskTriggerSignatureTest {

    @Before
    fun dictionaries() = TestVerbs.install()

    @Test
    fun `обычай за подписью - один обычай`() {
        assertTrue(RiskTrigger.isOnlySocial("Nastya: Привет"))
        assertTrue(RiskTrigger.isOnlySocial("Nastya: Приветик"))
        assertTrue(RiskTrigger.isOnlySocial("Иван: Привет"))
        assertTrue(RiskTrigger.isOnlySocial("Пракс: Спасибо! Доброй ночи."))
        assertTrue(RiskTrigger.assertsNothing("Nastya: Привет"))
    }

    @Test
    fun `вопрос и просьба за подписью - не утверждение`() {
        assertTrue(RiskTrigger.isOnlyQuestions("Пракс: Как ты обжился в Телеграме?"))
        assertTrue(RiskTrigger.isOnlyQuestions("Пракс: Привет. Как ты?"))
        assertTrue(RiskTrigger.isOnlyRequests("Пракс: Расскажи про правило."))
    }

    @Test
    fun `молчит - за подписью дело`() {
        assertFalse(RiskTrigger.isOnlySocial("Nastya: Привет. Я пишу стихи."))
        assertFalse(RiskTrigger.assertsNothing("Пракс: Станок называется Вымпел."))
        assertFalse(RiskTrigger.assertsNothing("Denis: во сне уже умеешь)"))
    }

    @Test
    fun `молчит - ярлык не подпись`() {
        // «Итог:» — ярлык владельца, а не говорящий: снимать нечего, и запись
        // с ним остаётся тем, чем была.
        assertFalse(RiskTrigger.isOnlySocial("Итог: спасибо"))
    }

    @Test
    fun `без подписи - как раньше`() {
        assertTrue(RiskTrigger.isOnlySocial("Привет"))
        assertFalse(RiskTrigger.isOnlySocial("Станок называется Вымпел."))
    }
}
