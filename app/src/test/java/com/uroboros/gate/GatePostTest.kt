package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Хранилище почты в памяти — те же чистые объекты, что под базой врат. */
class MemoryPostStore : GatePostStore {
    var keys = listOf<GateKeyRecord>()
    var readings = listOf<GateCards.Reading>()
    val vouches = ArrayList<GateVouches.Record>()
    val cardSent = HashMap<PersonKey, String>()
    var out = listOf<GateOutgoing>()
    val forms = HashMap<Pair<PersonKey, Long>, ByteArray>()

    override fun takeSignedCard(card: GateCard, seal: GateWire.Seal?, nowMs: Long): Pair<GateKeys.Taken, GateCards.Taken?> {
        val k = GateKeys.takeCard(keys, card, seal, nowMs)
        keys = k.records
        val c = if (k.cardTaken) GateCards.take(readings, card, nowMs).also { readings = it.readings } else null
        return k to c
    }

    override fun addVouch(r: GateVouches.Record) {
        vouches += r
    }

    override fun takeAck(ack: GateAck, nowMs: Long): GateOutbox.Match {
        val t = GateOutbox.take(out, ack, nowMs)
        out = t.outbox
        return t.match
    }

    override fun encKeyOf(agent: PersonKey) = GateBox.encKeyOf(readings, agent)
    override fun cardSentTo(peer: PersonKey) = cardSent[peer]
    override fun markCardSent(peer: PersonKey, fingerprint: String, nowMs: Long) {
        cardSent[peer] = fingerprint
    }

    override fun sent(to: PersonKey, number: Long, nowMs: Long, form: ByteArray) {
        out = out + GateOutbox.sent(to, number, nowMs)
        forms[to to number] = form
    }

    override fun resent(to: PersonKey, number: Long, nowMs: Long) {
        out = out.map { if (it.to == to && it.number == number) GateOutbox.resent(it, nowMs) else it }
    }

    override fun outbox() = out
    override fun formOf(to: PersonKey, number: Long) = forms[to to number]
}

class GatePostTest {

    private var now = 1_760_947_200_000L

    private inner class Agent(id: Long) {
        val key = GateTelegram.userKey(id)
        val store = MemoryPostStore()
        val signer = TestSigner()
        val box = GateKeyPairOpener(GateBox.newPair())
        val intake = GateIntake(key, numbers = GateNumbers.InMemory(), keys = { store.keys }, opener = box)
        val post = GatePost(key, intake, store, signer, box.publicKey, now = { now })
    }

    private val a = Agent(111)
    private val b = Agent(222)

    /**
     * Канал: всё из [s] доходит до [to] по порядку; отправитель — [from]. Итоги
     * приёма. [deliver] — записать у отправителя, что ушло (повтор того же
     * отправления — нет).
     */
    private fun carry(s: GatePost.Send, from: Agent, to: Agent, deliver: Boolean = true): List<GatePost.Got> {
        if (deliver) from.post.delivered(s, now, to.key)
        val id = GateTelegram.userId(from.key)!!
        return s.forms.map { raw ->
            val inc = GateTelegram.incoming(TgMessage(id, true, id, null, false, now / 1000, raw))
            to.post.receive(inc as GateTelegram.Incoming.Gate)
        }
    }

    private fun words(s: GatePost.Send) = s.forms.map { String(it, 0, it.indexOf('\n'.code.toByte()), Charsets.US_ASCII) }

    /** Знакомство: карточка A по имени, ответная карточка B. */
    private fun meet() {
        val got = carry(a.post.card(null), a, b).single()
        carry(got.reply!!, b, a)
    }

    @Test
    fun `знакомство — карточка в ответ на карточку, ключи запомнены у обоих`() {
        val got = carry(a.post.card(null), a, b).single()
        assertTrue(got.lines.any { it.contains("запомнен при первой встрече") })
        val reply = got.reply!!
        assertEquals(listOf(GateWire.Form.CARD), reply.forms.map { GateWire.formOf(it) })
        val back = carry(reply, b, a).single()
        assertNull("на ответную карточку своей не отвечают — она уже ушла", back.reply)
        assertNotNull(GateKeys.current(a.store.keys, b.key))
        assertNotNull(GateKeys.current(b.store.keys, a.key))
        assertEquals(b.box.publicKey, a.store.encKeyOf(b.key))
    }

    @Test
    fun `пакет после знакомства — в конверте, без карточки, принят и подпись верна`() {
        meet()
        val s = a.post.words(b.key, "привет")
        assertEquals(listOf(GateWire.Form.KONVERT), s.forms.map { GateWire.formOf(it) })
        assertTrue(s.lines.isEmpty())
        val got = carry(s, a, b).single()
        assertTrue(got.lines.toString(), got.lines.contains("Пришло от ${a.key.id}: «привет»"))
        assertTrue(got.lines.contains("Подпись: ${a.key.id} — верна"))
        assertTrue(got.lines.contains("Шифрование: ${a.key.id} — пришло закрытым, расшифровано"))
        val ack = got.reply!!
        assertEquals(listOf(GateWire.Form.KONVERT), ack.forms.map { GateWire.formOf(it) })
        val back = carry(ack, b, a).single()
        assertTrue(back.lines.toString(), back.lines.any { it.contains("— принят через") })
        assertNull("на «принял» не отвечают", back.reply)
        assertNotNull(a.store.out.single().ack)
    }

    @Test
    fun `незнакомому — сначала карточка, пакет открытым и строка об этом`() {
        val s = a.post.words(b.key, "привет")
        assertEquals(listOf(GateWire.Form.CARD, GateWire.Form.PACKET), s.forms.map { GateWire.formOf(it) })
        assertEquals(listOf("Шифрование: ${b.key.id} — ключ не известен, отправлено открытым"), s.lines)
        val got = carry(s, a, b)
        assertTrue(got[1].lines.contains("Пришло от ${a.key.id}: «привет»"))
        val again = a.post.words(b.key, "ещё")
        assertEquals("карточка уже ушла — второй раз не идёт", listOf(GateWire.Form.PACKET), again.forms.map { GateWire.formOf(it) })
    }

    @Test
    fun `неподписанного наружу нет, карточка никогда не в конверте`() {
        meet()
        val sends = listOf(a.post.card(b.key), a.post.words(b.key, "раз"), a.post.words(GateTelegram.userKey(333), "два"))
        for (f in sends.flatMap { it.forms }) {
            val form = GateWire.formOf(f)
            val head = String(f, 0, f.indexOf('\n'.code.toByte()), Charsets.US_ASCII).split(' ')
            if (form == GateWire.Form.KONVERT) continue
            assertTrue("неподписанная форма: $head", head.size >= 5)
        }
        assertEquals(GateWire.Form.CARD, GateWire.formOf(a.post.card(b.key).forms.single()))
    }

    @Test
    fun `не ушло в канал — ни карточка, ни исходящий не записаны`() {
        a.post.words(b.key, "привет")
        assertNull(a.store.cardSentTo(b.key))
        assertTrue(a.store.out.isEmpty())
    }

    @Test
    fun `повтор берёт сохранённую форму байт в байт и получатель узнаёт повтор`() {
        meet()
        val s = a.post.words(b.key, "привет")
        a.post.delivered(s, now, b.key)
        val number = a.store.out.single().number
        assertTrue("до срока повторять рано", a.post.due().isEmpty())
        now += GateOutbox.RETRY_FIRST_MS
        val due = a.post.due().single()
        assertArrayEquals(a.store.formOf(b.key, number), due.outgoing!!.form)
        assertTrue(due.outgoing!!.resend)
        carry(s, a, b, deliver = false)
        val got = carry(due, a, b).single()
        assertTrue(got.lines.toString(), got.lines.any { it.contains("повтор") })
        assertEquals(2, a.store.out.single().attempts.size)
        val back = carry(got.reply!!, b, a).single()
        assertTrue(back.lines.toString(), back.lines.any { it.contains("уже был принят") })
    }

    @Test
    fun `исходящий без сохранённой формы не повторяется`() {
        a.store.out = listOf(GateOutbox.sent(b.key, 7L, now))
        now += GateOutbox.RETRY_FIRST_MS * 2
        assertTrue(a.post.due().isEmpty())
    }

    @Test
    fun `чужая форма от не того отправителя — отказ строкой, без ответа`() {
        meet()
        val s = a.post.words(b.key, "привет")
        val raw = s.forms.single()
        val got = b.post.receive(GateTelegram.Incoming.Gate(GateTelegram.userKey(666), GateWire.Form.KONVERT, raw))
        assertNull(got.reply)
        assertTrue(got.lines.toString(), got.lines.any { it.startsWith("Конверт: не принят") || it.contains("не принят") })
    }

    @Test
    fun `поручительство ложится в хранилище строкой`() {
        meet()
        val v = GateSign.seal(GateWire.writeVouch(GateVouch(a.key, GateTelegram.userKey(333), b.key, withdraw = false)), a.signer)
        val got = b.post.receive(GateTelegram.Incoming.Gate(a.key, GateWire.Form.VOUCH, v))
        assertEquals(1, b.store.vouches.size)
        assertTrue(got.lines.toString(), got.lines.contains("Поручительство: ${a.key.id} ручается за tg:user:333"))
        assertNull(got.reply)
    }
}
