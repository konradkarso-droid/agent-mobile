package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Примеры из описания протокола (`docs/protokol/primery/`) читаются вратами и
 * пишутся обратно байт в байт. Разойдётся код с примерами — упадёт здесь, а не
 * у автора чужого агента, который делал по описанию.
 */
class GateProtocolExamplesTest {

    /** Тесты идут из папки модуля `app/`. */
    private fun example(name: String): ByteArray {
        val f = File("../docs/protokol/primery/$name")
        assertTrue("нет примера ${f.absolutePath}", f.isFile)
        val raw = f.readBytes()
        assertTrue("$name кончается переводом строки — тело длиннее объявленного", raw.last() != '\n'.code.toByte())
        return raw
    }

    private val agentB = PersonKey("tg:user:123")
    private val me = PersonKey("tg:user:789")

    @Test
    fun `пакет слов из примера читается и пишется обратно тем же`() {
        val raw = example("paket.txt")
        assertEquals(GateWire.Form.PACKET, GateWire.formOf(raw))
        val p = (GateWire.read(raw) as GateWire.Read.Ok).packet
        assertEquals(agentB, p.from)
        assertEquals("123456789abcdef0", GateWire.numberHex(p.number))
        assertArrayEquals(raw, GateWire.write(p))
        // Прошёл бы врата получателя в тот же момент.
        val v = GateIntake(me).receive(raw, agentB, p.sentMs)
        assertTrue(v.toString(), v is GateIntake.Verdict.Accepted)
    }

    @Test
    fun `карточка из примера читается и пишется обратно тем же`() {
        val raw = example("kartochka.txt")
        assertEquals(GateWire.Form.CARD, GateWire.formOf(raw))
        val c = (GateWire.readCard(raw) as GateWire.CardRead.Ok).card
        assertEquals(agentB, c.agent)
        assertEquals("Хоуп", c.fields[GateCard.Field.NAME])
        assertArrayEquals(raw, GateWire.writeCard(c))
    }

    @Test
    fun `поручительство из примера читается и пишется обратно тем же`() {
        val raw = example("poruka.txt")
        assertEquals(GateWire.Form.VOUCH, GateWire.formOf(raw))
        val v = (GateWire.readVouch(raw) as GateWire.VouchRead.Ok).vouch
        assertEquals(GateVouch(agentB, PersonKey("tg:user:333"), me, false), v)
        assertArrayEquals(raw, GateWire.writeVouch(v))
    }

    @Test
    fun `принял из примера отвечает на пакет из примера`() {
        val raw = example("prinyal.txt")
        assertEquals(GateWire.Form.ACK, GateWire.formOf(raw))
        val a = (GateWire.readAck(raw) as GateWire.AckRead.Ok).ack
        val p = (GateWire.read(example("paket.txt")) as GateWire.Read.Ok).packet
        assertEquals(p.number, a.number)
        assertEquals(p.to, a.from)
        assertEquals(p.from, a.to)
        assertEquals(GateAck.Result.ACCEPTED, a.result)
        assertArrayEquals(raw, GateWire.writeAck(a))
    }

    @Test
    fun `подписанная карточка из примера подписана своим ключом, пакет — ключом из неё`() {
        val cardRaw = example("podpis_kartochka.txt")
        val card = GateWire.readCard(cardRaw) as GateWire.CardRead.Ok
        val key = card.card.fields.getValue(GateCard.Field.KEY)
        assertTrue(GateSign.verify(key, card.seal!!.data, card.seal!!.sig))
        val packet = GateWire.read(example("podpis_paket.txt")) as GateWire.Read.Ok
        assertTrue(GateSign.verify(key, packet.seal!!.data, packet.seal!!.sig))
        // Тело подписанного пакета — то же, что у неподписанного примера.
        val plain = example("paket.txt")
        val signed = example("podpis_paket.txt")
        assertArrayEquals(plain.copyOfRange(plain.indexOf(10), plain.size), signed.copyOfRange(signed.indexOf(10), signed.size))
        // Врата незнакомому агенту: карточка — первая встреча, пакет — подпись верна.
        val keys = GateKeys.takeCard(emptyList(), card.card, card.seal, packet.packet.sentMs).records
        val v = GateIntake(me, keys = { keys }).receive(signed, agentB, packet.packet.sentMs) as GateIntake.Verdict.Accepted
        assertTrue(v.sign is GateKeys.Check.Verified)
    }

    /**
     * Закрытый ключ шифрования получателя из примера — открыт в описании
     * (§ 9) для проверки, как ключи контрольных примеров RFC. Не чей-то ключ.
     */
    private val primerSk = "335473fea2c30717ada3b00d432fa3a438b1c83e938ae0037eb5958c2ea9ac3c"

    @Test
    fun `карточка получателя из примера — версия 3, подписана своим ключом, с ключом шифрования`() {
        val raw = example("shifr_kartochka.txt")
        val c = GateWire.readCard(raw) as GateWire.CardRead.Ok
        assertEquals(me, c.card.agent)
        assertTrue(String(raw, Charsets.UTF_8).startsWith("kartochka ${GateWire.CARD_ENC_VERSION} "))
        assertTrue(GateSign.verify(c.card.fields.getValue(GateCard.Field.KEY), c.seal!!.data, c.seal!!.sig))
        assertNotNull(GateSign.decodeKey(c.card.fields.getValue(GateCard.Field.ENC)))
    }

    @Test
    fun `конверт из примера открывается ключом из описания, внутри — подписанный пакет из примера`() {
        val enc = (GateWire.readCard(example("shifr_kartochka.txt")) as GateWire.CardRead.Ok).card.fields.getValue(GateCard.Field.ENC)
        val sk = primerSk.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val opener = GateKeyPairOpener(java.security.KeyPair(GateSign.decodeKey(enc)!!, GateBox.privateKey(sk)))
        assertEquals(enc, opener.publicKey)
        val raw = example("konvert.txt")
        assertEquals(GateWire.Form.KONVERT, GateWire.formOf(raw))
        val k = (GateWire.readKonvert(raw) as GateWire.KonvertRead.Ok).konvert
        assertArrayEquals(example("podpis_paket.txt"), GateBox.open(opener, k))
        assertArrayEquals(raw, GateWire.writeKonvert(k))
        // Врата получателя: ключ подписи Б — из его карточки, пакет внутри — подпись верна.
        val card = GateWire.readCard(example("podpis_kartochka.txt")) as GateWire.CardRead.Ok
        val p = (GateWire.read(example("podpis_paket.txt")) as GateWire.Read.Ok).packet
        val keys = GateKeys.takeCard(emptyList(), card.card, card.seal, p.sentMs).records
        val v = GateIntake(me, keys = { keys }, opener = opener).receiveKonvert(raw, agentB, p.sentMs) as GateIntake.KonvertVerdict.Packet
        assertTrue((v.verdict as GateIntake.Verdict.Accepted).sign is GateKeys.Check.Verified)
    }

    @Test
    fun `версии в описании — те же, что в коде, в обеих версиях описания`() {
        for (name in listOf("README.md", "README.en.md")) for ((magic, version) in listOf(
            "trafaret" to GateWire.VERSION,
            "kartochka" to GateWire.CARD_VERSION,
            "poruka" to GateWire.VOUCH_VERSION,
            "prinyal" to GateWire.ACK_VERSION,
            "trafaret" to GateWire.SIGNED_VERSION,
            "kartochka" to GateWire.CARD_SIGNED_VERSION,
            "poruka" to GateWire.VOUCH_SIGNED_VERSION,
            "prinyal" to GateWire.ACK_SIGNED_VERSION,
            "kartochka" to GateWire.CARD_ENC_VERSION,
            "konvert" to GateWire.KONVERT_VERSION,
        )) {
            assertTrue("в $name нет «$magic $version»", File("../docs/protokol/$name").readText().contains("`$magic $version`"))
        }
    }
}
