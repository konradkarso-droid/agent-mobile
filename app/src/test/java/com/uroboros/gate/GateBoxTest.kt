package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.interfaces.ECPublicKey

class GateBoxTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hexOf(b: ByteArray) = b.joinToString("") { String.format("%02x", it) }
    private fun pair(sk: String, pk: String) = KeyPair(GateSign.decodeKey(GateSign.b64(hex(pk)))!!, GateBox.privateKey(hex(sk)))

    // --- Контрольные примеры RFC 9180, приложение A.3.1: DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM, режим base ---

    private val info = hex("4f6465206f6e2061204772656369616e2055726e")
    private val pkEm = "04a92719c6195d5085104f469a8b9814d5838ff72b60501e2c4466e5e67b325ac98536d7b61a1af4b78e5b7f951c0900be863c403ce65c9bfcb9382657222d18c4"
    private val skEm = "4995788ef4b9d6132b249ce59a77281493eb39af373d236a1fe415cb0c2d7beb"
    private val pkRm = "04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0"
    private val skRm = "f3ce7fdae57e1a310d87f1ebbde6f328be0a99cdbcadf4d6589cf29de4b8ffd2"
    private val sharedSecret = "c0d26aeab536609a572b07695d933b589dcf363ff9d93c93adea537aeabb8cb8"
    private val key = "868c066ef58aae6dc589b6cfdd18f97e"
    private val baseNonce = "4e0bc5018beba4bf004cca59"
    private val pt = hex("4265617574792069732074727574682c20747275746820626561757479")
    private val aad0 = hex("436f756e742d30")
    private val ct0 = "5ad590bb8baa577f8619db35a36311226a896e7342a6d836d8b7bcd2f20b6c7f9076ac232e3ab2523f39513434"

    @Test
    fun `контрольный пример RFC 9180 A_3 — общий секрет, ключ, значение и закрытое сходятся`() {
        val eph = pair(skEm, pkEm)
        val pkR = GateSign.decodeKey(GateSign.b64(hex(pkRm)))!!
        val dh = GateBox.dh(eph.private as java.security.interfaces.ECPrivateKey, pkR)
        val shared = GateBox.extractAndExpand(dh, hex(pkEm) + hex(pkRm))
        assertEquals(sharedSecret, hexOf(shared))
        val (k, n) = GateBox.keySchedule(shared, info)
        assertEquals(key, hexOf(k))
        assertEquals(baseNonce, hexOf(n))
        val (enc, ct) = GateBox.seal(pkR, info, aad0, pt, eph)
        assertEquals(pkEm, hexOf(enc))
        assertEquals(ct0, hexOf(ct))
    }

    @Test
    fun `контрольный пример RFC 9180 A_3 — получатель открывает`() {
        val opener = GateKeyPairOpener(pair(skRm, pkRm))
        assertArrayEquals(pt, GateBox.open(opener, hex(pkEm), info, aad0, hex(ct0)))
        // Другое aad — метка не сходится: молчит, а не отдаёт мусор.
        assertNull(GateBox.open(opener, hex(pkEm), info, hex("436f756e742d31"), hex(ct0)))
    }

    // --- Точка на кривой ---

    /** Та же x, y на единицу больше — вне кривой (у точки кривой с этой x ровно два y: y и p − y). */
    private fun offCurve(wire: String): String {
        val b = GateSign.unb64(wire)!!
        val y = BigInteger(1, b.copyOfRange(33, 65)).add(BigInteger.ONE)
        val yb = y.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        return GateSign.b64(b.copyOfRange(0, 33) + yb)
    }

    @Test
    fun `ключ вне кривой не читается, ключ кривой — читается`() {
        val good = GateSign.encodeKey(GateBox.newPair().public as ECPublicKey)
        assertNotNull(GateSign.decodeKey(good))
        assertNull(GateSign.decodeKey(offCurve(good)))
    }

    // --- Конверт во вратах ---

    private val me = SignFixtures.me
    private val agentB = SignFixtures.agentB
    private val now = SignFixtures.now
    private val packet = SignFixtures.packet
    private val signerB = TestSigner()
    private val myBox = GateKeyPairOpener(GateBox.newPair())

    /** Открыватель, который считает, сколько раз его просили согласовать. */
    private class Counting(val inner: GateOpener) : GateOpener {
        var calls = 0
        override val publicKey get() = inner.publicKey
        override fun agree(peer: ECPublicKey): ByteArray {
            calls++
            return inner.agree(peer)
        }
    }

    private fun knownB(): List<GateKeyRecord> {
        val card = SignFixtures.card(signerB)
        val seal = (GateWire.readCard(GateSign.seal(GateWire.writeCard(card), signerB)) as GateWire.CardRead.Ok).seal
        return GateKeys.takeCard(emptyList(), card, seal, now).records
    }

    private fun intake(opener: GateOpener? = myBox, limits: GateIntake.Limits = GateIntake.Limits.DECLARED) =
        GateIntake(me, limits = limits, keys = { knownB() }, opener = opener)

    private fun signedPacket(p: GatePacket = packet, signer: GateSigner = signerB) = GateSign.seal(GateWire.write(p), signer)

    @Test
    fun `закрытый подписанный пакет открывается и проходит врата с верной подписью`() {
        val raw = GateBox.close(signedPacket(), myBox.publicKey)
        assertEquals(GateWire.Form.KONVERT, GateWire.formOf(raw))
        val v = intake().receiveKonvert(raw, agentB, now) as GateIntake.KonvertVerdict.Packet
        val ok = v.verdict as GateIntake.Verdict.Accepted
        assertEquals(packet.words, ok.packet.words)
        assertTrue(ok.sign is GateKeys.Check.Verified)
    }

    @Test
    fun `канал не видит ни слов, ни формы, ни подписи`() {
        val inner = signedPacket()
        val raw = GateBox.close(inner, myBox.publicKey)
        val text = String(raw, Charsets.UTF_8)
        assertFalse(text.contains(packet.words))
        assertFalse(text.contains("trafaret"))
        assertFalse(text.contains(String(inner, 0, inner.indexOf(10), Charsets.US_ASCII).split(' ')[4]))
        // Повтор того же пакета — другие байты: новый одноразовый ключ.
        assertFalse(raw.contentEquals(GateBox.close(inner, myBox.publicKey)))
    }

    @Test
    fun `одноразовый ключ вне кривой — отказ, согласования не было`() {
        val k = (GateWire.readKonvert(GateBox.close(signedPacket(), myBox.publicKey)) as GateWire.KonvertRead.Ok).konvert
        val bad = GateWire.writeKonvert(GateBox.Konvert(k.key, offCurve(k.enc), k.box))
        val counting = Counting(myBox)
        val v = intake(counting).receiveKonvert(bad, agentB, now) as GateIntake.KonvertVerdict.Refused
        assertEquals(GateRefusal.CANNOT_OPEN, v.refusal)
        assertEquals(0, counting.calls)
    }

    @Test
    fun `закрыто чужим ключом — отказ, без согласования`() {
        val other = GateKeyPairOpener(GateBox.newPair())
        val counting = Counting(myBox)
        val v = intake(counting).receiveKonvert(GateBox.close(signedPacket(), other.publicKey), agentB, now) as GateIntake.KonvertVerdict.Refused
        assertEquals(GateRefusal.CANNOT_OPEN, v.refusal)
        assertTrue(v.detail, v.detail.contains("не моим ключом"))
        assertEquals(0, counting.calls)
    }

    @Test
    fun `испорченный байт закрытого — отказ`() {
        val k = (GateWire.readKonvert(GateBox.close(signedPacket(), myBox.publicKey)) as GateWire.KonvertRead.Ok).konvert
        val box = k.box.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        val v = intake().receiveKonvert(GateWire.writeKonvert(GateBox.Konvert(k.key, k.enc, box)), agentB, now) as GateIntake.KonvertVerdict.Refused
        assertEquals(GateRefusal.CANNOT_OPEN, v.refusal)
    }

    @Test
    fun `без своего ключа шифрования конверт не открыть`() {
        val v = intake(opener = null).receiveKonvert(GateBox.close(signedPacket(), myBox.publicKey), agentB, now) as GateIntake.KonvertVerdict.Refused
        assertEquals(GateRefusal.CANNOT_OPEN, v.refusal)
    }

    @Test
    fun `расшифровано — не значит от него, пакет другого агента в конверте от канала Б — отказ`() {
        val agentC = PersonKey("tg:user:555")
        val fromC = signedPacket(packet.copy(from = agentC), TestSigner())
        val v = intake().receiveKonvert(GateBox.close(fromC, myBox.publicKey), agentB, now) as GateIntake.KonvertVerdict.Packet
        assertEquals(GateRefusal.NOT_SENDER, (v.verdict as GateIntake.Verdict.Refused).refusal)
    }

    @Test
    fun `шифрование не заменяет подпись, неподписанный пакет знакомого агента в конверте — отказ`() {
        val v = intake().receiveKonvert(GateBox.close(GateWire.write(packet), myBox.publicKey), agentB, now) as GateIntake.KonvertVerdict.Packet
        assertEquals(GateRefusal.UNSIGNED, (v.verdict as GateIntake.Verdict.Refused).refusal)
    }

    @Test
    fun `карточка и конверт в конверт не кладутся, а подложенные — отказ`() {
        val card = GateSign.seal(GateWire.writeCard(SignFixtures.card(signerB)), signerB)
        assertTrue(runCatching { GateBox.close(card, myBox.publicKey) }.isFailure)
        val konvert = GateBox.close(signedPacket(), myBox.publicKey)
        assertTrue(runCatching { GateBox.close(konvert, myBox.publicKey) }.isFailure)
        for (inner in listOf(card, konvert)) {
            val pkR = GateSign.decodeKey(myBox.publicKey)!!
            val (enc, box) = GateBox.seal(pkR, GateBox.INFO, ByteArray(0), inner, GateBox.newPair())
            val raw = GateWire.writeKonvert(GateBox.Konvert(myBox.publicKey, GateSign.b64(enc), box))
            val v = intake().receiveKonvert(raw, agentB, now) as GateIntake.KonvertVerdict.Refused
            assertEquals(GateRefusal.MALFORMED, v.refusal)
        }
    }

    @Test
    fun `конверт засчитывается в поток один раз — форма внутри второй раз не считается`() {
        val limits = GateIntake.Limits.DECLARED.copy(perWindow = 2)
        val i = intake(limits = limits)
        repeat(2) { n ->
            val raw = GateBox.close(signedPacket(packet.copy(number = 100L + n)), myBox.publicKey)
            val v = i.receiveKonvert(raw, agentB, now) as GateIntake.KonvertVerdict.Packet
            assertTrue(v.verdict.toString(), v.verdict is GateIntake.Verdict.Accepted)
        }
        val third = i.receiveKonvert(GateBox.close(signedPacket(packet.copy(number = 200L)), myBox.publicKey), agentB, now)
        assertEquals(GateRefusal.FLOOD, (third as GateIntake.KonvertVerdict.Refused).refusal)
    }

    @Test
    fun `внутри больше предела формы — отказ, хоть сам конверт и в пределе`() {
        val limits = GateIntake.Limits.DECLARED.copy(maxBytes = 200)
        val v = intake(limits = limits).receiveKonvert(GateBox.close(signedPacket(), myBox.publicKey), agentB, now) as GateIntake.KonvertVerdict.Refused
        assertEquals(GateRefusal.TOO_BIG, v.refusal)
    }

    @Test
    fun `конверт больше своего предела — отказ до разбора`() {
        val raw = GateBox.close(signedPacket(), myBox.publicKey)
        val limits = GateIntake.Limits.DECLARED.copy(maxKonvertBytes = raw.size - 1)
        val counting = Counting(myBox)
        val v = intake(counting, limits).receiveKonvert(raw, agentB, now) as GateIntake.KonvertVerdict.Refused
        assertEquals(GateRefusal.TOO_BIG, v.refusal)
        assertEquals(0, counting.calls)
    }

    @Test
    fun `закрытые поручительство и принял открываются в свои формы`() {
        val vouch = GateSign.seal(GateWire.writeVouch(GateVouch(agentB, PersonKey("tg:user:333"), me, false)), signerB)
        val vv = intake().receiveKonvert(GateBox.close(vouch, myBox.publicKey), agentB, now) as GateIntake.KonvertVerdict.Vouch
        assertTrue(vv.verdict is GateIntake.VouchVerdict.Accepted)
        val ack = GateSign.seal(GateWire.writeAck(GateAck(agentB, me, 42L, GateAck.Result.ACCEPTED, now, now)), signerB)
        val av = intake().receiveKonvert(GateBox.close(ack, myBox.publicKey), agentB, now) as GateIntake.KonvertVerdict.Ack
        assertTrue(av.verdict is GateIntake.AckVerdict.Accepted)
    }

    // --- Карточка с ключом шифрования ---

    private fun cardWithEnc(enc: String = myBox.publicKey, signer: GateSigner = signerB) =
        GateCard(agentB, linkedMapOf(GateCard.Field.NAME to "Хоуп", GateCard.Field.KEY to signer.publicKey, GateCard.Field.ENC to enc))

    private fun withVersion(raw: ByteArray, v: Int): ByteArray {
        val nl = raw.indexOf(10)
        val parts = String(raw, 0, nl, Charsets.US_ASCII).split(' ').toMutableList()
        parts[1] = v.toString()
        return (parts.joinToString(" ") + "\n").toByteArray(Charsets.US_ASCII) + raw.copyOfRange(nl + 1, raw.size)
    }

    @Test
    fun `карточка с ключом шифрования подписывается версией 3 и читается`() {
        val raw = GateSign.seal(GateWire.writeCard(cardWithEnc()), signerB)
        assertTrue(String(raw, Charsets.UTF_8).startsWith("kartochka 3 "))
        val r = GateWire.readCard(raw) as GateWire.CardRead.Ok
        assertEquals(myBox.publicKey, r.card.fields[GateCard.Field.ENC])
        assertTrue(GateSign.verify(signerB.publicKey, r.seal!!.data, r.seal!!.sig))
        // Без ключа шифрования — по-прежнему версия 2.
        assertTrue(String(GateSign.seal(GateWire.writeCard(SignFixtures.card(signerB)), signerB), Charsets.UTF_8).startsWith("kartochka 2 "))
    }

    @Test
    fun `ключ шифрования не в версии 3, версия 3 без ключа, ключ вне кривой, ключ тот же, что подписи — неверная форма`() {
        val v3 = GateSign.seal(GateWire.writeCard(cardWithEnc()), signerB)
        val v2 = GateSign.seal(GateWire.writeCard(SignFixtures.card(signerB)), signerB)
        val bad = listOf(
            withVersion(v3, GateWire.CARD_SIGNED_VERSION),
            withVersion(v2, GateWire.CARD_ENC_VERSION),
            GateSign.seal(GateWire.writeCard(cardWithEnc(offCurve(myBox.publicKey))), signerB),
            GateSign.seal(GateWire.writeCard(cardWithEnc(signerB.publicKey)), signerB),
        )
        for (raw in bad) {
            val r = GateWire.readCard(raw)
            assertTrue(r.toString(), r is GateWire.CardRead.Fail && r.refusal == GateRefusal.MALFORMED)
        }
    }

    @Test
    fun `новый ключ шифрования в подписанной карточке — без тревоги, обычное изменение карточки`() {
        fun take(records: List<GateKeyRecord>, card: GateCard, at: Long): GateKeys.Taken {
            val seal = (GateWire.readCard(GateSign.seal(GateWire.writeCard(card), signerB)) as GateWire.CardRead.Ok).seal
            return GateKeys.takeCard(records, card, seal, at)
        }
        val first = take(emptyList(), cardWithEnc(), now)
        val newEnc = GateKeyPairOpener(GateBox.newPair()).publicKey
        val second = take(first.records, cardWithEnc(newEnc), now + 1)
        assertTrue(second.change is GateKeys.Change.Same)
        assertTrue(second.cardTaken)
        val readings = GateCards.take(GateCards.take(emptyList(), cardWithEnc(), now).readings, cardWithEnc(newEnc), now + 1)
        assertEquals(newEnc, GateBox.encKeyOf(readings.readings, agentB))
        assertNull(GateBox.encKeyOf(readings.readings, me))
    }

    // --- Строки прибора ---

    @Test
    fun `строки прибора — что ушло открытым и что пришло как`() {
        assertNull(GateBox.sentLine("Катя", closed = true))
        assertEquals("Шифрование: Катя — ключ не известен, отправлено открытым", GateBox.sentLine("Катя", closed = false))
        assertEquals("Шифрование: Катя — пришло закрытым, расшифровано", GateBox.receivedLine("Катя", closed = true, keyKnown = true))
        assertEquals("Шифрование: Катя — пришло открытым, хотя ключ шифрования известен", GateBox.receivedLine("Катя", closed = false, keyKnown = true))
        assertNull(GateBox.receivedLine("Катя", closed = false, keyKnown = false))
    }
}
