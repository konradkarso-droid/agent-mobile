package com.uroboros.gate

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.zip.CRC32

class GateVouchTest {

    private val me = PersonKey("tg:agent:789")
    private val katya = PersonKey("tg:agent:555") // агент Кати, знаком моему владельцу
    private val petya = PersonKey("tg:agent:666") // агент Пети, знаком моему владельцу
    private val stranger = PersonKey("tg:agent:444")
    private val newcomer = PersonKey("tg:agent:333") // за него ручаются
    private val humanB = PersonKey("tg:user:456")
    private val now = 1_760_947_200_000L // 20.10.2025 08:00 UTC
    private val day = 24 * 60 * 60 * 1000L
    private val moscow = ZoneId.of("Europe/Moscow")

    private fun knows(k: PersonKey, since: Long = now - 8 * day, revoked: Long? = null) = GateAcquaintance(k, since, revoked)
    private val known = listOf(knows(katya), knows(petya))

    private fun vouch(by: PersonKey, forAgent: PersonKey = newcomer, withdraw: Boolean = false) = GateVouch(by, forAgent, me, withdraw)
    private fun rec(by: PersonKey, at: Long = now - day, forAgent: PersonKey = newcomer, withdraw: Boolean = false) =
        GateVouches.Record(vouch(by, forAgent, withdraw), at)

    private val names: (PersonKey) -> String? = {
        when (it) {
            katya -> "агент Кати"
            petya -> "агент Пети"
            else -> null
        }
    }

    private fun line(r: GateVouches.Result) = GateVouches.meterLine(r, names, moscow)

    private fun wrap(body: String): ByteArray {
        val b = body.toByteArray(Charsets.UTF_8)
        val c = CRC32().also { it.update(b) }
        return "poruka ${GateWire.VOUCH_VERSION} ${b.size} ${String.format("%08x", c.value)}\n".toByteArray() + b
    }

    // --- Провод и врата ---

    @Test
    fun `поручительство пишется и читается как было`() {
        val v = vouch(katya, withdraw = true)
        assertEquals(GateWire.VouchRead.Ok(v), GateWire.readVouch(GateWire.writeVouch(v)))
    }

    @Test
    fun `карточка и пакет слов поручительством не читаются`() {
        val card = GateWire.writeCard(GateCard(katya, emptyMap()))
        assertEquals(GateRefusal.NOT_A_VOUCH, (GateWire.readVouch(card) as GateWire.VouchRead.Fail).refusal)
        val r = GateWire.readCard(GateWire.writeVouch(vouch(katya)))
        assertTrue(r is GateWire.CardRead.Fail && r.refusal == GateRefusal.NOT_A_CARD)
    }

    @Test
    fun `веса в поручительстве нет — лишнее поле отказ`() {
        val body = """{"voucher":"tg:agent:555","for":"tg:agent:333","to":"tg:agent:789","withdraw":false,"weight":120}"""
        assertEquals(GateRefusal.UNKNOWN_FIELD, (GateWire.readVouch(wrap(body)) as GateWire.VouchRead.Fail).refusal)
    }

    @Test
    fun `честное поручительство принято`() {
        val v = vouch(katya)
        assertEquals(GateIntake.VouchVerdict.Accepted(v), GateIntake(me).receiveVouch(GateWire.writeVouch(v), katya, now))
    }

    @Test
    fun `поручитель не тот, что у канала — не принято`() {
        val v = GateIntake(me).receiveVouch(GateWire.writeVouch(vouch(katya)), stranger, now)
        assertTrue(v is GateIntake.VouchVerdict.Refused && v.refusal == GateRefusal.NOT_SENDER)
        assertTrue((v as GateIntake.VouchVerdict.Refused).words.startsWith("Поручительство: не принято — отправитель не тот"))
    }

    @Test
    fun `поручительство не мне — не принято`() {
        val v = GateIntake(me).receiveVouch(GateWire.writeVouch(vouch(katya).copy(to = petya)), katya, now)
        assertTrue(v is GateIntake.VouchVerdict.Refused && v.refusal == GateRefusal.NOT_FOR_ME)
    }

    @Test
    fun `поручительства и карточки одного ключа — один счёт потока`() {
        val a = GateIntake(me)
        repeat(GateIntake.Limits.DECLARED.perWindow) {
            assertTrue(a.receiveCard(GateWire.writeCard(GateCard(katya, emptyMap())), katya, now) is GateIntake.CardVerdict.Accepted)
        }
        val v = a.receiveVouch(GateWire.writeVouch(vouch(katya)), katya, now)
        assertTrue("$v", v is GateIntake.VouchVerdict.Refused && v.refusal == GateRefusal.FLOOD)
    }

    // --- Счёт ---

    @Test
    fun `двое знакомых — достаточно`() {
        val r = GateVouches.of(newcomer, listOf(rec(katya), rec(petya)), known, emptyList(), now)
        assertTrue(r.enough)
        assertEquals(
            "Поручительство: tg:agent:333 — знакомых поручителей 2, нужно 2 — достаточно: " +
                "агент Кати (вы подтвердили 12.10.2025), агент Пети (вы подтвердили 12.10.2025)",
            line(r),
        )
    }

    @Test
    fun `один знакомый — мало`() {
        val r = GateVouches.of(newcomer, listOf(rec(katya)), known, emptyList(), now)
        assertFalse(r.enough)
        assertEquals(
            "Поручительство: tg:agent:333 — знакомых поручителей 1, нужно 2 — мало: агент Кати (вы подтвердили 12.10.2025)",
            line(r),
        )
    }

    @Test
    fun `незнакомый поручитель не в счёт, но показан`() {
        val r = GateVouches.of(newcomer, listOf(rec(katya), rec(stranger)), known, emptyList(), now)
        assertEquals(1, r.counted.size)
        assertEquals(
            "Поручительство: tg:agent:333 — знакомых поручителей 1, нужно 2 — мало: агент Кати (вы подтвердили 12.10.2025); " +
                "не в счёт: tg:agent:444 — его вы не подтверждали",
            line(r),
        )
    }

    @Test
    fun `сто незнакомых ручаются друг за друга — ни один не в счёт`() {
        val fakes = (1..100).map { PersonKey("tg:bot:$it") }
        val records = fakes.flatMap { a -> fakes.filter { it != a }.map { b -> rec(a, forAgent = b) } }
        for (f in fakes) {
            val r = GateVouches.of(f, records, known, emptyList(), now)
            assertEquals(0, r.counted.size)
            assertEquals(99, r.notCounted.size)
        }
    }

    @Test
    fun `цепочка в один шаг — за кого поручились знакомые, тот сам не поручитель`() {
        val records = listOf(rec(katya, forAgent = stranger), rec(petya, forAgent = stranger), rec(stranger))
        assertTrue(GateVouches.of(stranger, records, known, emptyList(), now).enough)
        val r = GateVouches.of(newcomer, records, known, emptyList(), now)
        assertEquals(emptyList<GateVouches.Counted>(), r.counted)
        assertEquals(GateVouches.Why.UNKNOWN, r.notCounted.single().why)
    }

    @Test
    fun `знакомство с поручителем снято — не в счёт, с датой`() {
        val acq = listOf(knows(katya, revoked = now - 2 * day), knows(petya))
        val r = GateVouches.of(newcomer, listOf(rec(katya), rec(petya)), acq, emptyList(), now)
        assertFalse(r.enough)
        assertTrue(line(r), line(r).endsWith("не в счёт: агент Кати — знакомство с ним вы сняли 18.10.2025"))
    }

    @Test
    fun `знакомство снято с поручителя, а сам агент знаком напрямую — по-прежнему знаком`() {
        val acq = listOf(knows(katya, revoked = now - 2 * day), knows(newcomer, since = now - 3 * day))
        val r = GateVouches.of(newcomer, listOf(rec(katya)), acq, emptyList(), now)
        assertEquals(now - 3 * day, r.directSince)
        assertTrue(line(r), line(r).startsWith("Поручительство: tg:agent:333 — знаком вам напрямую, с 17.10.2025; знакомых поручителей 0"))
    }

    @Test
    fun `знакомство ждёт подтверждения — не в счёт`() {
        val acq = listOf(GateAcquaintance(katya, null, null))
        val r = GateVouches.of(newcomer, listOf(rec(katya)), acq, emptyList(), now)
        assertEquals(GateVouches.Why.PENDING, r.notCounted.single().why)
    }

    @Test
    fun `знакомство с будущего момента ещё не действует`() {
        val r = GateVouches.of(newcomer, listOf(rec(katya)), listOf(knows(katya, since = now + day)), emptyList(), now)
        assertEquals(0, r.counted.size)
    }

    @Test
    fun `поручительство снято — не показано, снова дано — в счёт`() {
        val withdrawn = listOf(rec(katya, at = now - 2 * day), rec(katya, at = now - day, withdraw = true), rec(petya))
        val r = GateVouches.of(newcomer, withdrawn, known, emptyList(), now)
        assertEquals(listOf(petya), r.counted.map { it.voucher })
        assertTrue(r.notCounted.isEmpty())

        val again = withdrawn + rec(katya, at = now - 1000)
        assertTrue(GateVouches.of(newcomer, again, known, emptyList(), now).enough)
    }

    @Test
    fun `поручитель с действующей доверенностью знаком, с истёкшей — нет`() {
        val proxy = GateProxy(katya, humanB, GateProxy.Kind.PERSON, now - 5 * day, null, null)
        assertEquals(1, GateVouches.of(newcomer, listOf(rec(katya)), emptyList(), listOf(proxy), now).counted.size)
        val expired = proxy.copy(untilMs = now - day)
        assertEquals(0, GateVouches.of(newcomer, listOf(rec(katya)), emptyList(), listOf(expired), now).counted.size)
    }

    @Test
    fun `сам за себя не ручается`() {
        val r = GateVouches.of(newcomer, listOf(rec(newcomer)), known, emptyList(), now)
        assertEquals(GateVouches.Why.SELF, r.notCounted.single().why)
    }

    @Test
    fun `никто не ручается — так и сказано`() {
        assertEquals("Поручительство: tg:agent:333 — никто не ручается", line(GateVouches.of(newcomer, emptyList(), known, emptyList(), now)))
        val r = GateVouches.of(katya, listOf(rec(petya, forAgent = petya)), known, emptyList(), now)
        assertNull(r.counted.firstOrNull())
        assertEquals("Поручительство: агент Кати — знаком вам напрямую, с 12.10.2025; никто не ручается", line(r))
    }

    @Test
    fun `поручительство и знакомство за человека не дают`() {
        val r = GateVouches.of(newcomer, listOf(rec(katya), rec(petya)), known + knows(newcomer), emptyList(), now)
        assertTrue(r.enough)
        val p = GatePacket(
            from = newcomer, represents = humanB, to = me, number = 1L, sentMs = now, place = Place.DIRECT,
            saidBy = humanB, about = listOf(humanB), aboutUnclear = false, atMs = now, zone = "Europe/Moscow",
            source = SourceKind.USER_STATED, kind = SentenceKind.Kind.STATEMENT, topic = emptyList(),
            exact = emptyMap(), words = "я в отпуске",
        )
        val who = GateWho.of(p, emptyList(), setOf(newcomer), now).who
        assertTrue("$who", who is GateWho.Who.Unconfirmed && who.why == GateWho.Why.NO_PROXY)
    }
}
