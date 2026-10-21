package com.uroboros.gate

import com.uroboros.gate.SignFixtures.agentB
import com.uroboros.gate.SignFixtures.me
import com.uroboros.gate.SignFixtures.now
import com.uroboros.gate.SignFixtures.packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GateKeysTest {

    private val real = TestSigner()
    private val twin = TestSigner()
    private val nameOf: (com.uroboros.memory.nav.PersonKey) -> String? = { if (it == agentB) "агент Бориса" else null }

    private fun sealOf(raw: ByteArray) = when (val f = GateWire.formOf(raw)) {
        GateWire.Form.CARD -> (GateWire.readCard(raw) as GateWire.CardRead.Ok).seal
        GateWire.Form.PACKET -> (GateWire.read(raw) as GateWire.Read.Ok).seal
        else -> error("$f")
    }

    private fun signedCard(signer: GateSigner, previous: GateSigner? = null, name: String = "Хоуп"): Pair<GateCard, GateWire.Seal> {
        val card = SignFixtures.card(signer, name)
        return card to sealOf(GateSign.seal(GateWire.writeCard(card), signer, previous))!!
    }

    private fun signedPacket(signer: GateSigner) = sealOf(GateSign.seal(GateWire.write(packet), signer))

    /** Ключ настоящего агента известен с первой встречи. */
    private fun known(): List<GateKeyRecord> {
        val (card, seal) = signedCard(real)
        return GateKeys.takeCard(emptyList(), card, seal, now).records
    }

    // --- Ключ не известен ---

    @Test
    fun `ключ не известен — без подписи принимается, с подписью проверить нечем`() {
        assertSame(GateKeys.Check.Unsigned, GateKeys.check(emptyList(), agentB, null))
        assertSame(GateKeys.Check.Unchecked, GateKeys.check(emptyList(), agentB, signedPacket(real)))
    }

    @Test
    fun `первая подписанная карточка задаёт ключ`() {
        val (card, seal) = signedCard(real)
        val t = GateKeys.takeCard(emptyList(), card, seal, now)
        val first = t.change as GateKeys.Change.First
        assertEquals(real.publicKey, first.record.key)
        assertEquals(now, first.record.trustedMs)
        assertTrue(t.cardTaken)
    }

    @Test
    fun `карточка без подписи от незнакомого — ключа нет, карточка принимается`() {
        val t = GateKeys.takeCard(emptyList(), GateCard(agentB, mapOf(GateCard.Field.NAME to "Хоуп")), null, now)
        assertSame(GateKeys.Change.NoKey, t.change)
        assertTrue(t.cardTaken)
    }

    @Test
    fun `карточка подписана не своим ключом — отказ`() {
        val card = SignFixtures.card(real)
        val seal = sealOf(GateSign.seal(GateWire.writeCard(card), twin))
        val t = GateKeys.takeCard(emptyList(), card, seal, now)
        assertEquals(GateRefusal.BAD_SIGNATURE, (t.change as GateKeys.Change.Refused).refusal)
        assertTrue(!t.cardTaken)
    }

    // --- Ключ известен ---

    @Test
    fun `ключ известен — подпись им верна`() {
        assertTrue(GateKeys.check(known(), agentB, signedPacket(real)) is GateKeys.Check.Verified)
    }

    @Test
    fun `ключ известен — без подписи отказ`() {
        val c = GateKeys.check(known(), agentB, null) as GateKeys.Check.Refused
        assertEquals(GateRefusal.UNSIGNED, c.refusal)
    }

    @Test
    fun `ключ известен — подпись двойника отказ`() {
        val c = GateKeys.check(known(), agentB, signedPacket(twin)) as GateKeys.Check.Refused
        assertEquals(GateRefusal.BAD_SIGNATURE, c.refusal)
    }

    @Test
    fun `ключ известен — карточка без подписи отказ`() {
        val t = GateKeys.takeCard(known(), GateCard(agentB, mapOf(GateCard.Field.NAME to "Другое имя")), null, now + 1)
        assertEquals(GateRefusal.UNSIGNED, (t.change as GateKeys.Change.Refused).refusal)
        assertTrue(!t.cardTaken)
    }

    @Test
    fun `та же карточка с тем же ключом — без перемен`() {
        val (card, seal) = signedCard(real, name = "Хоуп 2")
        val t = GateKeys.takeCard(known(), card, seal, now + 1)
        assertTrue(t.change is GateKeys.Change.Same)
        assertTrue(t.cardTaken)
        assertNull(GateKeys.meterLine(agentB, t.change, nameOf))
    }

    // --- Новый ключ ---

    @Test
    fun `новый ключ без подписи прежним — тревога, прежний в силе, карточка не берётся`() {
        val (card, seal) = signedCard(twin, name = "Не Хоуп")
        val t = GateKeys.takeCard(known(), card, seal, now + 1)
        val p = t.change as GateKeys.Change.Pending
        assertEquals(real.publicKey, p.current.key)
        assertEquals(twin.publicKey, p.record.key)
        assertNull(p.record.trustedMs)
        assertTrue(!t.cardTaken)
        // Пакеты прежним ключом идут, новым — нет.
        assertTrue(GateKeys.check(t.records, agentB, signedPacket(real)) is GateKeys.Check.Verified)
        assertEquals(GateRefusal.BAD_SIGNATURE, (GateKeys.check(t.records, agentB, signedPacket(twin)) as GateKeys.Check.Refused).refusal)
        assertTrue(GateKeys.meterLine(agentB, t.change, nameOf)!!.startsWith("Тревога — агент Бориса прислал новый ключ подписи"))
    }

    @Test
    fun `тот же новый ключ ещё раз — та же тревога, запись одна`() {
        val (card, seal) = signedCard(twin)
        val once = GateKeys.takeCard(known(), card, seal, now + 1)
        val twice = GateKeys.takeCard(once.records, card, seal, now + 2)
        assertEquals(once.records, twice.records)
        assertTrue(twice.change is GateKeys.Change.Pending)
    }

    @Test
    fun `владелец подтвердил новый ключ — им и проверяется, прежний кончен`() {
        val (card, seal) = signedCard(twin)
        val pending = GateKeys.takeCard(known(), card, seal, now + 1).records
        val after = GateKeys.confirm(pending, agentB, twin.publicKey, now + 2)!!
        assertEquals(twin.publicKey, GateKeys.current(after, agentB)!!.key)
        assertTrue(GateKeys.check(after, agentB, signedPacket(twin)) is GateKeys.Check.Verified)
        assertEquals(GateRefusal.BAD_SIGNATURE, (GateKeys.check(after, agentB, signedPacket(real)) as GateKeys.Check.Refused).refusal)
        assertEquals(now + 2, after.single { it.key == real.publicKey }.endedMs)
    }

    @Test
    fun `подтвердить нечего — null`() {
        assertNull(GateKeys.confirm(known(), agentB, twin.publicKey, now))
    }

    @Test
    fun `новый ключ, подписанный прежним, — смена без тревоги`() {
        val newPhone = TestSigner()
        val (card, seal) = signedCard(newPhone, previous = real)
        val t = GateKeys.takeCard(known(), card, seal, now + 1)
        val r = t.change as GateKeys.Change.Rotated
        assertEquals(real.publicKey, r.previous.key)
        assertEquals(now + 1, r.previous.endedMs)
        assertEquals(GateKeyRecord.How.ROTATED, r.record.how)
        assertTrue(t.cardTaken)
        assertTrue(GateKeys.check(t.records, agentB, signedPacket(newPhone)) is GateKeys.Check.Verified)
    }

    @Test
    fun `подпись прежним — не тем ключом, — это не смена, а тревога`() {
        val (card, seal) = signedCard(TestSigner(), previous = twin)
        assertTrue(GateKeys.takeCard(known(), card, seal, now + 1).change is GateKeys.Change.Pending)
    }

    // --- Во вратах ---

    @Test
    fun `врата — пакет знакомого агента без подписи отказ, и принял на него не уходит`() {
        val v = GateIntake(me, keys = ::known).receive(GateWire.write(packet), agentB, now) as GateIntake.Verdict.Refused
        assertEquals(GateRefusal.UNSIGNED, v.refusal)
        assertNull(GateAcks.answer(v, me, now, now))
    }

    @Test
    fun `врата — пакет с подписью двойника отказ, номер не тратится`() {
        val numbers = GateNumbers.InMemory()
        val v = GateIntake(me, numbers = numbers, keys = ::known).receive(GateSign.seal(GateWire.write(packet), twin), agentB, now)
        assertEquals(GateRefusal.BAD_SIGNATURE, (v as GateIntake.Verdict.Refused).refusal)
        assertTrue(!numbers.contains(agentB.id, packet.number))
    }

    @Test
    fun `врата — незнакомый агент без подписи принимается как прежде`() {
        val v = GateIntake(me).receive(GateWire.write(packet), agentB, now) as GateIntake.Verdict.Accepted
        assertSame(GateKeys.Check.Unsigned, v.sign)
    }

    @Test
    fun `врата — карточка своим ключом принята с подписью, чужим — отказ`() {
        val card = SignFixtures.card(real)
        val ok = GateIntake(me).receiveCard(GateSign.seal(GateWire.writeCard(card), real), agentB, now) as GateIntake.CardVerdict.Accepted
        assertTrue(ok.seal != null)
        val bad = GateIntake(me).receiveCard(GateSign.seal(GateWire.writeCard(card), twin), agentB, now) as GateIntake.CardVerdict.Refused
        assertEquals(GateRefusal.BAD_SIGNATURE, bad.refusal)
    }

    @Test
    fun `врата — карточка без подписи от знакомого агента отказ`() {
        val raw = GateWire.writeCard(GateCard(agentB, mapOf(GateCard.Field.NAME to "Хоуп")))
        val v = GateIntake(me, keys = ::known).receiveCard(raw, agentB, now) as GateIntake.CardVerdict.Refused
        assertEquals(GateRefusal.UNSIGNED, v.refusal)
    }

    @Test
    fun `врата — принял и поручительство тоже проверяются`() {
        val ack = GateAck(agentB, me, 7L, GateAck.Result.ACCEPTED, now, now)
        val vouch = GateVouch(agentB, com.uroboros.memory.nav.PersonKey("tg:user:333"), me, false)
        val intake = GateIntake(me, keys = ::known)
        assertTrue(intake.receiveAck(GateSign.seal(GateWire.writeAck(ack), real), agentB, now) is GateIntake.AckVerdict.Accepted)
        assertEquals(GateRefusal.UNSIGNED, (intake.receiveAck(GateWire.writeAck(ack), agentB, now) as GateIntake.AckVerdict.Refused).refusal)
        assertTrue(intake.receiveVouch(GateSign.seal(GateWire.writeVouch(vouch), real), agentB, now) is GateIntake.VouchVerdict.Accepted)
        assertEquals(GateRefusal.BAD_SIGNATURE, (intake.receiveVouch(GateSign.seal(GateWire.writeVouch(vouch), twin), agentB, now) as GateIntake.VouchVerdict.Refused).refusal)
    }

    // --- Ключ — не доверенность ---

    @Test
    fun `верная подпись не даёт за кого`() {
        val v = GateIntake(me, keys = ::known).receive(GateSign.seal(GateWire.write(packet), real), agentB, now)
        val accepted = v as GateIntake.Verdict.Accepted
        assertTrue(accepted.sign is GateKeys.Check.Verified)
        val who = GateWho.of(accepted.packet, emptyList(), emptySet(), now).who
        assertTrue(who.toString(), who is GateWho.Who.Unconfirmed)
    }
}
