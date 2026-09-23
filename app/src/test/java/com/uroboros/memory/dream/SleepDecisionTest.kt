package com.uroboros.memory.dream

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение уснуть.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: занятый агент не спит, недобравший
 * тишины не спит, при нулевом давлении не спит. Решение пишет ночь, и каждая
 * из этих проверок держит границу, которую «починка» в сторону частых ночей
 * сломала бы заметно.
 */
class SleepDecisionTest {

    private val quiet = SleepDecision.QUIET_MS

    @Test
    fun `занятый не спит, сколько бы ни было тихо`() {
        val why = SleepDecision.gate(nowMs = 10 * quiet, busy = true, quietSinceMs = 0)
        assertNotNull(why)
        assertTrue(why!!, why.contains("работаю"))
    }

    @Test
    fun `недобравший тишины не спит и говорит, сколько набрал`() {
        val why = SleepDecision.gate(nowMs = 12 * 60_000L, busy = false, quietSinceMs = 0)
        assertNotNull(why)
        assertTrue(why!!, why.contains("тихо 12 мин из 30"))
    }

    @Test
    fun `тишина ровно на пороге открывает ворота`() {
        assertNull(SleepDecision.gate(nowMs = quiet, busy = false, quietSinceMs = 0))
    }

    @Test
    fun `часы, ушедшие назад, не дают отрицательной тишины`() {
        val why = SleepDecision.gate(nowMs = 0, busy = false, quietSinceMs = 5_000)
        assertTrue(why!!, why.contains("тихо 0 мин"))
    }

    @Test
    fun `без давления не спит, с давлением спит`() {
        assertNotNull(SleepDecision.decide(0))
        assertNull(SleepDecision.decide(SleepDecision.MIN_PRESSURE))
    }
}
