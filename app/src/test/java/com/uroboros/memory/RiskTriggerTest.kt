package com.uroboros.memory

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
}
