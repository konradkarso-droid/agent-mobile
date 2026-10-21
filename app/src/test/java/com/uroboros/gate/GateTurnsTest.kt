package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey
import org.junit.Assert.assertEquals
import org.junit.Test

class GateTurnsTest {

    private val kate = PersonKey("tg:user:123")
    private val now = 1_760_947_200_000L

    @Test
    fun `до трёх подряд — можно, третий последний`() {
        assertEquals(GateTurns.Decision.May(2), GateTurns.decide(emptyList(), now))
        assertEquals(GateTurns.Decision.May(1), GateTurns.decide(listOf(now + 1), now))
        assertEquals(GateTurns.Decision.May(0), GateTurns.decide(listOf(now + 1, now + 2), now))
    }

    @Test
    fun `три подряд без реплики владельца — молчу`() {
        assertEquals(GateTurns.Decision.Silent(3), GateTurns.decide(listOf(now + 1, now + 2, now + 3), now))
    }

    @Test
    fun `реплика владельца сбрасывает счёт`() {
        val sent = listOf(now + 1, now + 2, now + 3)
        assertEquals(GateTurns.Decision.May(2), GateTurns.decide(sent, now + 4))
        assertEquals(GateTurns.Decision.May(0), GateTurns.decide(sent, now + 1))
    }

    @Test
    fun `владелец не писал ни разу — считаются все`() {
        assertEquals(GateTurns.Decision.Silent(4), GateTurns.decide(listOf(1L, 2L, 3L, 4L), null))
    }

    @Test
    fun `строка прибора, когда молчу`() {
        assertEquals(
            "Разговор: агент Кати — пакетов подряд без вашей реплики 3, предел 3 — молчу",
            GateTurns.meterLine(kate, GateTurns.Decision.Silent(3)) { "агент Кати" },
        )
    }
}
