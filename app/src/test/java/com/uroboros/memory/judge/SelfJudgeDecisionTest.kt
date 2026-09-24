package com.uroboros.memory.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Условия самостоятельного судьи (SelfJudgeDecision.refusal). Главное здесь —
 * отказы: каждое условие по отдельности обязано остановить суд, иначе
 * «начинать строго» держится на одном из них.
 */
class SelfJudgeDecisionTest {

    private val minute = 60_000L

    private val ready = SelfJudgeDecision.Inputs(
        running = false,
        emergencyStop = false,
        powerKnown = true,
        charging = true,
        watchdogRefusal = null,
        engineBusy = false,
        quietMs = 20 * minute,
        restLeftMs = 0,
        modelChosen = true,
    )

    @Test
    fun `все условия выполнены - суд начинается`() {
        assertNull(SelfJudgeDecision.refusal(ready))
    }

    @Test
    fun `без зарядки не судит даже при полном заряде`() {
        assertEquals("не на зарядке", SelfJudgeDecision.refusal(ready.copy(charging = false)))
    }

    @Test
    fun `без показаний батареи не судит`() {
        assertEquals(
            "сторож ещё не прислал показаний батареи",
            SelfJudgeDecision.refusal(ready.copy(powerKnown = false)),
        )
    }

    @Test
    fun `отказ сторожа передаётся словами сторожа`() {
        val why = "устройство в критической зоне (перегрев или разряд)"
        assertEquals(why, SelfJudgeDecision.refusal(ready.copy(watchdogRefusal = why)))
    }

    @Test
    fun `идущий разбор, стоп и занятая модель останавливают`() {
        assertEquals("идёт разбор", SelfJudgeDecision.refusal(ready.copy(running = true)))
        assertEquals("взведён аварийный стоп", SelfJudgeDecision.refusal(ready.copy(emergencyStop = true)))
        assertEquals("модель занята", SelfJudgeDecision.refusal(ready.copy(engineBusy = true)))
    }

    @Test
    fun `тишины меньше порога - ждёт и говорит сколько`() {
        assertEquals("тихо 14 мин из 15", SelfJudgeDecision.refusal(ready.copy(quietMs = 14 * minute + 59_000)))
        assertNull(SelfJudgeDecision.refusal(ready.copy(quietMs = SelfJudgeDecision.QUIET_MS)))
    }

    @Test
    fun `отдых после прогона считается с округлением вверх`() {
        assertEquals(
            "отдыхаю после разбора, ещё 1 мин",
            SelfJudgeDecision.refusal(ready.copy(restLeftMs = 1_000)),
        )
    }

    @Test
    fun `модель ни разу не выбиралась - загружать нечего`() {
        assertEquals(
            "модель ни разу не выбиралась — загружать нечего",
            SelfJudgeDecision.refusal(ready.copy(modelChosen = false)),
        )
    }
}
