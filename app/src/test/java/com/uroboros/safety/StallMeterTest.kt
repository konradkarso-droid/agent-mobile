package com.uroboros.safety

import com.uroboros.safety.StallMeter.Beat
import com.uroboros.safety.StallMeter.Episode
import com.uroboros.safety.StallMeter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Простои процесса.
 *
 * ГЛАВНОЕ ЗДЕСЬ: отметка вовремя — не простой; сон телефона, нехватка
 * процессора и медленный ход различаются по трём часам; сон без работы не
 * пишется, а заморозка без работы — пишется.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что на телефоне часы ведут себя так, как здесь
 * (uptime стоит во сне, realtime идёт) и что поток отметок замерзает вместе с
 * процессом. Это видно только на устройстве.
 */
class StallMeterTest {

    private fun beat(at: Long, uptime: Long = at, cpu: Long = 0, group: String = "h-background", working: Boolean = true) =
        Beat(wallMs = 1_000_000 + at, realtimeMs = at, uptimeMs = uptime, cpuMs = cpu, group = group, working = working)

    private val clock: (Long) -> String = { "t$it" }
    private val where: (String) -> String = { "«$it»" }

    @Test
    fun `отметка вовремя и с небольшим опозданием — не простой`() {
        assertNull(StallMeter.episode(beat(0), beat(StallMeter.BEAT_MS)))
        assertNull(StallMeter.episode(beat(0), beat(StallMeter.BEAT_MS + StallMeter.STALL_MS - 1)))
    }

    @Test
    fun `телефон не спал, процессора почти не было — процессора не давали`() {
        val gap = 20 * 60_000L
        val e = StallMeter.episode(beat(0, cpu = 5_000), beat(gap, uptime = gap, cpu = 5_200, group = "top-app"))
        assertNotNull(e)
        assertEquals(Kind.NO_CPU, StallMeter.kind(e!!))
        assertEquals(0L, e.sleptMs)
        assertEquals(200L, e.cpuMs)
        assertEquals("h-background", e.groupBefore)
        assertEquals("top-app", e.groupAfter)
    }

    @Test
    fun `телефон спал больше половины — сон`() {
        val gap = 10 * 60_000L
        val e = StallMeter.episode(beat(0), beat(gap, uptime = 60_000))
        assertEquals(Kind.PHONE_SLEPT, StallMeter.kind(e!!))
        assertEquals(gap - 60_000, e.sleptMs)
    }

    @Test
    fun `процессор шёл, а отметка опаздывала — медленно`() {
        val gap = 5 * 60_000L
        val e = StallMeter.episode(beat(0, cpu = 0), beat(gap, uptime = gap, cpu = gap))
        assertEquals(Kind.SLOW, StallMeter.kind(e!!))
    }

    @Test
    fun `сон без работы не пишется, заморозка без работы пишется`() {
        val gap = 30 * 60_000L
        assertNull(StallMeter.episode(beat(0, working = false), beat(gap, uptime = 0, working = false)))
        val frozen = StallMeter.episode(beat(0, working = false), beat(gap, uptime = gap, working = false))
        assertNotNull(frozen)
        assertEquals(false, frozen!!.working)
    }

    @Test
    fun `сон за работой пишется — блокировка сна не удержала`() {
        val gap = 30 * 60_000L
        assertNotNull(StallMeter.episode(beat(0, working = true), beat(gap, uptime = 0)))
    }

    @Test
    fun `простоев нет — сказано с какого момента смотрим, а не пусто`() {
        assertEquals("Простои: не было с t5 (отметка раз в 10 с)", StallMeter.meter(emptyList(), 5, clock, where))
        assertEquals("Простои: отметки ещё не шли", StallMeter.meter(emptyList(), null, clock, where))
    }

    @Test
    fun `последний подробно, прежние коротко и новыми вперёд`() {
        val old = Episode(100, 19 * 60_000L + 40_000, 0, 200, true, "h-background", "top-app")
        val last = Episode(200, 5 * 60_000L, 0, 100, false, "h-background", "h-background")
        val text = StallMeter.meter(listOf(old, last), 1, clock, where)
        val lines = text.lines()
        assertEquals(
            "Простои: последний t200 — 5 мин 0 с, без работы: телефон не спал, процессора агент получил 0.1 с" +
                " — процессора ему не давали · группа «h-background»",
            lines[0],
        )
        assertEquals("· t100 19 мин 40 с, процессора не давали, за работой", lines[1])
    }

    @Test
    fun `группа сменилась — показаны обе`() {
        val e = Episode(1, 120_000, 0, 0, true, "h-background", "top-app")
        assertTrue(StallMeter.meter(listOf(e), 0, clock, where).contains("группа «h-background» → «top-app»"))
    }

    @Test
    fun `хранение туда и обратно, испорченная строка не читается`() {
        val e = Episode(1_794_900_000_000, 1_180_000, 3, 200, true, "h-background", "top-app")
        assertEquals(e, StallMeter.decode(StallMeter.encode(e)))
        assertNull(StallMeter.decode("1,2,3"))
        assertNull(StallMeter.decode("a,b,c,d,1,x,y"))
    }

    @Test
    fun `длительность словами`() {
        assertEquals("45 с", StallMeter.duration(45_000))
        assertEquals("19 мин 40 с", StallMeter.duration(1_180_000))
        assertEquals("3 ч 28 мин", StallMeter.duration(12_514_060))
    }
}
