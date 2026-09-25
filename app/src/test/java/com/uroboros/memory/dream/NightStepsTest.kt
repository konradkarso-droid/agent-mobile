package com.uroboros.memory.dream

import com.uroboros.memory.judge.SelfJudgeDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Шаги ночи без кнопки (NightSteps). Главное — отказы: каждое условие по
 * отдельности обязано удержать модель, и зарядка среди них НЕ значится.
 */
class NightStepsTest {

    private val ready = NightSteps.Inputs(
        running = false,
        emergencyStop = false,
        powerKnown = true,
        watchdogRefusal = null,
        engineBusy = false,
        quietMs = SelfJudgeDecision.QUIET_MS,
        restLeftMs = 0,
        modelChosen = true,
    )

    private fun night(self: String? = null, mirror: String? = null, conclusions: String? = null) =
        DreamNight(
            nightAt = 1_000L,
            dreams = 3,
            dreamers = 3,
            dreamersCold = 0,
            dreamersArchive = 0,
            coldDreams = 0,
            archiveDreams = 0,
            skippedHidden = 0,
            skippedQuestions = 0,
            skippedAgentReports = 0,
            ceilingHit = false,
            selfLineOutcome = self,
            mirrorOutcome = mirror,
            conclusionsOutcome = conclusions,
        )

    @Test
    fun `условия выполнены — можно, и зарядка не нужна`() {
        assertNull(NightSteps.refusal(ready))
    }

    @Test
    fun `каждое условие по отдельности держит`() {
        assertEquals("идёт разбор или ночь", NightSteps.refusal(ready.copy(running = true)))
        assertEquals("взведён аварийный стоп", NightSteps.refusal(ready.copy(emergencyStop = true)))
        assertEquals("сторож ещё не прислал показаний батареи", NightSteps.refusal(ready.copy(powerKnown = false)))
        assertEquals(
            "заряд 20% без зарядки, нужно от 30%",
            NightSteps.refusal(ready.copy(watchdogRefusal = "заряд 20% без зарядки, нужно от 30%")),
        )
        assertEquals("модель занята", NightSteps.refusal(ready.copy(engineBusy = true)))
        assertEquals("тихо 14 мин из 15", NightSteps.refusal(ready.copy(quietMs = 14L * 60_000)))
        assertEquals(
            "модель не загрузилась, следующая попытка через 10 мин",
            NightSteps.refusal(ready.copy(restLeftMs = 9L * 60_000 + 1)),
        )
        assertEquals(
            "модель ни разу не выбиралась — загружать нечего",
            NightSteps.refusal(ready.copy(modelChosen = false)),
        )
    }

    @Test
    fun `новая ночь без итогов — нужны все три`() {
        val missing = NightSteps.missing(night())
        assertTrue(missing.any)
        assertEquals("строка о себе, зеркало, выводы", missing.words())
    }

    @Test
    fun `молчание — ночь по кнопке с итогами всех трёх не трогается`() {
        val missing = NightSteps.missing(night("Строка о себе: …", "Зеркало: …", "Выводы: …"))
        assertFalse(missing.any)
        assertEquals("", missing.words())
    }

    @Test
    fun `отказ «не делаю» — тоже итог, шаг не повторяется`() {
        val missing = NightSteps.missing(night(self = "Строка о себе: не предлагаю — …"))
        assertFalse(missing.selfLine)
        assertEquals("зеркало, выводы", missing.words())
    }

    @Test
    fun `заряд до и после`() {
        assertEquals("заряд 78% → 75%", NightSteps.battery(78, 75, charging = false))
        assertEquals("заряд 78% → 79%, на зарядке", NightSteps.battery(78, 79, charging = true))
        assertEquals("заряд ? → 75%", NightSteps.battery(null, 75, charging = false))
    }

    @Test
    fun `итог — первые строки шагов через точку`() {
        assertEquals(
            "Строка о себе: не предлагаю · Выводы: сделано 1",
            NightSteps.summary(listOf("Строка о себе: не предлагаю\nподробно", "", "Выводы: сделано 1")),
        )
        assertEquals("шаги ничего не сказали", NightSteps.summary(emptyList()))
    }
}
