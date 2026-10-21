package com.uroboros.gate

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.zip.CRC32

/** Ключ подписи для тестов — обычный ключ Java на той же кривой, что и на телефоне. */
class TestSigner : GateSigner {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val publicKey: String = GateSign.encodeKey(pair.public as ECPublicKey)
    override fun sign(data: ByteArray): ByteArray = GateSign.fromDer(
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        },
    )
}

/** Тестовые пакеты врат — общие для тестов подписи. */
internal object SignFixtures {
    val me = PersonKey("tg:user:789")
    val agentB = PersonKey("tg:user:123")
    val humanB = PersonKey("tg:user:456")
    const val now = 1_760_947_200_000L

    val packet = GatePacket(
        from = agentB, represents = humanB, to = me, number = 42L, sentMs = now,
        place = Place.DIRECT, saidBy = humanB, about = listOf(humanB), aboutUnclear = false, atMs = now,
        zone = "Europe/Moscow", source = SourceKind.USER_STATED, kind = SentenceKind.Kind.STATEMENT,
        topic = emptyList(), exact = emptyMap(), words = "в субботу я занят",
    )

    fun card(signer: GateSigner, name: String = "Хоуп") =
        GateCard(agentB, linkedMapOf(GateCard.Field.NAME to name, GateCard.Field.KEY to signer.publicKey))
}

class GateSignTest {

    private val signer = TestSigner()
    private val other = TestSigner()
    private val packet = SignFixtures.packet

    private fun header(raw: ByteArray) = String(raw, 0, raw.indexOf('\n'.code.toByte()), Charsets.US_ASCII).split(' ')

    // --- Ключ и подпись ---

    @Test
    fun `ключ — 87 знаков base64url, читается обратно`() {
        assertEquals(GateSign.KEY_CHARS, signer.publicKey.length)
        val k = GateSign.decodeKey(signer.publicKey)
        assertNotNull(k)
        assertEquals(signer.publicKey, GateSign.encodeKey(k!!))
    }

    @Test
    fun `не ключ — не читается`() {
        for (s in listOf("", "abc", signer.publicKey.dropLast(1), "A" + signer.publicKey.drop(1), signer.publicKey.replace('A', '+'))) {
            assertNull(s, GateSign.decodeKey(s))
        }
    }

    @Test
    fun `подпись верна своим ключом и не верна чужим`() {
        val data = "данные".toByteArray()
        val sig = GateSign.b64(signer.sign(data))
        assertEquals(GateSign.SIG_CHARS, sig.length)
        assertTrue(GateSign.verify(signer.publicKey, data, sig))
        assertTrue(!GateSign.verify(other.publicKey, data, sig))
        assertTrue(!GateSign.verify(signer.publicKey, "другое".toByteArray(), sig))
    }

    @Test
    fun `DER туда и обратно — те же 64 байта`() {
        repeat(20) {
            val raw = signer.sign("п$it".toByteArray())
            assertArrayEquals(raw, GateSign.fromDer(GateSign.toDer(raw)))
        }
    }

    // --- Подпись на проводе ---

    @Test
    fun `подписанный пакет — версия 3, пятое слово шапки, тело то же`() {
        val plain = GateWire.write(packet)
        val sealed = GateSign.seal(plain, signer)
        val h = header(sealed)
        assertEquals(listOf("trafaret", "${GateWire.SIGNED_VERSION}"), h.take(2))
        assertEquals(5, h.size)
        assertArrayEquals(plain.copyOfRange(plain.indexOf('\n'.code.toByte()), plain.size), sealed.copyOfRange(sealed.indexOf('\n'.code.toByte()), sealed.size))
        val r = GateWire.read(sealed) as GateWire.Read.Ok
        assertEquals(packet, r.packet)
        assertTrue(GateSign.verify(signer.publicKey, r.seal!!.data, r.seal!!.sig))
        assertNull(r.seal!!.prevSig)
    }

    @Test
    fun `подписана шапка с формой и версией, а не одно тело`() {
        val r = GateWire.read(GateSign.seal(GateWire.write(packet), signer)) as GateWire.Read.Ok
        val signed = String(r.seal!!.data, Charsets.UTF_8)
        assertTrue(signed, signed.startsWith("trafaret ${GateWire.SIGNED_VERSION} "))
        assertTrue(signed.substringBefore('\n').split(' ').size == 4)
    }

    @Test
    fun `неподписанный пакет — без подписи`() {
        assertNull((GateWire.read(GateWire.write(packet)) as GateWire.Read.Ok).seal)
    }

    @Test
    fun `тело подменено с верной суммой — подпись не сходится`() {
        val sealed = String(GateSign.seal(GateWire.write(packet), signer), Charsets.UTF_8)
        val h = sealed.substringBefore('\n').split(' ')
        val body = sealed.substringAfter('\n').replace("занят", "свободен").toByteArray()
        val crc = String.format("%08x", CRC32().also { it.update(body) }.value)
        val forged = "${h[0]} ${h[1]} ${body.size} $crc ${h[4]}\n".toByteArray() + body
        val r = GateWire.read(forged) as GateWire.Read.Ok
        assertTrue(!GateSign.verify(signer.publicKey, r.seal!!.data, r.seal!!.sig))
    }

    @Test
    fun `подпись от карточки, приставленная к пакету, не сходится`() {
        val cardSig = header(GateSign.seal(GateWire.writeCard(SignFixtures.card(signer)), signer))[4]
        val plain = String(GateWire.write(packet), Charsets.UTF_8)
        val h = plain.substringBefore('\n').split(' ')
        val forged = "${h[0]} ${GateWire.SIGNED_VERSION} ${h[2]} ${h[3]} $cardSig\n${plain.substringAfter('\n')}".toByteArray()
        val r = GateWire.read(forged) as GateWire.Read.Ok
        assertTrue(!GateSign.verify(signer.publicKey, r.seal!!.data, r.seal!!.sig))
    }

    @Test
    fun `шапка не того числа слов для версии — не форма`() {
        val plain = String(GateWire.write(packet), Charsets.UTF_8)
        val h = plain.substringBefore('\n').split(' ')
        val body = plain.substringAfter('\n')
        val sig = GateSign.b64(signer.sign(byteArrayOf(1)))
        val signedNoSig = "${h[0]} ${GateWire.SIGNED_VERSION} ${h[2]} ${h[3]}\n$body".toByteArray()
        val plainWithSig = "${h[0]} ${GateWire.VERSION} ${h[2]} ${h[3]} $sig\n$body".toByteArray()
        val sixWords = "${h[0]} ${GateWire.SIGNED_VERSION} ${h[2]} ${h[3]} $sig $sig\n$body".toByteArray()
        val shortSig = "${h[0]} ${GateWire.SIGNED_VERSION} ${h[2]} ${h[3]} ${sig.dropLast(2)}\n$body".toByteArray()
        for (raw in listOf(signedNoSig, plainWithSig, sixWords, shortSig)) {
            assertEquals(String(raw), GateRefusal.NOT_A_PACKET, (GateWire.read(raw) as GateWire.Read.Fail).refusal)
        }
    }

    @Test
    fun `карточка — подпись прежним ключом шестым словом, и только у карточки`() {
        val sealed = GateSign.seal(GateWire.writeCard(SignFixtures.card(signer)), signer, previous = other)
        val r = GateWire.readCard(sealed) as GateWire.CardRead.Ok
        assertTrue(GateSign.verify(signer.publicKey, r.seal!!.data, r.seal!!.sig))
        assertTrue(GateSign.verify(other.publicKey, r.seal!!.data, r.seal!!.prevSig!!))
        val failed = runCatching { GateSign.seal(GateWire.write(packet), signer, previous = other) }
        assertTrue(failed.isFailure)
    }

    @Test
    fun `подписать уже подписанное нельзя`() {
        assertTrue(runCatching { GateSign.seal(GateSign.seal(GateWire.write(packet), signer), signer) }.isFailure)
    }

    @Test
    fun `карточка с ключом без подписи и подписанная без ключа — неверная форма`() {
        val unsignedWithKey = GateWire.writeCard(SignFixtures.card(signer))
        assertEquals(GateRefusal.MALFORMED, (GateWire.readCard(unsignedWithKey) as GateWire.CardRead.Fail).refusal)
        val signedNoKey = GateSign.seal(GateWire.writeCard(GateCard(SignFixtures.agentB, mapOf(GateCard.Field.NAME to "Хоуп"))), signer)
        assertEquals(GateRefusal.MALFORMED, (GateWire.readCard(signedNoKey) as GateWire.CardRead.Fail).refusal)
    }

    @Test
    fun `все четыре формы подписываются и читаются`() {
        val ack = GateAck(SignFixtures.me, SignFixtures.agentB, 42L, GateAck.Result.ACCEPTED, SignFixtures.now, SignFixtures.now)
        val vouch = GateVouch(SignFixtures.agentB, PersonKey("tg:user:333"), SignFixtures.me, false)
        assertNotNull((GateWire.readAck(GateSign.seal(GateWire.writeAck(ack), signer)) as GateWire.AckRead.Ok).seal)
        assertNotNull((GateWire.readVouch(GateSign.seal(GateWire.writeVouch(vouch), signer)) as GateWire.VouchRead.Ok).seal)
        assertNotNull((GateWire.readCard(GateSign.seal(GateWire.writeCard(SignFixtures.card(signer)), signer)) as GateWire.CardRead.Ok).seal)
    }
}
