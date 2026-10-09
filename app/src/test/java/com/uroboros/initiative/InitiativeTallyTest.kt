package com.uroboros.initiative

import com.uroboros.initiative.InitiativeTally.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Счёт проверок «пишу первым» за сутки (InitiativeTally): один итог на
 * проверку, пауза по часам, смена суток, хранение строкой.
 */
class InitiativeTallyTest {

    private val minute = 60_000L
    private val t0 = 1_000_000_000_000L

    private fun add(s: InitiativeTally.State, at: Long, o: Outcome, why: String? = null, day: String = "2026-10-09") =
        InitiativeTally.add(s, at, o, why, day)

    @Test
    fun `каждая проверка — один итог, пауза — самая долгая`() {
        var s = InitiativeTally.EMPTY
        s = add(s, t0, Outcome.SILENCE_SHORT)
        s = add(s, t0 + minute, Outcome.SILENCE_SHORT)
        s = add(s, t0 + minute + 220 * minute, Outcome.NOTHING_TO_SAY, "любопытство — вклад лидера 2 из 6")
        s = add(s, t0 + 222 * minute, Outcome.WROTE)
        val d = s.today!!
        assertEquals(4, d.checks)
        assertEquals(2, d.counts[Outcome.SILENCE_SHORT])
        assertEquals(1, d.counts[Outcome.WROTE])
        assertEquals(220 * minute, d.longestGapMs)
        assertEquals("любопытство — вклад лидера 2 из 6", d.nothingWhy)
    }

    @Test
    fun `первая проверка паузы не даёт`() {
        val s = add(InitiativeTally.EMPTY, t0, Outcome.ZONE)
        assertEquals(0L, s.today!!.longestGapMs)
    }

    @Test
    fun `причина без «нечего сказать» не запоминается`() {
        val s = add(InitiativeTally.EMPTY, t0, Outcome.SILENCE_SHORT, "любопытство — лидера нет")
        assertNull(s.today!!.nothingWhy)
    }

    @Test
    fun `новые сутки — счёт заново, пауза через полночь — новым суткам`() {
        var s = add(InitiativeTally.EMPTY, t0, Outcome.SILENCE_SHORT, day = "2026-10-08")
        s = add(s, t0 + 300 * minute, Outcome.NOTHING_TO_SAY, "имя — выключено", day = "2026-10-09")
        assertEquals("2026-10-09", s.today!!.day)
        assertEquals(1, s.today!!.checks)
        assertEquals(300 * minute, s.today!!.longestGapMs)
        assertEquals("2026-10-08", s.previous!!.day)
        assertEquals(1, s.previous!!.checks)
    }

    @Test
    fun `прибор — сегодня с причинами по убыванию, «написал» всегда`() {
        var s = InitiativeTally.EMPTY
        s = add(s, t0, Outcome.NOTHING_TO_SAY, "вклад 2 из 6", day = InitiativeTally.dayKey(t0))
        repeat(3) { s = add(s, t0 + (it + 1) * minute, Outcome.SILENCE_SHORT, day = InitiativeTally.dayKey(t0)) }
        val lines = InitiativeTally.meter(s, t0 + 5 * minute)
        assertEquals(1, lines.size)
        assertEquals(
            "Первым сегодня: проверок 4, самая долгая пауза 1 мин; написал 0, " +
                "владелец молчал меньше часа 3, нечего сказать 1 (последнее: вклад 2 из 6)",
            lines[0],
        )
    }

    @Test
    fun `прибор молчит словами, когда проверок не было`() {
        assertEquals(listOf("Первым сегодня: проверок ещё не было"), InitiativeTally.meter(InitiativeTally.EMPTY, t0))
    }

    @Test
    fun `вчерашний счёт, когда сегодня проверок ещё не было`() {
        val yesterday = InitiativeTally.dayKey(t0)
        val s = add(InitiativeTally.EMPTY, t0, Outcome.WROTE, day = yesterday)
        val lines = InitiativeTally.meter(s, t0 + 24 * 60 * minute)
        assertEquals("Первым сегодня: проверок ещё не было", lines[0])
        assertTrue(lines[1], lines[1].startsWith("Первым ${yesterday.substring(8)}.${yesterday.substring(5, 7)}: проверок 1"))
    }

    @Test
    fun `хранение строкой туда и обратно`() {
        var s = add(InitiativeTally.EMPTY, t0, Outcome.NOTHING_TO_SAY, "с\tтабом\nи строкой")
        s = add(s, t0 + 3 * minute, Outcome.LOAD_FAILED)
        val back = InitiativeTally.decode(InitiativeTally.encode(s.today))!!
        assertEquals(s.today!!.copy(nothingWhy = "с табом и строкой"), back)
    }

    @Test
    fun `неразборчивая строка — счёт заново, а не падение`() {
        assertNull(InitiativeTally.decode(null))
        assertNull(InitiativeTally.decode(""))
        assertNull(InitiativeTally.decode("мусор"))
        assertNull(InitiativeTally.decode("2026-10-09\tне число\t0\t0\t\t"))
    }

    @Test
    fun `длительность словами`() {
        assertEquals("меньше минуты", InitiativeTally.duration(30_000))
        assertEquals("12 мин", InitiativeTally.duration(12 * minute))
        assertEquals("3 ч", InitiativeTally.duration(180 * minute))
        assertEquals("3 ч 40 мин", InitiativeTally.duration(220 * minute))
    }

    @Test
    fun `каждому условию — свой итог`() {
        InitiativeDecision.Kind.values().forEach { k ->
            assertEquals(k.name, Outcome.of(k).name)
        }
    }
}
