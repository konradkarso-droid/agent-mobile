package com.uroboros.initiative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Условия «агент заговаривает первым» (InitiativeDecision.refusal). Главное
 * здесь — отказы: каждое условие по отдельности обязано удержать агента, иначе
 * «не беспокоить без причины» держится на одном из них.
 */
class InitiativeDecisionTest {

    private val minute = 60_000L
    private val now = 10_000 * minute

    private val ready = InitiativeDecision.Inputs(
        running = false,
        emergencyStop = false,
        powerKnown = true,
        zoneNormal = true,
        watchdogRefusal = null,
        engineBusy = false,
        journalNotRaised = false,
        autoContinue = true,
        timesUnreadable = null,
        ownerReplyAt = now - 61 * minute,
        lastInitiativeAt = null,
        now = now,
        sourceRefusal = null,
        modelChosen = true,
    )

    private fun refusal(i: InitiativeDecision.Inputs) = InitiativeDecision.refusal(i)

    @Test
    fun `все условия выполнены - заговаривает`() {
        assertNull(refusal(ready))
    }

    @Test
    fun `идущий разбор или сон и аварийный стоп удерживают`() {
        assertEquals("идёт разбор памяти или сон", refusal(ready.copy(running = true)))
        assertEquals("взведён аварийный стоп", refusal(ready.copy(emergencyStop = true)))
    }

    @Test
    fun `без показаний батареи не пишет`() {
        assertEquals("сторож ещё не прислал показаний батареи", refusal(ready.copy(powerKnown = false)))
    }

    @Test
    fun `зона не норма - не пишет`() {
        assertEquals("зона не «норма»", refusal(ready.copy(zoneNormal = false)))
    }

    @Test
    fun `отказ сторожа передаётся словами сторожа`() {
        val why = "заряд 20% без зарядки, нужно от 30%"
        assertEquals(why, refusal(ready.copy(watchdogRefusal = why)))
    }

    @Test
    fun `зарядка не требуется`() {
        // Входа «на зарядке» нет вовсе: заряд решает сторож (см. выше).
        assertNull(refusal(ready.copy(watchdogRefusal = null)))
    }

    @Test
    fun `занятая модель удерживает`() {
        assertEquals("модель занята", refusal(ready.copy(engineBusy = true)))
    }

    @Test
    fun `неподнятая лента удерживает, только когда решает владелец`() {
        assertEquals(
            "разговор с диска не поднят — решает владелец",
            refusal(ready.copy(journalNotRaised = true, autoContinue = false)),
        )
        assertNull("настройка включена — лента поднимется сама", refusal(ready.copy(journalNotRaised = true)))
    }

    @Test
    fun `не прочиталось время владельца - не пишет и называет причину`() {
        assertEquals(
            "не прочиталось, когда писал владелец — диск не принял запись",
            refusal(ready.copy(timesUnreadable = "диск не принял запись")),
        )
    }

    @Test
    fun `владелец ни разу не писал - молчание не с чего считать`() {
        assertEquals(
            "владелец ещё не писал — молчание не с чего считать",
            refusal(ready.copy(ownerReplyAt = null)),
        )
    }

    @Test
    fun `молчания меньше часа - ждёт и говорит сколько`() {
        assertEquals("владелец молчит 59 мин из 60", refusal(ready.copy(ownerReplyAt = now - 59 * minute - 30_000)))
        assertNull("ровно час — можно", refusal(ready.copy(ownerReplyAt = now - InitiativeDecision.SILENCE_MS)))
    }

    @Test
    fun `прошлое сообщение без ответа - второго нет, от любого источника`() {
        val asked = ready.copy(lastInitiativeAt = now - 10 * minute, ownerReplyAt = now - 70 * minute)
        assertEquals("прошлое сообщение первым ещё без ответа", refusal(asked))
        assertEquals(
            "и через сутки тоже: напоминаний нет",
            "прошлое сообщение первым ещё без ответа",
            refusal(asked.copy(now = now + 24 * 60 * minute)),
        )
    }

    @Test
    fun `ответ владельца снимает ожидание, а молчание считается от ответа`() {
        val answered = ready.copy(lastInitiativeAt = now - 200 * minute, ownerReplyAt = now - 100 * minute)
        assertNull(refusal(answered))
        assertEquals(
            "владелец молчит 5 мин из 60",
            refusal(answered.copy(ownerReplyAt = now - 5 * minute)),
        )
    }

    @Test
    fun `источнику нечего сказать - не пишет и называет отказ источника`() {
        assertEquals(
            "нечего сказать: любопытство — вклад лидера 3 из 6",
            refusal(ready.copy(sourceRefusal = "любопытство — вклад лидера 3 из 6")),
        )
    }

    @Test
    fun `модель ни разу не выбиралась - загружать нечего`() {
        assertEquals("модель ни разу не выбиралась — загружать нечего", refusal(ready.copy(modelChosen = false)))
    }

    @Test
    fun `называется первое невыполненное условие`() {
        val everything = ready.copy(
            running = true,
            emergencyStop = true,
            powerKnown = false,
            zoneNormal = false,
            engineBusy = true,
            ownerReplyAt = null,
            sourceRefusal = "нечего",
            modelChosen = false,
        )
        assertEquals("идёт разбор памяти или сон", refusal(everything))
        assertEquals("сторож ещё не прислал показаний батареи", refusal(everything.copy(running = false, emergencyStop = false)))
    }

    @Test
    fun `ожидание ответа`() {
        assertFalse("не писал первым", InitiativeDecision.awaiting(null, 5))
        assertTrue("владелец с тех пор не писал", InitiativeDecision.awaiting(10, 9))
        assertTrue("реплик на диске нет", InitiativeDecision.awaiting(10, null))
        assertFalse("реплика после сообщения — ответ", InitiativeDecision.awaiting(10, 11))
    }

    @Test
    fun `строка прибора`() {
        val clock: (Long) -> String = { "12:30" }
        assertEquals(
            "Первым: не пишу — модель занята",
            InitiativeDecision.meter("модель занята", null, null, null, clock = clock),
        )
        assertEquals(
            "пока сообщение ждёт ответа, оно и называется",
            "Первым: написал в 12:30 (сон «мост: а → б»), жду ответа",
            InitiativeDecision.meter("зона не «норма»", 10, "сон «мост: а → б»", 9, clock = clock),
        )
        assertEquals(
            "Первым: написал в 12:30 (сон «мост»), жду ответа · уведомление не отправлено",
            InitiativeDecision.meter(null, 10, "сон «мост»", 9, note = "уведомление не отправлено", clock = clock),
        )
        assertEquals(
            "после ответа — снова причина",
            "Первым: не пишу — владелец молчит 1 мин из 60",
            InitiativeDecision.meter("владелец молчит 1 мин из 60", 10, "сон «мост»", 11, clock = clock),
        )
    }
}
