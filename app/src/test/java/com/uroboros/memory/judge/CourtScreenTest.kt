package com.uroboros.memory.judge

import com.uroboros.memory.judge.CourtScreen.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строка экрана суда.
 *
 * ГЛАВНОЕ ЗДЕСЬ: «просил» и «открылся» — разные вещи, и строка их не путает:
 * просил, а экран не открылся, — так и сказано; не просил, потому что экран и
 * так горел, — тоже сказано, а не молчание.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что телефон открывает экран по уведомлению на весь
 * экран и что с горящим экраном агента не замораживают. Это видно только на
 * устройстве.
 */
class CourtScreenTest {

    private val clock: (Long) -> String = { "t$it" }

    @Test
    fun `суда сам не было — так и сказано`() {
        assertEquals("Экран суда: в этом запуске суд сам не шёл", CourtScreen.line(null, clock))
    }

    @Test
    fun `экран и так горел — не понадобился`() {
        assertEquals(
            "Экран суда: суд с t1, экран и так горел — не понадобился",
            CourtScreen.line(Run(startedAt = 1), clock),
        )
    }

    @Test
    fun `просил, а не открылся — так и сказано, с концом суда`() {
        val run = Run(startedAt = 1).requested(5).requested(9).ended(20)
        assertEquals(
            "Экран суда: просил 2 раза, последний раз в t9 — не открылся, суд кончился в t20",
            CourtScreen.line(run, clock),
        )
    }

    @Test
    fun `открылся — время первого открытия, без лишних счётов при одном разе`() {
        val run = Run(startedAt = 1).requested(5).opened(6)
        assertEquals("Экран суда: открыт в t6", CourtScreen.line(run, clock))
    }

    @Test
    fun `гасили и зажигали — счёт просьб и открытий`() {
        val run = Run(startedAt = 1).requested(5).opened(6).requested(30).opened(31).ended(40)
        assertEquals(
            "Экран суда: открыт в t6 · просил 2 раза, открывался 2 раза, суд кончился в t40",
            CourtScreen.line(run, clock),
        )
    }

    @Test
    fun `нет разрешения — причина, а не молчание`() {
        val run = Run(startedAt = 1).copy(refusal = CourtScreen.NOT_ALLOWED)
        assertTrue(CourtScreen.line(run, clock).startsWith("Экран суда: не показан — разрешение «Уведомления на весь экран» выключено"))
    }

    @Test
    fun `новая просьба снимает прежний отказ`() {
        val run = Run(startedAt = 1).copy(refusal = "сбой").requested(5)
        assertEquals(null, run.refusal)
    }

    @Test
    fun `конец суда пишется один раз`() {
        assertEquals(10L, Run(startedAt = 1).ended(10).ended(50).endedAt)
    }

    @Test
    fun `раз, раза, раз`() {
        assertEquals("1 раз", CourtScreen.times(1))
        assertEquals("3 раза", CourtScreen.times(3))
        assertEquals("5 раз", CourtScreen.times(5))
        assertEquals("12 раз", CourtScreen.times(12))
        assertEquals("22 раза", CourtScreen.times(22))
    }
}
