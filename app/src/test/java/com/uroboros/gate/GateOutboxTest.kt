package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GateOutboxTest {

    private val me = PersonKey("tg:user:789")
    private val kate = PersonKey("tg:user:123")
    private val boris = PersonKey("tg:user:456")
    private val now = 1_760_947_200_000L
    private val hour = 60 * 60_000L
    private val day = 24 * hour
    private val names = mapOf(kate to "агент Кати")
    private val nameOf: (PersonKey) -> String? = { names[it] }

    private fun ackFrom(peer: PersonKey, number: Long, result: GateAck.Result = GateAck.Result.ACCEPTED) =
        GateAck(peer, me, number, result, now + 1000, now + 2000)

    // --- Повторы ---

    @Test
    fun `без ответа — ждать час, потом повторить`() {
        val o = GateOutbox.sent(kate, 7L, now)
        assertEquals(GateOutbox.State.Waiting(now + hour), GateOutbox.state(o, now))
        assertEquals(GateOutbox.State.Waiting(now + hour), GateOutbox.state(o, now + hour - 1))
        assertSame(GateOutbox.State.Resend, GateOutbox.state(o, now + hour))
    }

    @Test
    fun `промежуток растёт вдвое и не больше суток`() {
        var o = GateOutbox.sent(kate, 7L, now)
        var t = now
        val waits = mutableListOf<Long>()
        repeat(7) {
            val next = (GateOutbox.state(o, t) as GateOutbox.State.Waiting).nextMs
            waits += next - t
            t = next
            o = GateOutbox.resent(o, t)
        }
        assertEquals(listOf(1, 2, 4, 8, 16, 24, 24).map { it * hour }, waits)
    }

    @Test
    fun `через семь суток от первой отправки — не дошло`() {
        val o = GateOutbox.resent(GateOutbox.sent(kate, 7L, now), now + 6 * day)
        assertTrue(GateOutbox.state(o, now + 7 * day - 1) !is GateOutbox.State.GaveUp)
        assertSame(GateOutbox.State.GaveUp, GateOutbox.state(o, now + 7 * day))
        assertEquals(GateIntake.Limits.DECLARED.repeatWindowMs, GateOutbox.giveUpMs)
    }

    @Test
    fun `ответ есть — повторов больше нет, даже после срока`() {
        val o = GateOutbox.take(listOf(GateOutbox.sent(kate, 7L, now)), ackFrom(kate, 7L), now + 5000).outbox.single()
        assertTrue(GateOutbox.state(o, now + 30 * day) is GateOutbox.State.Delivered)
        val refused = o.copy(ack = ackFrom(kate, 7L, GateAck.Result.FROM_FUTURE))
        assertTrue(GateOutbox.state(refused, now + 30 * day) is GateOutbox.State.NotTaken)
    }

    // --- Какой «принял» к какому пакету ---

    @Test
    fun `принял находит свой пакет по паре кому и номер`() {
        val outbox = listOf(GateOutbox.sent(kate, 7L, now), GateOutbox.sent(boris, 7L, now), GateOutbox.sent(kate, 8L, now))
        val t = GateOutbox.take(outbox, ackFrom(kate, 7L), now + 5000)
        val m = t.match as GateOutbox.Match.Answered
        assertEquals(kate, m.outgoing.to)
        assertEquals(7L, m.outgoing.number)
        assertEquals(now + 5000, m.outgoing.ackedMs)
        assertEquals(outbox[1], t.outbox[1])
        assertEquals(outbox[2], t.outbox[2])
    }

    @Test
    fun `тот же номер от другого агента — не мой пакет`() {
        val outbox = listOf(GateOutbox.sent(kate, 7L, now))
        val t = GateOutbox.take(outbox, ackFrom(boris, 7L), now)
        assertSame(GateOutbox.Match.Unknown, t.match)
        assertEquals(outbox, t.outbox)
    }

    @Test
    fun `второй принял на повтор ничего не меняет`() {
        val first = GateOutbox.take(listOf(GateOutbox.sent(kate, 7L, now)), ackFrom(kate, 7L), now + 5000).outbox
        val t = GateOutbox.take(first, ackFrom(kate, 7L, GateAck.Result.REPEAT), now + 9000)
        assertTrue(t.match is GateOutbox.Match.Already)
        assertEquals(first, t.outbox)
    }

    // --- Прибор ---

    @Test
    fun `строки прибора`() {
        val o = GateOutbox.sent(kate, 0x3fa1L, now)
        assertEquals(
            "Отправлено: агент Кати, пакет 0000000000003fa1 — нет ответа 3 ч, попыток 2, следующая через 1 ч",
            GateOutbox.meterLine(GateOutbox.resent(o, now + 2 * hour), now + 3 * hour, nameOf),
        )
        assertEquals(
            "Отправлено: агент Кати, пакет 0000000000003fa1 — нет ответа 1 ч, попыток 1, пора повторить",
            GateOutbox.meterLine(o, now + hour, nameOf),
        )
        val done = GateOutbox.take(listOf(o), ackFrom(kate, 0x3fa1L), now + 4 * 60_000L).outbox.single()
        assertEquals("Отправлено: агент Кати, пакет 0000000000003fa1 — принят через 4 мин", GateOutbox.meterLine(done, now + day, nameOf))
        val future = done.copy(ack = ackFrom(kate, 0x3fa1L, GateAck.Result.FROM_FUTURE))
        assertEquals("Отправлено: агент Кати, пакет 0000000000003fa1 — не принят: из будущего", GateOutbox.meterLine(future, now + day, nameOf))
        val lost = GateOutbox.sent(boris, 1L, now)
        assertEquals(
            "Отправлено: tg:user:456, пакет 0000000000000001 — не дошло за 7 сут, попыток 1",
            GateOutbox.meterLine(lost, now + 8 * day, nameOf),
        )
        assertEquals(
            "«Принял» на пакет 0000000000000009: агент Кати — такого пакета я ему не отправлял",
            GateOutbox.meterUnknown(ackFrom(kate, 9L), nameOf),
        )
    }
}
