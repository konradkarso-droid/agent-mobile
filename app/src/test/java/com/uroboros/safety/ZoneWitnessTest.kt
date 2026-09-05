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
     * ГРАНИЦА. Сообщение от источника может прийти без части показаний:
     * температуры в нём просто нет. Событие при этом настоящее, счётчик растёт
     * честно, а оценивать ногу нечем.
     *
     * Подстановка COMFORT на это место была бы худшей из возможных: нога,
     * которая не читается вовсе, выглядела бы исправной и спокойной, а
     * растущий счётчик событий подтверждал бы её здоровье. Ровно эта ложь
     * жила в сторожевом коде до 09.2026 — нечитаемая температура превращалась
     * там в 0.0 °C, то есть в «холодно».
     */
    @Test
    fun `сообщение без температуры не делает ногу нормой`() {
        val witness = ZoneWitness(startedAtMs = 0L)

        witness.recordPower(temperatureZone = null, chargeZone = SafetyZone.COMFORT, atMs = 1_000L)

        val snapshot = witness.snapshot(nowMs = 2_000L)
        // Источник жив: событие пришло и посчитано.
        assertEquals(1, snapshot.powerEvents)
        assertEquals(java.lang.Long.valueOf(1_000L), snapshot.powerLastAtMs)
        // А нога — нет. Именно null, а не COMFORT.
        assertNull(snapshot.worstBatteryTemperature)
        assertEquals(SafetyZone.COMFORT, snapshot.worstCharge)

        val text = witness.format(snapshot, ::label)
        // Проверка разведена на две части намеренно, и это не послабление.
        // В одной строке отчёта стоят показания ДВУХ разных предметов:
        // счётчик принадлежит источнику (сообщение о батарее), а оценка —
        // ноге (температура). Сверять их одной склеенной строкой значит
        // ронять проверку обоих при правке любого из них, а по красному тесту
        // потом не видно, какая половина изменилась.
        assertTrue(text.contains("Батарея: событий 1, последнее 1 с назад"))
        assertTrue(text.contains("оценок не было"))
    }

    /**
     * ГРАНИЦА. Не наступившая оценка не участвует в выборе худшего — ни в
     * какую сторону. Если бы null однажды начал затирать запомненное, прибор
     * терял бы память о жаре ровно тогда, когда показания станут ненадёжными,
     * то есть в самый неподходящий момент.
     */
    @Test
    fun `непрочитанная нога не стирает уже виденное худшее`() {
        val witness = ZoneWitness(startedAtMs = 0L)

        witness.recordPower(SafetyZone.FATIGUE, SafetyZone.COMFORT, atMs = 1_000L)
        witness.recordPower(temperatureZone = null, chargeZone = SafetyZone.COMFORT, atMs = 2_000L)

        val snapshot = witness.snapshot(nowMs = 3_000L)
        assertEquals(SafetyZone.FATIGUE, snapshot.worstBatteryTemperature)
        assertEquals(2, snapshot.powerEvents)
    }

    /**
     * ГРАНИЦА, и она закрепляет утверждение из пояснения к снимку: у пустого
     * поля худшего есть ДВЕ причины, и различает их счётчик событий того же
     * источника.
     *
     * Здесь показан второй случай — источник жив, а прочитать из его сообщения
     * нечего. От первого случая (источника не слышно вовсе) он отличается
     * только числом событий, поэтому читать поле худшего в отрыве от счётчика
     * нельзя, а отчёт печатает их всегда вместе.
     */
    @Test
    fun `живой источник без единого показания отличим от молчащего`() {
        val witness = ZoneWitness(startedAtMs = 0L)

        witness.recordPower(temperatureZone = null, chargeZone = null, atMs = 1_000L)

        val snapshot = witness.snapshot(nowMs = 2_000L)
        assertEquals(1, snapshot.powerEvents)
        assertNull(snapshot.worstBatteryTemperature)
        assertNull(snapshot.worstCharge)
        // Наблюдать было нечего — и прибор не достраивает это до «нормы».
        assertNull(snapshot.worstOverall)

        val text = witness.format(snapshot, ::label)
        // Источник молчащим НЕ назван: события от него шли.
        assertTrue(text.contains("Батарея: событий 1"))
        assertTrue(!text.contains("Батарея: событий нет"))
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
