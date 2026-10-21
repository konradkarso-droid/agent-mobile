package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateClockTest {

    private val me = PersonKey("tg:user:789")
    private val kate = PersonKey("tg:user:123")
    private val now = 1_760_947_200_000L
    private val nameOf: (PersonKey) -> String? = { if (it == kate) "агент Кати" else null }

    /**
     * Пакет ушёл в t1 по моим часам, у Кати пришёл в t2 и ответ ушёл в t3 по её
     * часам, ответ пришёл ко мне в t4.
     */
    private fun answered(t1: Long, t2: Long, t3: Long, t4: Long, attempts: List<Long> = listOf(t1)) =
        GateOutgoing(kate, 7L, attempts, GateAck(kate, me, 7L, GateAck.Result.ACCEPTED, t2, t3), t4)

    private fun taken(o: GateOutgoing) = (GateClock.of(o) as GateClock.Reading.Taken).sample

    private fun dropped(o: GateOutgoing): String {
        val r = GateClock.of(o)
        assertTrue("ждали выброшенный замер, а взят: $r", r is GateClock.Reading.Dropped)
        return (r as GateClock.Reading.Dropped).why
    }

    @Test
    fun `часы Кати спешат на 3 минуты, путь в обе стороны по 10 секунд`() {
        val skew = 3 * 60_000L
        val s = taken(answered(now, now + 10_000 + skew, now + 15_000 + skew, now + 25_000))
        assertEquals(skew, s.offsetMs)
        assertEquals(10_000L, s.errorMs)
    }

    @Test
    fun `часы Кати отстают — расхождение меньше нуля`() {
        val skew = -90_000L
        assertEquals(skew, taken(answered(now, now + 1000 + skew, now + 1000 + skew, now + 2000)).offsetMs)
    }

    @Test
    fun `путь не поровну — ошибка не больше разброса`() {
        // Туда 1 с, обратно 59 с, часы сходятся точно.
        val s = taken(answered(now, now + 1000, now + 1000, now + 60_000))
        assertTrue(s.toString(), kotlin.math.abs(s.offsetMs) <= s.errorMs)
    }

    @Test
    fun `повторов несколько — замер не берётся`() {
        val o = answered(now + 3_600_000, now + 3_600_500, now + 3_600_600, now + 3_601_000, attempts = listOf(now, now + 3_600_000))
        assertEquals("попыток 2 — не знаю, на какую ответ", dropped(o))
    }

    @Test
    fun `ответ шёл дольше двух минут — замер выброшен, а не усреднён`() {
        val sixHours = 6 * 3_600_000L
        assertEquals("ответ шёл 6 ч", dropped(answered(now, now + 1000, now + 1000, now + sixHours)))
        assertTrue(GateClock.of(answered(now, now + 1000, now + 1000, now + GateClock.MAX_ROUND_TRIP_MS)) is GateClock.Reading.Taken)
        assertTrue(GateClock.of(answered(now, now + 1000, now + 1000, now + GateClock.MAX_ROUND_TRIP_MS + 1)) is GateClock.Reading.Dropped)
    }

    @Test
    fun `задержка меньше нуля — часы перевели на ходу`() {
        assertEquals("моменты не сходятся — часы перевели на ходу", dropped(answered(now, now, now + 60_000, now + 1000)))
    }

    @Test
    fun `без ответа мерить нечего`() {
        assertEquals("ответа нет", dropped(GateOutbox.sent(kate, 7L, now)))
    }

    @Test
    fun `строки прибора`() {
        val fast = GateClock.of(answered(now, now + 190_000, now + 190_000, now + 20_000))
        assertEquals("Часы: агент Кати — спешат на ~3 мин (±10 с)", GateClock.meterLine(kate, fast, nameOf))
        val slow = GateClock.of(answered(now, now - 3_590_000, now - 3_590_000, now + 20_000))
        assertEquals("Часы: агент Кати — отстают на ~1 ч (±10 с)", GateClock.meterLine(kate, slow, nameOf))
        val same = GateClock.of(answered(now, now + 3000, now + 3000, now + 10_000))
        assertEquals("Часы: агент Кати — сходятся с моими (±5 с)", GateClock.meterLine(kate, same, nameOf))
        val lost = GateClock.of(answered(now, now + 1000, now + 1000, now + 6 * 3_600_000L))
        assertEquals("Часы: агент Кати — замер выброшен: ответ шёл 6 ч", GateClock.meterLine(kate, lost, nameOf))
    }

    // --- Свои часы по серверу ---

    @Test
    fun `сервер впереди больше минуты — мои отстают`() {
        assertEquals(
            "Часы: мои отстают от сервера телеграма на ~2 мин",
            GateClock.selfCheck(now + 2 * 60_000L, now, GateTelegram.SERVER),
        )
    }

    @Test
    fun `сервер впереди на минуту и меньше, или позади на сутки — молчит`() {
        assertNull(GateClock.selfCheck(now + GateClock.SELF_TOLERANCE_MS, now, GateTelegram.SERVER))
        assertNull(GateClock.selfCheck(now, now, GateTelegram.SERVER))
        // Телефон был выключен: сообщение ждало сутки — о часах это ничего не говорит.
        assertNull(GateClock.selfCheck(now - 24 * 3_600_000L, now, GateTelegram.SERVER))
    }
}
