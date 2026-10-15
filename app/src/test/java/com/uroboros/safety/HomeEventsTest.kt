package com.uroboros.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Запись «дома» ([HomeEvents]).
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: норма без эпизода не пишется, эпизод,
 * не ставший хуже, не переписывается, испорченная строка не роняет чтение.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что сторож на телефоне присылает зоны и обрывы в
 * запись (проводка ProcessObjects и LlmEngine) — это видно по строке «Дом:».
 */
class HomeEventsTest {

    private fun power(temp: Double = 30.0, percent: Int = 80) =
        PowerState(temperatureCelsius = temp, percent = percent, charging = false, temperatureKnown = true)

    private val label: (SafetyZone) -> String = { it.name.lowercase() }
    private val time: (Long) -> String = { "t$it" }

    @Test
    fun `норма без эпизода — писать нечего`() {
        assertNull(HomeEvents.withZone(emptyList(), SafetyZone.COMFORT, power(), 1))
    }

    @Test
    fun `выход из нормы открывает эпизод, возврат закрывает`() {
        val opened = HomeEvents.withZone(emptyList(), SafetyZone.WARNING, power(41.0), 10)!!
        assertEquals(1, opened.size)
        assertTrue(opened.single().open)
        val closed = HomeEvents.withZone(opened, SafetyZone.COMFORT, power(38.0), 20)!!
        assertEquals(1, closed.size)
        assertEquals(20L, closed.single().endedAt)
    }

    @Test
    fun `внутри эпизода копится худшее, лучшее его не портит`() {
        var ev = HomeEvents.withZone(emptyList(), SafetyZone.WARNING, power(41.0, 50), 10)!!
        ev = HomeEvents.withZone(ev, SafetyZone.CRITICAL, power(51.5, 40), 11)!!
        assertNull("остыл, но не до нормы — эпизод не хуже", HomeEvents.withZone(ev, SafetyZone.WARNING, power(42.0, 45), 12))
        val e = ev.single()
        assertEquals(SafetyZone.CRITICAL, e.zone)
        assertEquals(51.5, e.maxTemp!!, 0.001)
        assertEquals(40, e.minPercent)
    }

    @Test
    fun `непрочитанная температура не превращается в ноль`() {
        val unknown = PowerState(temperatureCelsius = 0.0, percent = 10, charging = false, temperatureKnown = false)
        val e = HomeEvents.withZone(emptyList(), SafetyZone.CRITICAL, unknown, 5)!!.single()
        assertNull(e.maxTemp)
        assertEquals(10, e.minPercent)
    }

    @Test
    fun `обрыв — отдельная запись, эпизод не закрывает`() {
        val opened = HomeEvents.withZone(emptyList(), SafetyZone.CRITICAL, power(51.0), 10)!!
        val cut = HomeEvents.withCut(opened, byHeat = true, at = 11)
        assertEquals(listOf(HomeEvents.Kind.ZONE, HomeEvents.Kind.CUT), cut.map { it.kind })
        assertTrue(cut.first().open)
        val closed = HomeEvents.withZone(cut, SafetyZone.COMFORT, power(), 30)!!
        assertEquals(30L, closed.first().endedAt)
    }

    @Test
    fun `хранится не больше MAX_EVENTS, старшие выпадают`() {
        var ev = emptyList<HomeEvents.Event>()
        repeat(HomeEvents.MAX_EVENTS + 5) { ev = HomeEvents.withCut(ev, false, it.toLong()) }
        assertEquals(HomeEvents.MAX_EVENTS, ev.size)
        assertEquals(5L, ev.first().at)
    }

    @Test
    fun `строка туда и обратно, испорченная — пропускается`() {
        val e = HomeEvents.Event(HomeEvents.Kind.ZONE, 1, 2, SafetyZone.FATIGUE, 46.5, 33)
        assertEquals(e, HomeEvents.decode(HomeEvents.encode(e)))
        val c = HomeEvents.Event(HomeEvents.Kind.CUT, 7, cutByHeat = true)
        assertEquals(c, HomeEvents.decode(HomeEvents.encode(c)))
        assertNull(HomeEvents.decode("мусор"))
        assertNull(HomeEvents.decode("ZONE;x;;;;;0"))
    }

    @Test
    fun `прибор — печатается и без событий`() {
        assertEquals(
            "Дом: за пределами нормы не был (с тех пор, как ведётся запись)",
            HomeEvents.meter(emptyList(), 100, label, time),
        )
    }

    @Test
    fun `прибор — последнее событие словами и счёт за сутки`() {
        val day = HomeEvents.DAY_MS
        val ev = listOf(
            HomeEvents.Event(HomeEvents.Kind.CUT, 0, cutByHeat = false),
            HomeEvents.Event(HomeEvents.Kind.ZONE, 2 * day, null, SafetyZone.CRITICAL, 51.2, 12),
        )
        assertEquals(
            "Дом: последнее — critical, до 51.2°C, заряд до 12%, t${2 * day} – идёт сейчас · за сутки событий 1",
            HomeEvents.meter(ev, 2 * day + 5, label, time),
        )
        assertEquals(
            "сторож оборвал работу модели — потолок непрерывной работы, t0",
            HomeEvents.describe(ev.first(), label, time),
        )
    }
}
