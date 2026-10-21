package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun `версии в описании — те же, что в коде`() {
        val doc = File("../docs/protokol/README.md").readText()
        for ((magic, version) in listOf(
            "trafaret" to GateWire.VERSION,
            "kartochka" to GateWire.CARD_VERSION,
            "poruka" to GateWire.VOUCH_VERSION,
            "prinyal" to GateWire.ACK_VERSION,
        )) {
            assertTrue("в описании нет «$magic $version»", doc.contains("`$magic $version`"))
        }
    }
}
