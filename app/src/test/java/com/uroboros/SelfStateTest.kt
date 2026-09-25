package com.uroboros

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строка состояния агента.
 *
 * ГЛАВНОЕ ЗДЕСЬ — МОЛЧАНИЕ БЕЗ ПЕРЕМЕН: одинаковое состояние не даёт строки,
 * иначе лента забивалась бы повторами. И второе — ни слова во втором лице:
 * переписанное моделью в ответ «ты» уходит собеседнику. Время в строке
 * зависит от часового пояса машины, поэтому проверяются слова, а не часы.
 */
class SelfStateTest {

    private val base = SelfState.Snapshot(
        nightAt = 1_000L,
        starts = 2, aliveSince = 500L, sleepPressure = 0, ribbonPercent = 20,
    )

    /** Слова второго лица — их в строке быть не должно. */
    private val secondPerson = Regex("(?iu)(^|[^\\p{L}])(ты|тебя|тебе|твой|твоей|твоего|твоё|твоих)([^\\p{L}]|$)")

    @Test
    fun `без перемен — молчит`() {
        assertNull(SelfState.line(base, base))
    }

    @Test
    fun `поспал — сказано когда, без снов`() {
        val text = SelfState.line(base, base.copy(nightAt = 2_000L))!!
        assertTrue(text, text.contains("Я поспал в "))
        assertFalse("сны не называются", text.contains("снов"))
    }

    @Test
    fun `давление — один раз на цикл`() {
        val tired = base.copy(sleepPressure = 1)
        assertTrue(SelfState.line(base, tired)!!.contains("у меня накопилось"))
        assertNull(SelfState.line(tired, tired.copy(sleepPressure = 5)))
    }

    @Test
    fun `лента — на каждом пороге один раз`() {
        assertTrue(SelfState.line(base, base.copy(ribbonPercent = 55))!!.contains("50 %"))
        assertNull(SelfState.line(base.copy(ribbonPercent = 55), base.copy(ribbonPercent = 70)))
        assertTrue(SelfState.line(base.copy(ribbonPercent = 70), base.copy(ribbonPercent = 76))!!.contains("75 %"))
    }

    @Test
    fun `кома — сказано`() {
        assertTrue(SelfState.line(base, base.copy(starts = 3))!!.contains("я очнулся"))
    }

    @Test
    fun `новый процесс говорит всё, что правда`() {
        val text = SelfState.line(null, base.copy(sleepPressure = 2))!!
        assertTrue(text, text.contains("перерыв"))
        assertTrue(text, text.contains("Я поспал"))
        assertTrue(text, text.contains("накопилось"))
    }

    @Test
    fun `ни одной перемены во втором лице`() {
        val everything = SelfState.line(null, base.copy(starts = 3, sleepPressure = 2, ribbonPercent = 95))!!
        assertFalse(everything, secondPerson.containsMatchIn(everything))
    }

    @Test
    fun `после установки первый подъём — не кома`() {
        val text = SelfState.line(null, base.copy(starts = 1, nightAt = null))
        assertNull(text)
    }
}
