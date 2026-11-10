package com.uroboros.memory

import com.uroboros.memory.nav.AddressSorter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandingConsentTest {

    private val who = "ChatLink: ответ в телеграме"
    private val t0 = 1_700_000_000_000L

    private fun reply(by: String = who, provenance: ActionProvenance = ActionProvenance.MODEL_OUTPUT, type: ActionType = ActionType.SEND_MESSAGE) =
        ActionRequest(type = type, requestedBy = by, provenance = provenance, crossesDeviceBoundary = true, isReversible = false)

    private val consent = StandingConsent.grantedByOwner(ActionType.SEND_MESSAGE, who, "владелец", t0)

    @Test
    fun `без согласия ответ в телеграм не уходит`() {
        val v = ActionGate.evaluate(reply(), now = t0)
        assertEquals(GateResult.DENY, v.result)
        assertEquals(8.0, v.riskWeight, 0.0)
    }

    @Test
    fun `с согласием уходит, вес честный, в тексте — чьё`() {
        val v = ActionGate.evaluate(reply(), now = t0 + 60_000, consent = consent)
        assertEquals(GateResult.ALLOW, v.result)
        assertEquals(8.0, v.riskWeight, 0.0)
        assertTrue(v.reason.contains("согласием владельца"))
    }

    @Test
    fun `переспрос кодом весит столько же и проходит тем же согласием`() {
        val v = ActionGate.evaluate(reply(provenance = ActionProvenance.CODE), now = t0, consent = consent)
        assertEquals(8.0, v.riskWeight, 0.0)
        assertEquals(GateResult.ALLOW, v.result)
    }

    @Test
    fun `согласие не открывает чужой компонент, чужой тип и истёкший срок`() {
        assertEquals(GateResult.DENY, ActionGate.evaluate(reply(by = "TermuxKotlinCompiler"), now = t0, consent = consent).result)
        assertEquals(GateResult.DENY, ActionGate.evaluate(reply(type = ActionType.EXTERNAL_PROCESS), now = t0, consent = consent).result)
        val late = ActionGate.evaluate(reply(), now = t0 + StandingConsent.DEFAULT_TERM_MS, consent = consent)
        assertEquals(GateResult.DENY, late.result)
        assertTrue(late.reason.contains("не подходит"))
    }

    @Test
    fun `аварийный стоп сильнее согласия`() {
        EmergencyStop.clear()
        try {
            EmergencyStop.triggerManual("тест")
            assertEquals(GateResult.DENY, ActionGate.evaluate(reply(), now = t0, consent = consent).result)
        } finally {
            EmergencyStop.clear()
        }
    }

    @Test
    fun `восстановленное — только целое и неистёкшее`() {
        assertNotNull(StandingConsent.restored(ActionType.SEND_MESSAGE, who, "в", t0, t0 + 1000, t0 + 10))
        assertNull(StandingConsent.restored(ActionType.SEND_MESSAGE, who, "в", t0, t0 + 1000, t0 + 1000))
        assertNull("конец не позже начала", StandingConsent.restored(ActionType.SEND_MESSAGE, who, "в", t0, t0, t0))
        assertNull("начало далеко в будущем", StandingConsent.restored(ActionType.SEND_MESSAGE, who, "в", t0 + 3L * 24 * 3600_000, t0 + 4L * 24 * 3600_000, t0))
    }

    @Test
    fun `срок по умолчанию — семь дней`() {
        assertEquals(7L * 24 * 3600_000, consent.expiresAt - consent.grantedAt)
        assertFalse(consent.isExpired(t0 + 6L * 24 * 3600_000))
    }

    @Test
    fun `судья берёт из чата только обращённое`() {
        assertTrue("с экрана — как всегда", AddressSorter.judgedRecord(null))
        assertTrue(AddressSorter.judgedRecord("TO_AGENT"))
        assertFalse(AddressSorter.judgedRecord("ABOUT_AGENT"))
        assertFalse(AddressSorter.judgedRecord("DOUBTFUL"))
        assertFalse(AddressSorter.judgedRecord("GENERAL"))
        assertFalse("незнакомое — не судится", AddressSorter.judgedRecord("SOMETHING"))
    }

    @Test
    fun `согласие без срока не истекает, а стоп и чужой компонент его по-прежнему не пускают`() {
        val open = StandingConsent.grantedByOwner(ActionType.SEND_MESSAGE, who, "в", t0, termMs = StandingConsent.NO_TERM)
        assertTrue(open.openEnded)
        val farFuture = t0 + 3650L * 24 * 3600_000
        assertEquals(GateResult.ALLOW, ActionGate.evaluate(reply(), now = farFuture, consent = open).result)
        assertEquals(GateResult.DENY, ActionGate.evaluate(reply(by = "TermuxKotlinCompiler"), now = farFuture, consent = open).result)
        EmergencyStop.clear()
        try {
            EmergencyStop.triggerManual("тест")
            assertEquals(GateResult.DENY, ActionGate.evaluate(reply(), now = farFuture, consent = open).result)
        } finally {
            EmergencyStop.clear()
        }
    }

    @Test
    fun `согласие на срок бессрочным не считается и восстанавливается без срока только целым`() {
        assertFalse(consent.openEnded)
        assertNotNull(StandingConsent.restored(ActionType.SEND_MESSAGE, who, "в", t0, StandingConsent.NO_TERM, t0 + 10))
        assertNull("начало далеко в будущем", StandingConsent.restored(ActionType.SEND_MESSAGE, who, "в", t0 + 3L * 24 * 3600_000, StandingConsent.NO_TERM, t0))
    }
}
