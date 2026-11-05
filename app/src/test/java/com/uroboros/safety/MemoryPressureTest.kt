package com.uroboros.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPressureTest {

    private val gb = 1024L * 1024 * 1024
    private fun r(avail: Double, threshold: Double = 0.9, low: Boolean = false, at: Long = 100_000L) =
        MemoryPressure.Reading((avail * gb).toLong(), (threshold * gb).toLong(), low, at)

    @Test
    fun `мало памяти, движок свободен, экран закрыт — выгрузить`() {
        val d = MemoryPressure.decide(r(0.5), null, modelLoaded = true, busy = null, screenShown = false)
        assertTrue(d is MemoryPressure.Decision.Unload)
        assertEquals("свободно 0.5 ГБ при пороге 0.9 ГБ", (d as MemoryPressure.Decision.Unload).why)
        assertTrue(MemoryPressure.decide(r(2.0, low = true), null, true, null, false) is MemoryPressure.Decision.Unload)
    }

    @Test
    fun `сигнал системы уровня «в фоне» — выгрузить, «экран скрыт» — нет`() {
        assertTrue(MemoryPressure.decide(r(3.0), 40, true, null, false) is MemoryPressure.Decision.Unload)
        assertEquals(MemoryPressure.Decision.Nothing, MemoryPressure.decide(r(3.0), 20, true, null, false))
    }

    @Test
    fun `посреди работы и при открытом экране — не выгружать, и сказано почему`() {
        assertEquals(MemoryPressure.Decision.Hold("идёт суд или ночь"), MemoryPressure.decide(r(0.5), null, true, "суд или ночь", false))
        assertEquals(MemoryPressure.Decision.Hold("открыт экран"), MemoryPressure.decide(r(0.5), null, true, null, true))
    }

    @Test
    fun `молчит — памяти хватает, модели нет, замера нет`() {
        assertEquals(MemoryPressure.Decision.Nothing, MemoryPressure.decide(r(3.0), null, true, null, false))
        assertEquals(MemoryPressure.Decision.Nothing, MemoryPressure.decide(r(0.5), null, false, null, false))
        assertEquals(MemoryPressure.Decision.Nothing, MemoryPressure.decide(null, null, true, null, false))
    }

    @Test
    fun `строка экрана — замер, выгрузки за сутки, что мешало`() {
        val clock: (Long) -> String = { "ЧЧ:$it" }
        assertEquals("Память телефона: замера ещё не было · выгрузок по сигналу за сутки нет", MemoryPressure.meterLine(null, emptyList(), null, clock))
        assertEquals(
            "Память телефона: свободно 0.5 ГБ, порог 0.9 ГБ, мало · выгрузок по сигналу за сутки 2, последняя ЧЧ:7 · не выгружал: открыт экран",
            MemoryPressure.meterLine(r(0.5), listOf(3L, 7L), "открыт экран", clock),
        )
        assertEquals(listOf(5L), MemoryPressure.today(listOf(5L, -90_000_000L), 10L))
    }

    @Test
    fun `перед комой — только замер не старше двух опросов до неё`() {
        assertEquals("Перед комой: свободно 0.5 ГБ, порог 0.9 ГБ, «мало»: да", MemoryPressure.beforeComa(r(0.5), 110_000L))
        assertEquals("Перед комой: свободно 3.0 ГБ, порог 0.9 ГБ, «мало»: нет", MemoryPressure.beforeComa(r(3.0), 110_000L))
        assertNull("замер давний", MemoryPressure.beforeComa(r(0.5), 200_000L))
        assertNull("замер после комы", MemoryPressure.beforeComa(r(0.5), 50_000L))
        assertNull(MemoryPressure.beforeComa(null, 110_000L))
    }
}
