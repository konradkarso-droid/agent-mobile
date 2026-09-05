package com.uroboros.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки прибора наблюдения за зоной.
 *
 * Половина из них — проверки НА МОЛЧАНИЕ: они закрепляют не то, что прибор
 * умеет, а то, чего он не должен делать. Такие границы иначе живут только в
 * голове автора и переезжают при первой же правке молча.
 *
 * Телефон здесь не нужен и нагрев не нужен: время в приборе передаётся
 * снаружи, поэтому «сорок семь минут наблюдения» ставятся числом.
 */
class ZoneWitnessTest {

    private fun label(zone: SafetyZone): String = when (zone) {
        SafetyZone.COMFORT -> "норма"
        SafetyZone.WARNING -> "нагрев"
        SafetyZone.FATIGUE -> "утомление"
        SafetyZone.CRITICAL -> "критическая"
    }

    /**
     * ГРАНИЦА, а не поведение. Выбор худшей зоны сравнивает ordinal, то есть
     * молча полагается на порядок объявления констант. Перестановка их местами
     * компилируется, проходит все остальные тесты и ломает прибор в ту сторону,
     * которую никто не заметит: худшее перестанет быть худшим.
     */
    @Test
    fun `константы зоны идут по возрастанию строгости`() {
        assertTrue(SafetyZone.COMFORT.ordinal < SafetyZone.WARNING.ordinal)
        assertTrue(SafetyZone.WARNING.ordinal < SafetyZone.FATIGUE.ordinal)
        assertTrue(SafetyZone.FATIGUE.ordinal < SafetyZone.CRITICAL.ordinal)
    }

    @Test
    fun `без событий худшее не выдумывается`() {
        val witness = ZoneWitness(startedAtMs = 0L)

        val snapshot = witness.snapshot(nowMs = 60_000L)

        assertEquals(0, snapshot.thermalEvents)
        assertNull(snapshot.thermalLastAtMs)
        assertNull(snapshot.worstThermalStatus)
        assertNull(snapshot.worstBatteryTemperature)
        assertNull(snapshot.worstCharge)
        // Главное: пустой прибор НЕ отвечает COMFORT. Это была бы ложь в
        // благополучную сторону — «наблюдали, всё хорошо» вместо «не наблюдали».
        assertNull(snapshot.worstOverall)
    }

    @Test
    fun `молчание источника названо словами, а не нулём`() {
        val witness = ZoneWitness(startedAtMs = 0L)
        witness.recordPower(SafetyZone.COMFORT, SafetyZone.COMFORT, atMs = 1_000L)

        val text = witness.format(witness.snapshot(nowMs = 47L * 60_000L), ::label)

        assertTrue(text.contains("Тепловой статус: событий нет за 47 мин — источник молчит"))
        // А живой источник в том же отчёте выглядит иначе — иначе отличать было
        // бы нечего.
        assertTrue(text.contains("Батарея: событий 1"))
    }

    @Test
    fun `запоминается худшее, а не последнее`() {
        val witness = ZoneWitness(startedAtMs = 0L)

        witness.recordThermalStatus(SafetyZone.FATIGUE, atMs = 1_000L)
        witness.recordThermalStatus(SafetyZone.COMFORT, atMs = 2_000L)

        val snapshot = witness.snapshot(nowMs = 3_000L)
        assertEquals(SafetyZone.FATIGUE, snapshot.worstThermalStatus)
        // Устройство успело остыть — и именно поэтому мгновенное значение здесь
        // соврало бы: прибор существует ради того, что уже прошло.
        assertEquals(SafetyZone.FATIGUE, snapshot.worstOverall)
    }

    @Test
    fun `итог — строжайшее из трёх ног`() {
        val witness = ZoneWitness(startedAtMs = 0L)

        witness.recordThermalStatus(SafetyZone.COMFORT, atMs = 1_000L)
        witness.recordPower(SafetyZone.WARNING, SafetyZone.CRITICAL, atMs = 1_000L)

        val snapshot = witness.snapshot(nowMs = 2_000L)
        assertEquals(SafetyZone.COMFORT, snapshot.worstThermalStatus)
        assertEquals(SafetyZone.WARNING, snapshot.worstBatteryTemperature)
        assertEquals(SafetyZone.CRITICAL, snapshot.worstCharge)
        assertEquals(SafetyZone.CRITICAL, snapshot.worstOverall)
    }

    /**
     * ГРАНИЦА. Заряд и температура приезжают одним сообщением, поэтому событие
     * от батареи ровно одно. Если счётчик однажды начнут увеличивать на каждую
     * ногу, подписка станет выглядеть вдвое более живой, чем она есть, — а это
     * ровно та ложь, против которой прибор и поставлен.
     */
    @Test
    fun `одно сообщение батареи — одно событие, хотя ног в нём две`() {
        val witness = ZoneWitness(startedAtMs = 0L)

        witness.recordPower(SafetyZone.WARNING, SafetyZone.COMFORT, atMs = 500L)

        val snapshot = witness.snapshot(nowMs = 1_000L)
        assertEquals(1, snapshot.powerEvents)
        assertEquals(java.lang.Long.valueOf(500L), snapshot.powerLastAtMs)
    }

    @Test
    fun `время последнего события считается от переданного момента`() {
        val witness = ZoneWitness(startedAtMs = 0L)
        witness.recordThermalStatus(SafetyZone.COMFORT, atMs = 10_000L)

        val text = witness.format(witness.snapshot(nowMs = 190_000L), ::label)

        assertTrue(text.contains("последнее 3 мин назад"))
    }

    /**
     * ГРАНИЦА. Прибор стоит на пути физического предохранителя, поэтому не
     * имеет права упасть ни на каком входе: переведённые назад часы, снимок
     * раньше события, огромные значения. Уронив поток, наблюдатель сломал бы
     * ровно тот механизм, ради надёжности которого поставлен.
     */
    @Test
    fun `часы назад и запредельные числа не роняют прибор`() {
        val witness = ZoneWitness(startedAtMs = 100_000L)
        witness.recordThermalStatus(SafetyZone.COMFORT, atMs = Long.MAX_VALUE)
        witness.recordPower(SafetyZone.COMFORT, SafetyZone.COMFORT, atMs = -5_000L)

        // Снимок раньше начала наблюдения и раньше событий.
        val snapshot = witness.snapshot(nowMs = 0L)
        assertEquals(0L, snapshot.observedForMs)

        val text = witness.format(snapshot, ::label)
        // Отрицательных промежутков на экране не бывает: они схлопнуты в ноль.
        assertTrue(!text.contains("-"))
    }
}
