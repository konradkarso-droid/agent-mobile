package com.uroboros.gate

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * «Кто это»: агент Бориса пишет Антону. Доверенности — записи на входе.
 */
class GateWhoTest {

    private val agentB = PersonKey("tg:agent:123")
    private val boris = PersonKey("tg:user:456")
    private val katya = PersonKey("tg:user:777")
    private val other = PersonKey("tg:agent:222")
    private val chat = PersonKey("tg:chat:900")
    private val me = PersonKey("tg:agent:789")

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_760_947_200_000L
    private val zone = ZoneId.of("Europe/Moscow")

    private fun packet(
        from: PersonKey = agentB,
        represents: PersonKey = boris,
        saidBy: PersonKey = represents,
        atMs: Long = now,
        sentMs: Long = now,
    ) = GatePacket(
        from = from, represents = represents, to = me, number = 1L, sentMs = sentMs,
        place = Place.DIRECT, saidBy = saidBy,
        about = emptyList(), aboutUnclear = false, atMs = atMs, zone = "Europe/Moscow",
        source = SourceKind.USER_STATED, kind = SentenceKind.Kind.STATEMENT, topic = emptyList(),
        exact = emptyMap(), words = "я в отпуске",
    )

    private fun proxy(
        agent: PersonKey = agentB,
        principal: PersonKey = boris,
        kind: GateProxy.Kind = GateProxy.Kind.PERSON,
        confirmedMs: Long? = now - day,
        untilMs: Long? = null,
        revokedMs: Long? = null,
    ) = GateProxy(agent, principal, kind, confirmedMs, untilMs, revokedMs)

    private fun who(
        p: GatePacket = packet(),
        proxies: List<GateProxy>,
        known: Set<PersonKey> = setOf(agentB),
        at: Long = now,
    ) = GateWho.of(p, proxies, known, at)

    private fun why(p: GatePacket = packet(), proxies: List<GateProxy>, at: Long = now): GateWho.Why {
        val w = who(p, proxies, at = at).who
        assertTrue("ждали «не подтверждено», а: $w", w is GateWho.Who.Unconfirmed)
        return (w as GateWho.Who.Unconfirmed).why
    }

    private val names = mapOf(boris to "Борис", katya to "Катя", agentB to "агент Бориса")
    private fun line(r: GateWho.Result) = GateWho.meterLine(r, { names[it] }, zone)

    // --- Подтверждено ---

    @Test
    fun `действующая доверенность — за Бориса`() {
        val p = proxy()
        assertEquals(GateWho.Who.ForPerson(p), who(proxies = listOf(p)).who)
    }

    @Test
    fun `сам за себя — без доверенности`() {
        val r = who(packet(represents = agentB, saidBy = agentB), proxies = emptyList())
        assertEquals(GateWho.Who.Self, r.who)
        assertEquals("Врата: агент Бориса — агент сам за себя", line(r))
    }

    @Test
    fun `два подтверждённых агента у Бориса — оба за Бориса, тревоги нет`() {
        val proxies = listOf(proxy(), proxy(agent = other))
        assertTrue(who(proxies = proxies).who is GateWho.Who.ForPerson)
        assertTrue(who(packet(from = other), proxies).who is GateWho.Who.ForPerson)
    }

    @Test
    fun `за чат, сказал агент — за чат`() {
        val p = proxy(principal = chat, kind = GateProxy.Kind.CHAT)
        assertEquals(GateWho.Who.ForChat(p), who(packet(represents = chat, saidBy = agentB), listOf(p)).who)
    }

    // --- Молчит: не подтверждено, но пакет не отвергнут ---

    @Test
    fun `нет доверенности — не подтверждено, а не отказ`() {
        val r = who(proxies = emptyList())
        assertEquals(GateWho.Who.Unconfirmed(boris, GateWho.Why.NO_PROXY, null, emptyList()), r.who)
        assertEquals("Врата: за кого — не подтверждено: заявлено: Борис; доверенности нет", line(r))
    }

    @Test
    fun `владелец не подтвердил — ждёт подтверждения`() {
        assertEquals(GateWho.Why.PENDING, why(proxies = listOf(proxy(confirmedMs = null))))
    }

    @Test
    fun `отозвана — не подтверждено, и с моментом отзыва в строке`() {
        val p = proxy(revokedMs = now - 2 * 60_000L)
        assertEquals(GateWho.Why.REVOKED, why(proxies = listOf(p)))
        assertTrue(line(who(proxies = listOf(p))).contains("отозвана 20.10.2025"))
    }

    @Test
    fun `отзыв назначен на потом — пока действует`() {
        assertTrue(who(proxies = listOf(proxy(revokedMs = now + day))).who is GateWho.Who.ForPerson)
    }

    @Test
    fun `срок вышел — истекла, на миг раньше — действует`() {
        assertEquals(GateWho.Why.EXPIRED, why(proxies = listOf(proxy(untilMs = now))))
        assertTrue(who(proxies = listOf(proxy(untilMs = now + 1))).who is GateWho.Who.ForPerson)
    }

    @Test
    fun `срок не указан — действует до отзыва, хоть через десять лет`() {
        val p = proxy(confirmedMs = now - 3650 * day)
        assertTrue(who(proxies = listOf(p)).who is GateWho.Who.ForPerson)
        assertTrue(line(who(proxies = listOf(p))).contains("без срока, до отзыва"))
        assertEquals(GateWho.Why.REVOKED, why(proxies = listOf(p.copy(revokedMs = now - 1))))
    }

    @Test
    fun `подтверждена позже моего сейчас — ещё не действует`() {
        assertEquals(GateWho.Why.NOT_STARTED, why(proxies = listOf(proxy(confirmedMs = now + 60_000L))))
    }

    @Test
    fun `доверенность за Катю, говорит за Бориса — за другого`() {
        val r = who(proxies = listOf(proxy(principal = katya)))
        assertEquals(GateWho.Why.OTHER_PRINCIPAL, (r.who as GateWho.Who.Unconfirmed).why)
        assertTrue(line(r), line(r).contains("доверенность — на другого: Катя"))
    }

    @Test
    fun `несколько записей — причина по порядку, ожидание владельца раньше старого отзыва`() {
        val old = proxy(confirmedMs = now - 30 * day, revokedMs = now - 10 * day)
        val fresh = proxy(confirmedMs = null)
        assertEquals(GateWho.Why.PENDING, why(proxies = listOf(old, fresh)))
        assertEquals(GateWho.Why.REVOKED, why(proxies = listOf(old)))
    }

    // --- Тревога ---

    @Test
    fun `другой ключ за Бориса, у Бориса есть подтверждённый агент — тревога, старый по-прежнему за Бориса`() {
        val proxies = listOf(proxy())
        val r = who(packet(from = other), proxies, known = emptySet())
        val w = r.who as GateWho.Who.Unconfirmed
        assertTrue(w.alarm)
        assertEquals(listOf(agentB), w.rivals)
        assertEquals(
            "Врата: тревога — заявлено: Борис; доверенности нет; у этого человека уже есть подтверждённый агент: агент Бориса · ключ ${other.id} впервые",
            line(r),
        )
        assertTrue(who(proxies = proxies).who is GateWho.Who.ForPerson)
    }

    @Test
    fun `доверенность старого агента отозвана — новый не подтверждён, но без тревоги`() {
        val proxies = listOf(proxy(revokedMs = now - day))
        val w = who(packet(from = other), proxies).who as GateWho.Who.Unconfirmed
        assertFalse(w.alarm)
    }

    @Test
    fun `за чат тревоги нет — модераторов бывает несколько`() {
        val proxies = listOf(proxy(principal = chat, kind = GateProxy.Kind.CHAT))
        val w = who(packet(from = other, represents = chat, saidBy = other), proxies).who as GateWho.Who.Unconfirmed
        assertFalse(w.alarm)
    }

    // --- Чат не говорит ---

    @Test
    fun `за чат, сказал чат — с третьих рук, даже при действующей доверенности`() {
        val p = proxy(principal = chat, kind = GateProxy.Kind.CHAT)
        val w = who(packet(represents = chat, saidBy = chat), listOf(p)).who as GateWho.Who.Refused
        assertEquals(GateRefusal.HEARSAY, w.refusal)
    }

    @Test
    fun `чат или человек — по записи, а не по виду ключа`() {
        // Ключ похож на чат, но записей о чате нет: обычное «не подтверждено».
        val w = who(packet(represents = chat, saidBy = chat), emptyList()).who
        assertTrue(w is GateWho.Who.Unconfirmed)
    }

    // --- Срок — на момент приёма ---

    @Test
    fun `сказано и отправлено до отзыва, принято после — не подтверждено`() {
        val p = proxy(revokedMs = now - 60_000L)
        assertEquals(GateWho.Why.REVOKED, why(packet(atMs = now - 3_600_000L, sentMs = now - 3_600_000L), listOf(p)))
    }

    @Test
    fun `слова месячной давности при доверенности со вчера — за Бориса`() {
        val p = proxy(confirmedMs = now - day)
        assertTrue(who(packet(atMs = now - 30 * day), listOf(p)).who is GateWho.Who.ForPerson)
    }

    // --- Впервые ---

    @Test
    fun `ключ впервые — знакомство, на доверие не влияет`() {
        val p = proxy()
        val first = who(proxies = listOf(p), known = emptySet())
        assertTrue(first.firstSeen)
        assertEquals(GateWho.Who.ForPerson(p), first.who)
        assertFalse(who(proxies = listOf(p)).firstSeen)
        assertTrue(line(first), line(first).endsWith("· ключ ${agentB.id} впервые"))
    }

    @Test
    fun `строка за Бориса — даты в поясе получателя`() {
        val p = proxy(confirmedMs = now - day, untilMs = now + 30 * day)
        assertEquals(
            "Врата: за кого — Борис (доверенность подтверждена 19.10.2025, до 19.11.2025)",
            line(who(proxies = listOf(p))),
        )
    }
}
