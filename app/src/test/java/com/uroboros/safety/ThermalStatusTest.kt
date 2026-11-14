package com.uroboros.safety

import org.junit.Assert.assertEquals
import org.junit.Test

/** Перевод статуса системы в зону сторожа и строки прибора (ThermalStatus). */
class ThermalStatusTest {

    @Test
    fun `числа статусов совпадают с Android`() {
        // android.os.PowerManager.THERMAL_STATUS_NONE..SHUTDOWN = 0..6.
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), listOf(
            ThermalStatus.NONE, ThermalStatus.LIGHT, ThermalStatus.MODERATE, ThermalStatus.SEVERE,
            ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN,
        ))
    }

    @Test
    fun `слабый статус - норма, сторож молчит`() {
        assertEquals(SafetyZone.COMFORT, ThermalStatus.zone(ThermalStatus.NONE))
        assertEquals(SafetyZone.COMFORT, ThermalStatus.zone(ThermalStatus.LIGHT))
    }

    @Test
    fun `умеренный - нагрев, сильный - утомление, критический и выше - критическая`() {
        assertEquals(SafetyZone.WARNING, ThermalStatus.zone(ThermalStatus.MODERATE))
        assertEquals(SafetyZone.FATIGUE, ThermalStatus.zone(ThermalStatus.SEVERE))
        assertEquals(SafetyZone.CRITICAL, ThermalStatus.zone(ThermalStatus.CRITICAL))
        assertEquals(SafetyZone.CRITICAL, ThermalStatus.zone(ThermalStatus.EMERGENCY))
        assertEquals(SafetyZone.CRITICAL, ThermalStatus.zone(ThermalStatus.SHUTDOWN))
    }

    @Test
    fun `незнакомый статус - строже всего, а не норма`() {
        assertEquals(SafetyZone.CRITICAL, ThermalStatus.zone(7))
        assertEquals(SafetyZone.CRITICAL, ThermalStatus.zone(-1))
        assertEquals("неизвестный (7)", ThermalStatus.word(7))
    }

    @Test
    fun `пороги производителя - по возрастанию статуса, пустые названы словами`() {
        assertEquals("производитель порогов не задал", ThermalStatus.thresholdsLine(emptyMap()))
        assertEquals(
            "слабый 0.70 · умеренный 0.85 · сильный 1.00",
            ThermalStatus.thresholdsLine(mapOf(3 to 1.0f, 1 to 0.7f, 2 to 0.85f)),
        )
    }

    @Test
    fun `не число не печатается числом`() {
        assertEquals("не число", ThermalStatus.headroom(Float.NaN))
    }
}
