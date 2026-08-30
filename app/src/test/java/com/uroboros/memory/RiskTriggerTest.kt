package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class RiskTriggerTest {

    private fun sticker(
        content: String,
        importance: Importance = Importance.MEDIUM,
        tag: String = "test",
        id: Long = 0
    ): Sticker =
        Sticker(id = id, content = content, tag = tag, importance = importance.name)

    @Test
    fun `plain low-signal sticker does not trigger review`() {
        val candidate = sticker("обычная короткая запись без особых признаков")
        val decision = RiskTrigger.evaluate(candidate, emptyList())
        assertFalse(decision.shouldReview)
    }

    @Test
    fun `single low-weight signal alone does not trigger`() {
        val candidate = sticker("важный факт, но короткий", importance = Importance.HIGH)
        val decision = RiskTrigger.evaluate(candidate, emptyList())
        assertFalse(decision.shouldReview)
    }

    @Test
    fun `two low-weight signals together trigger review`() {
        val longUncertainText = "наверное " + "слово ".repeat(40)
        val candidate = sticker(longUncertainText, importance = Importance.HIGH)
        val decision = RiskTrigger.evaluate(candidate, emptyList())
        assertTrue(decision.shouldReview)
        assertTrue(decision.reasons.contains("high_importance"))
        assertTrue(decision.reasons.contains("uncertainty_marker"))
    }

    @Test
    fun `negation mismatch on similar content triggers contradiction`() {
        val existing = sticker("я живу в Москве постоянно", tag = "личное", id = 1)
        val candidate = sticker("я не живу в Москве постоянно", tag = "личное")
        val decision = RiskTrigger.evaluate(candidate, listOf(existing))
        assertTrue(decision.shouldReview)
        assertTrue(decision.reasons.contains("contradiction"))
    }

    @Test
    fun `number mismatch on similar content triggers contradiction`() {
        val existing = sticker("встреча назначена на 15 число", tag = "план", id = 2)
        val candidate = sticker("встреча назначена на 20 число", tag = "план")
        val decision = RiskTrigger.evaluate(candidate, listOf(existing))
        assertTrue(decision.shouldReview)
        assertTrue(decision.reasons.contains("contradiction"))
    }

    @Test
    fun `unrelated existing sticker does not trigger contradiction`() {
        val existing = sticker("рецепт борща на ужин", tag = "еда", id = 3)
        val candidate = sticker("завтра дедлайн по проекту", tag = "работа")
        val decision = RiskTrigger.evaluate(candidate, listOf(existing))
        assertFalse(decision.shouldReview)
    }

    // --- Числительные словами и границы механизма.
    //
    // Проверка number mismatch выше пользуется цифрами, поэтому карта
    // WORD_NUMBERS ею не затрагивается вовсе: механизм сравнения чисел был
    // накрыт тестом, а разбор числительного словом — нет.
    //
    // Половина проверок ниже утверждает МОЛЧАНИЕ. Поиск противоречий грубый
    // по устройству, и его легко принять за более умный, чем он есть; эти
    // проверки закрепляют границы, чтобы следующий читающий увидел их сразу,
    // а не вывел из неудачного случая через полгода.

    @Test
    fun `числительное словом считается числом`() {
        val existing = sticker("В радуге шесть цветов.", tag = "факты", id = 10)
        val candidate = sticker("В радуге семь цветов.", tag = "факты")
        val decision = RiskTrigger.evaluate(candidate, listOf(existing))
        assertTrue(decision.shouldReview)
        assertTrue(decision.reasons.contains("contradiction"))
        assertEquals(10L, decision.contradictionCandidateId)
    }

    @Test
    fun `цифра и слово — одно и то же число, а не разные`() {
        val existing = sticker("В радуге семь цветов.", tag = "факты", id = 11)
        val candidate = sticker("В радуге 7 цветов.", tag = "факты")
        val decision = RiskTrigger.evaluate(candidate, listOf(existing))
        assertNull(decision.contradictionCandidateId)
    }

    @Test
    fun `совпадающие числа противоречием не считаются`() {
        val existing = sticker("Семь цветов в радуге.", tag = "факты", id = 12)
        val candidate = sticker("В радуге семь цветов.", tag = "факты")
        val decision = RiskTrigger.evaluate(candidate, listOf(existing))
        assertNull(decision.contradictionCandidateId)
    }

    /**
     * Граница: в карте числительных лежит именительный падеж, поэтому «в пяти
     * цветах» числом не считается. Схожесть текстов здесь около 0.80, то есть
     * до сравнения чисел дело доходит — молчит именно разбор числительного.
     */
    @Test
    fun `косвенный падеж числительного не распознаётся`() {
        val existing = sticker("В пяти цветах радуги мало.", tag = "факты", id = 13)
        val candidate = sticker("В радуге пять цветов.", tag = "факты")
        val decision = RiskTrigger.evaluate(candidate, listOf(existing))
        assertNull(decision.contradictionCandidateId)
    }

    /**
     * Граница: одно и то же, сказанное другими словами, даёт схожесть около
     * 0.33 и до сравнения чисел не доходит вовсе. Опустить порог ради таких
     * пар нельзя — ложные срабатывания на живых записях начинаются раньше,
     * чем ловятся эти. Пересказ формальными средствами не берётся.
     */
    @Test
    fun `пересказ другими словами не ловится`() {
        val existing = sticker("Пауки имеют четыре ноги.", tag = "факты", id = 14)
        val candidate = sticker("У паука восемь ног.", tag = "факты")
        val decision = RiskTrigger.evaluate(candidate, listOf(existing))
        assertNull(decision.contradictionCandidateId)
    }

    /**
     * Числа сравниваются только после порога схожести. Проверка выше про
     * рецепт борща берёт записи вообще без чисел; здесь числа есть у обеих и
     * они разные, а противоречия всё равно нет.
     */
    @Test
    fun `разные числа в записях на разные темы противоречием не считаются`() {
        val existing = sticker("Рубанок стоил пять тысяч.", tag = "покупки", id = 15)
        val candidate = sticker("В радуге семь цветов.", tag = "факты")
        val decision = RiskTrigger.evaluate(candidate, listOf(existing))
        assertNull(decision.contradictionCandidateId)
    }
}
