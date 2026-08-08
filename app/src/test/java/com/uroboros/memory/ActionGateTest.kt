package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionGateTest {

    @Test
    fun `safe local reversible action is allowed`() {
        val request = ActionRequest(
            type = ActionType.WRITE_MEMORY,
            requestedBy = "user",
            provenance = ActionProvenance.USER,
            crossesDeviceBoundary = false,
            isReversible = true
        )
        val verdict = ActionGate.evaluate(request)
        assertEquals(GateResult.ALLOW, verdict.result)
    }

    @Test
    fun `irreversible external action from non-user source is denied`() {
        val request = ActionRequest(
            type = ActionType.NETWORK_CALL,
            requestedBy = "agent",
            provenance = ActionProvenance.WEB_FETCH,
            crossesDeviceBoundary = true,
            isReversible = false,
            affectedObjectCount = 5
        )
        val verdict = ActionGate.evaluate(request)
        assertEquals(GateResult.DENY, verdict.result)
    }

    @Test
    fun `unlisted action type is always denied regardless of signals`() {
        val request = ActionRequest(
            type = ActionType.FILE_DELETE,
            requestedBy = "user",
            provenance = ActionProvenance.USER,
            crossesDeviceBoundary = false,
            isReversible = true
        )
        val verdict = ActionGate.evaluate(request)
        assertEquals(GateResult.DENY, verdict.result)
    }

    @Test
    fun `mass operation raises risk weight over single object`() {
        val single = ActionGate.evaluate(
            ActionRequest(
                type = ActionType.WRITE_MEMORY,
                requestedBy = "user",
                provenance = ActionProvenance.USER,
                crossesDeviceBoundary = false,
                isReversible = true,
                affectedObjectCount = 1
            )
        )
        val batch = ActionGate.evaluate(
            ActionRequest(
                type = ActionType.WRITE_MEMORY,
                requestedBy = "user",
                provenance = ActionProvenance.USER,
                crossesDeviceBoundary = false,
                isReversible = true,
                affectedObjectCount = 20
            )
        )
        assert(batch.riskWeight > single.riskWeight)
    }
}
