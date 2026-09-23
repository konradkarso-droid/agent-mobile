package com.uroboros.memory.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение уснуть.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: занятый агент не спит, недобравший пол
 * тишины не спит ни при каком давлении, при нулевом давлении не спит. Решение
 * пишет ночь, и каждая из этих проверок держит границу, которую «починка» в
 * сторону частых ночей сломала бы заметно.
 */
class SleepDecisionTest {

    private val minute = 60_000L

    @Test
    fun `занятый не спит, сколько бы ни было тихо`() {
        val why = SleepDecision.gate(nowMs = 100 * minute, busy = true, quietSinceMs = 0)
        assertTrue(why!!, why.contains("работаю"))
    }

    @Test
    fun `меньше пола тишины — не спит`() {
        val why = SleepDecision.gate(nowMs = 4 * minute, busy = false, quietSinceMs = 0)
        assertTrue(why!!, why.contains("тихо 4 мин, меньше 5"))
        assertNull(SleepDecision.gate(nowMs = 5 * minute, busy = false, quietSinceMs = 0))
    }

    @Test
    fun `часы, ушедшие назад, не дают отрицательной тишины`() {
        val why = SleepDecision.gate(nowMs = 0, busy = false, quietSinceMs = 5_000)
        assertTrue(why!!, why.contains("тихо 0 мин"))
    }

    @Test
    fun `шкала тишины — 30 при давлении 1, 10 при 10, не меньше пола`() {
        assertEquals(30 * minute, SleepDecision.quietNeededMs(1))
        assertEquals(10 * minute, SleepDecision.quietNeededMs(10))
        assertEquals(SleepDecision.QUIET_FLOOR_MS, SleepDecision.quietNeededMs(1000))
        assertTrue(SleepDecision.quietNeededMs(5) in (10 * minute + 1) until 30 * minute)
    }

    @Test
    fun `сильнее устал — скорее засыпает`() {
        assertNotNull(SleepDecision.waitReason(pressure = 1, quietMs = 12 * minute))
        assertNull(SleepDecision.waitReason(pressure = 10, quietMs = 12 * minute))
        val why = SleepDecision.waitReason(pressure = 1, quietMs = 12 * minute)!!
        assertTrue(why, why.contains("давление 1, тихо 12 мин из 30"))
    }

    @Test
    fun `без давления не спит, с давлением можно`() {
        assertNotNull(SleepDecision.decide(0))
        assertNull(SleepDecision.decide(SleepDecision.MIN_PRESSURE))
    }
}
