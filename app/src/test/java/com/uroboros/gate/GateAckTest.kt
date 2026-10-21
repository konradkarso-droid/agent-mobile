package com.uroboros.gate

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class GateAckTest {

    private val me = PersonKey("tg:user:789")
    private val agentB = PersonKey("tg:user:123")
    private val humanB = PersonKey("tg:user:456")
    private val stranger = PersonKey("tg:user:666")
    private val now = 1_760_947_200_000L
    private val day = 24 * 60 * 60 * 1000L

    private val packet = GatePacket(
        from = agentB, represents = humanB, to = me, number = 0x1234_5678_9abc_def0L, sentMs = now,
        place = Place.DIRECT, saidBy = humanB, about = listOf(humanB), aboutUnclear = false, atMs = now,
        zone = "Europe/Moscow", source = SourceKind.USER_STATED, kind = SentenceKind.Kind.STATEMENT,
        topic = listOf("суббота"), exact = emptyMap(), words = "в субботу я занят",
    )

    /** «Принял» от меня агенту Бориса — как его прочтёт агент Бориса. */
    private val ack = GateAck(me, agentB, packet.number, GateAck.Result.ACCEPTED, now + 1000, now + 2000)

    private fun wrap(body: String, magic: String = "prinyal", version: Int = GateWire.ACK_VERSION): ByteArray {
        val b = body.toByteArray(Charsets.UTF_8)
        val c = CRC32().also { it.update(b) }
        return "$magic $version ${b.size} ${String.format("%08x", c.value)}\n".toByteArray() + b
    }

    private val ackBody = String(GateWire.writeAck(ack), Charsets.UTF_8).substringAfter('\n')

    private fun readFail(raw: ByteArray): GateRefusal {
        val r = GateWire.readAck(raw)
        assertTrue("ждали отказ, а прочитано: $r", r is GateWire.AckRead.Fail)
        return (r as GateWire.AckRead.Fail).refusal
    }

    // --- Провод ---

    @Test
    fun `принял доходит как отправлен`() {
        for (r in GateAck.Result.values()) {
            val a = ack.copy(result = r)
            assertEquals(GateWire.AckRead.Ok(a), GateWire.readAck(GateWire.writeAck(a)))
        }
    }

    @Test
    fun `тело принял — ровно эти поля и номер строкой`() {
        assertEquals(
            "{\"from\":\"tg:user:789\",\"to\":\"tg:user:123\",\"number\":\"123456789abcdef0\"," +
                "\"result\":\"accepted\",\"got\":${now + 1000},\"sent\":${now + 2000}}",
            ackBody,
        )
    }

    @Test
    fun `незнакомое поле, незнакомый итог, нет поля — не читается`() {
        assertEquals(GateRefusal.UNKNOWN_FIELD, readFail(wrap(ackBody.replaceFirst("{", "{\"trust\":true,"))))
        assertEquals(GateRefusal.MALFORMED, readFail(wrap(ackBody.replace("accepted", "believed"))))
        assertEquals(GateRefusal.NO_ORIGIN, readFail(wrap(ackBody.replace("\"from\":\"tg:user:789\",", ""))))
        assertEquals(GateRefusal.NO_KEY, readFail(wrap(ackBody.replace(",\"to\":\"tg:user:123\"", ""))))
        assertEquals(GateRefusal.MALFORMED, readFail(wrap(ackBody.replace("\"123456789abcdef0\"", "\"123\""))))
    }

    @Test
    fun `ответ ушёл раньше, чем пакет пришёл, — порча`() {
        assertEquals(GateRefusal.MALFORMED, readFail(GateWire.writeAck(ack.copy(sentMs = ack.gotMs - 1))))
    }

    @Test
    fun `принял и пакет слов не читаются друг за друга`() {
        assertEquals(GateRefusal.NOT_AN_ACK, readFail(GateWire.write(packet)))
        val r = GateWire.read(GateWire.writeAck(ack))
        assertEquals(GateRefusal.NOT_A_PACKET, (r as GateWire.Read.Fail).refusal)
    }

    @Test
    fun `чужая версия принял — не читается`() {
        assertEquals(GateRefusal.FOREIGN_VERSION, readFail(wrap(ackBody, version = GateWire.ACK_SIGNED_VERSION + 1)))
    }

    @Test
    fun `форма узнаётся по шапке, чужое — не форма`() {
        assertEquals(GateWire.Form.PACKET, GateWire.formOf(GateWire.write(packet)))
        assertEquals(GateWire.Form.ACK, GateWire.formOf(GateWire.writeAck(ack)))
        assertEquals(GateWire.Form.CARD, GateWire.formOf(GateWire.writeCard(GateCard(agentB, emptyMap()))))
        assertEquals(GateWire.Form.VOUCH, GateWire.formOf(GateWire.writeVouch(GateVouch(agentB, stranger, me, false))))
        for (s in listOf("", "привет", "trafaret", "prinyalx 1 2 3\n{}", "%PDF-1.4 ...")) {
            assertNull(s, GateWire.formOf(s.toByteArray()))
        }
    }

    // --- Приём ---

    /** Приём у агента Бориса: я ему отвечаю. */
    private fun intakeB() = GateIntake(agentB)

    @Test
    fun `принял от того, кому слал, — принят`() {
        assertEquals(GateIntake.AckVerdict.Accepted(ack), intakeB().receiveAck(GateWire.writeAck(ack), me, now))
    }

    @Test
    fun `принял не с того ключа канала — отправитель не тот`() {
        val v = intakeB().receiveAck(GateWire.writeAck(ack), stranger, now) as GateIntake.AckVerdict.Refused
        assertEquals(GateRefusal.NOT_SENDER, v.refusal)
    }

    @Test
    fun `принял другому агенту — не мне`() {
        val v = intakeB().receiveAck(GateWire.writeAck(ack.copy(to = stranger)), me, now) as GateIntake.AckVerdict.Refused
        assertEquals(GateRefusal.NOT_FOR_ME, v.refusal)
        assertEquals("«Принял»: не принят — не мне: кому tg:user:666, я tg:user:123", v.words)
    }

    @Test
    fun `принял и пакеты слов — один счёт потока`() {
        val intake = intakeB()
        repeat(GateIntake.Limits.DECLARED.perWindow) { intake.receiveAck(GateWire.writeAck(ack), me, now) }
        val v = intake.receiveAck(GateWire.writeAck(ack), me, now) as GateIntake.AckVerdict.Refused
        assertEquals(GateRefusal.FLOOD, v.refusal)
    }

    // --- На что отвечать ---

    private fun answer(v: GateIntake.Verdict) = GateAcks.answer(v, me, now + 1000, now + 2000)

    @Test
    fun `принят — принял с номером пакета и моими моментами`() {
        val v = GateIntake(me).receive(GateWire.write(packet), agentB, now)
        assertEquals(ack, answer(v))
    }

    @Test
    fun `повтор — тоже принял, иначе отправитель шлёт снова`() {
        val intake = GateIntake(me)
        intake.receive(GateWire.write(packet), agentB, now)
        val v = intake.receive(GateWire.write(packet), agentB, now)
        assertEquals(GateAck.Result.REPEAT, answer(v)!!.result)
        assertTrue(answer(v)!!.result.delivered)
    }

    @Test
    fun `не мне, устарел, из будущего — отвечается, что не принят`() {
        val notMine = GateIntake(me).receive(GateWire.write(packet.copy(to = stranger)), agentB, now)
        val old = now - 8 * day
        val stale = GateIntake(me).receive(GateWire.write(packet.copy(sentMs = old, atMs = old)), agentB, now)
        val far = now + 2 * day
        val future = GateIntake(me).receive(GateWire.write(packet.copy(sentMs = far, atMs = far)), agentB, now)
        assertEquals(GateAck.Result.NOT_FOR_ME, answer(notMine)!!.result)
        assertEquals(GateAck.Result.STALE, answer(stale)!!.result)
        assertEquals(GateAck.Result.FROM_FUTURE, answer(future)!!.result)
        for (v in listOf(notMine, stale, future)) {
            assertEquals(agentB, answer(v)!!.to)
            assertTrue(!answer(v)!!.result.delivered)
        }
    }

    @Test
    fun `не прочитан, подменён отправитель, сверх потока — не отвечается`() {
        val broken = GateIntake(me).receive("мусор".toByteArray(), agentB, now)
        val forged = GateIntake(me).receive(GateWire.write(packet), stranger, now)
        val intake = GateIntake(me)
        repeat(GateIntake.Limits.DECLARED.perWindow) { intake.receive(GateWire.write(packet.copy(number = it.toLong())), agentB, now) }
        val flood = intake.receive(GateWire.write(packet.copy(number = 999L)), agentB, now)
        assertEquals(GateRefusal.FLOOD, (flood as GateIntake.Verdict.Refused).refusal)
        for (v in listOf(broken, forged, flood)) assertNull(v.toString(), answer(v))
    }

    @Test
    fun `не поверил — всё равно принял, дошло не значит поверил`() {
        // Агент говорит за чат, а сказавшим назван чат — «кто это» отказывает «с третьих рук».
        val chat = PersonKey("tg:chat:-100500")
        val p = packet.copy(represents = chat, saidBy = chat)
        val v = GateIntake(me).receive(GateWire.write(p), agentB, now)
        val proxy = GateProxy(agentB, chat, GateProxy.Kind.CHAT, now - day, null, null)
        val who = GateWho.of((v as GateIntake.Verdict.Accepted).packet, listOf(proxy), emptySet(), now).who
        assertTrue(who.toString(), who is GateWho.Who.Refused)
        assertEquals(GateAck.Result.ACCEPTED, answer(v)!!.result)
    }

    @Test
    fun `часы перевели назад между приходом и ответом — ответ не раньше прихода`() {
        val v = GateIntake(me).receive(GateWire.write(packet), agentB, now)
        val a = GateAcks.answer(v, me, now + 5000, now + 1000)!!
        assertEquals(a.gotMs, a.sentMs)
        assertTrue(GateWire.readAck(GateWire.writeAck(a)) is GateWire.AckRead.Ok)
    }
}
