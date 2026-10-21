package com.uroboros.gate.store

import com.uroboros.gate.GateAck
import com.uroboros.gate.GateAcquaintance
import com.uroboros.gate.GateCard
import com.uroboros.gate.GateCards
import com.uroboros.gate.GateEvidence
import com.uroboros.gate.GateProxy
import com.uroboros.gate.GateVouch
import com.uroboros.gate.GateVouches
import com.uroboros.memory.nav.PersonKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Строка базы врат и запись врат переводятся друг в друга без потерь. */
class GateRowsTest {

    private val me = PersonKey("tg:user:789")
    private val agentB = PersonKey("tg:user:123")
    private val humanB = PersonKey("tg:user:456")
    private val chat = PersonKey("tg:chat:-100500")
    private val now = 1_760_947_200_000L

    @Test
    fun `доверенность — туда и обратно, с пустыми полями и без`() {
        for (p in listOf(
            GateProxy(agentB, humanB, GateProxy.Kind.PERSON, null, null, null),
            GateProxy(agentB, chat, GateProxy.Kind.CHAT, now, now + 1, now + 2),
        )) assertEquals(p, GateProxyRow.of(p).toRecord())
    }

    @Test
    fun `свидетельство — туда и обратно, текст слово в слово`() {
        for (k in GateEvidence.Kind.values()) {
            val e = GateEvidence(k, now, "@x_bot — мой агент\nвторая строка «в кавычках»")
            val row = GateEvidenceRow.of(7L, e)
            assertEquals(7L, row.proxyId)
            assertEquals(e, row.toRecord())
        }
    }

    @Test
    fun `знакомство — туда и обратно`() {
        for (a in listOf(GateAcquaintance(agentB, null, null), GateAcquaintance(agentB, now, now + 5))) {
            assertEquals(a, GateAcquaintanceRow.of(a).toRecord())
        }
    }

    @Test
    fun `поручительство и снятие — туда и обратно`() {
        for (w in listOf(false, true)) {
            val r = GateVouches.Record(GateVouch(agentB, humanB, me, w), now)
            assertEquals(r, GateVouchRow.of(r).toRecord())
        }
    }

    @Test
    fun `показание карточки — туда и обратно, отпечаток тот же`() {
        val cards = listOf(
            GateCard(agentB, emptyMap()),
            GateCard(agentB, mapOf(GateCard.Field.NAME to "Хоуп")),
            GateCard(agentB, mapOf(GateCard.Field.NAME to "Хоуп", GateCard.Field.REPO to "https://x", GateCard.Field.ABOUT to "о себе")),
        )
        for (c in cards) for (ended in listOf(null, now + 9)) {
            val r = GateCards.Reading(c, now, ended)
            val back = GateCardReadingRow.of(r).toRecord()
            assertEquals(r, back)
            assertEquals(c.fingerprint, back.card.fingerprint)
        }
    }

    @Test
    fun `исходящий без ответа — ответа нет, с ответом — принял мне`() {
        val none = GateOutgoingRow(agentB.id, 42L, now, null, null, null, null)
        assertNull(none.ack(me))
        val row = GateOutgoingRow(agentB.id, 42L, now, GateAck.Result.STALE.name, now + 1, now + 2, now + 3)
        assertEquals(GateAck(agentB, me, 42L, GateAck.Result.STALE, now + 1, now + 2), row.ack(me))
    }
}
