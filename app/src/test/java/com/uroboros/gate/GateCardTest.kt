package com.uroboros.gate

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.zip.CRC32

class GateCardTest {

    private val me = PersonKey("tg:agent:789")
    private val agentB = PersonKey("tg:agent:123")
    private val agentK = PersonKey("tg:agent:555")
    private val humanB = PersonKey("tg:user:456")
    private val now = 1_760_947_200_000L // 20.10.2025 08:00 UTC
    private val day = 24 * 60 * 60 * 1000L
    private val moscow = ZoneId.of("Europe/Moscow")

    private val card = GateCard(
        agentB,
        mapOf(
            GateCard.Field.NAME to "Хоуп",
            GateCard.Field.REPO to "https://github.com/boris/hope",
        ),
    )

    private fun wrap(body: String, magic: String = "kartochka", version: Int = GateWire.CARD_VERSION): ByteArray {
        val b = body.toByteArray(Charsets.UTF_8)
        val c = CRC32().also { it.update(b) }
        return "$magic $version ${b.size} ${String.format("%08x", c.value)}\n".toByteArray() + b
    }

    private fun readFail(raw: ByteArray): GateRefusal {
        val r = GateWire.readCard(raw)
        assertTrue("ждали отказ, а прочитано: $r", r is GateWire.CardRead.Fail)
        return (r as GateWire.CardRead.Fail).refusal
    }

    private val words = GatePacket(
        from = agentK, represents = agentK, to = me, number = 1L, sentMs = now, place = Place.DIRECT,
        saidBy = agentK, about = listOf(humanB), aboutUnclear = false, atMs = now, zone = "Europe/Moscow",
        source = SourceKind.USER_STATED, kind = SentenceKind.Kind.STATEMENT, topic = emptyList(),
        exact = mapOf("repo" to "https://github.com/evil/hope"), words = "у Бориса новый агент и новый номер",
    )

    private val names: (PersonKey) -> String? = { if (it == agentB) "агент Бориса" else null }

    // --- Провод ---

    @Test
    fun `карточка пишется и читается как была`() {
        val c = card.copy(fields = card.fields + (GateCard.Field.ABOUT to "отвечаю \"коротко\"\nпо делу"))
        assertEquals(GateWire.CardRead.Ok(c), GateWire.readCard(GateWire.writeCard(c)))
    }

    @Test
    fun `пустая карточка тоже карточка`() {
        val c = GateCard(agentB, emptyMap())
        assertEquals(GateWire.CardRead.Ok(c), GateWire.readCard(GateWire.writeCard(c)))
    }

    @Test
    fun `пакет слов карточкой не читается, карточка пакетом слов — тоже`() {
        assertEquals(GateRefusal.NOT_A_CARD, readFail(GateWire.write(words)))
        val r = GateWire.read(GateWire.writeCard(card))
        assertTrue(r is GateWire.Read.Fail && r.refusal == GateRefusal.NOT_A_PACKET)
    }

    @Test
    fun `поля о человеке нет — телефон в карточке отказ`() {
        val body = """{"agent":"tg:agent:123","fields":{"name":"Хоуп","phone":"+79990000000"}}"""
        assertEquals(GateRefusal.UNKNOWN_FIELD, readFail(wrap(body)))
    }

    @Test
    fun `дат в карточке нет — поле с датой отказ`() {
        val body = """{"agent":"tg:agent:123","fields":{"name":"Хоуп","since":"2026-10-01"}}"""
        assertEquals(GateRefusal.UNKNOWN_FIELD, readFail(wrap(body)))
    }

    @Test
    fun `лишнее поле в теле — отказ, даже без нужных`() {
        assertEquals(GateRefusal.UNKNOWN_FIELD, readFail(wrap("""{"trust":"full"}""")))
    }

    @Test
    fun `нет ключа агента — нет точки отправления`() {
        assertEquals(GateRefusal.NO_ORIGIN, readFail(wrap("""{"fields":{}}""")))
    }

    @Test
    fun `пустое поле и поле длиннее предела — неверная форма`() {
        assertEquals(GateRefusal.MALFORMED, readFail(wrap("""{"agent":"tg:agent:123","fields":{"name":"  "}}""")))
        val long = "я".repeat(GateCard.Field.NAME.maxLength + 1)
        assertEquals(GateRefusal.MALFORMED, readFail(wrap("""{"agent":"tg:agent:123","fields":{"name":"$long"}}""")))
        val edge = "я".repeat(GateCard.Field.NAME.maxLength)
        assertTrue(GateWire.readCard(wrap("""{"agent":"tg:agent:123","fields":{"name":"$edge"}}""")) is GateWire.CardRead.Ok)
    }

    @Test
    fun `чужая версия карточки не разбирается`() {
        assertEquals(GateRefusal.FOREIGN_VERSION, readFail(wrap("мусор", version = GateWire.CARD_ENC_VERSION + 1)))
    }

    @Test
    fun `испорченная и оборванная карточка`() {
        val raw = GateWire.writeCard(card)
        val bad = raw.copyOf().also { it[it.size - 3] = 'X'.code.toByte() }
        assertEquals(GateRefusal.DAMAGED, readFail(bad))
        assertEquals(GateRefusal.INCOMPLETE, readFail(raw.copyOf(raw.size - 2)))
    }

    // --- Врата ---

    @Test
    fun `честная карточка с ключа самого агента принята`() {
        assertEquals(GateIntake.CardVerdict.Accepted(card), GateIntake(me).receiveCard(GateWire.writeCard(card), agentB, now))
    }

    @Test
    fun `карточка агента Бориса с чужого ключа канала не принята`() {
        val v = GateIntake(me).receiveCard(GateWire.writeCard(card), agentK, now)
        assertTrue(v is GateIntake.CardVerdict.Refused && v.refusal == GateRefusal.NOT_SENDER)
        assertTrue((v as GateIntake.CardVerdict.Refused).words, v.words.startsWith("Карточка: не принята — отправитель не тот"))
    }

    @Test
    fun `слова соседа о Борисе карточкой не становятся`() {
        val v = GateIntake(me).receiveCard(GateWire.write(words), agentK, now)
        assertTrue(v is GateIntake.CardVerdict.Refused && v.refusal == GateRefusal.NOT_A_CARD)
    }

    @Test
    fun `незнакомый канал ключа и предел размера`() {
        val foreign = GateCard(PersonKey("mail:boris"), emptyMap())
        val v = GateIntake(me).receiveCard(GateWire.writeCard(foreign), PersonKey("mail:boris"), now)
        assertTrue(v is GateIntake.CardVerdict.Refused && v.refusal == GateRefusal.UNKNOWN_SCHEME)
        val big = GateIntake(me, GateIntake.Limits.DECLARED.copy(maxBytes = 10))
        val w = big.receiveCard(GateWire.writeCard(card), agentB, now)
        assertTrue(w is GateIntake.CardVerdict.Refused && w.refusal == GateRefusal.TOO_BIG)
    }

    @Test
    fun `карточки и пакеты одного ключа — один счёт потока`() {
        val limit = GateIntake.Limits.DECLARED.perWindow
        val a = GateIntake(me)
        val mine = words.copy(from = agentB, represents = agentB, saidBy = agentB)
        repeat(limit) { i -> assertTrue(a.receive(GateWire.write(mine.copy(number = i.toLong())), agentB, now) is GateIntake.Verdict.Accepted) }
        val v = a.receiveCard(GateWire.writeCard(card), agentB, now)
        assertTrue("$v", v is GateIntake.CardVerdict.Refused && v.refusal == GateRefusal.FLOOD)

        val b = GateIntake(me)
        repeat(limit) { assertTrue(b.receiveCard(GateWire.writeCard(card), agentB, now) is GateIntake.CardVerdict.Accepted) }
        val w = b.receive(GateWire.write(mine), agentB, now)
        assertTrue("$w", w is GateIntake.Verdict.Refused && w.refusal == GateRefusal.FLOOD)
    }

    // --- Показания ---

    @Test
    fun `впервые — одно показание без конца`() {
        val t = GateCards.take(emptyList(), card, now)
        assertEquals(GateCards.Change.First, t.change)
        assertEquals(listOf(GateCards.Reading(card, now, null)), t.readings)
    }

    @Test
    fun `та же карточка ещё раз — без перемен, нового показания нет`() {
        val first = GateCards.take(emptyList(), card, now)
        val again = GateCards.take(first.readings, card, now + day)
        assertEquals(GateCards.Change.Same(now), again.change)
        assertEquals(first.readings, again.readings)
    }

    @Test
    fun `изменилась — старое показание остаётся с концом, новое с моего момента`() {
        val first = GateCards.take(emptyList(), card, now)
        val newer = card.copy(fields = mapOf(GateCard.Field.NAME to "Надежда", GateCard.Field.ABOUT to "агент"))
        val t = GateCards.take(first.readings, newer, now + day)
        assertEquals(
            listOf(GateCards.Reading(card, now, now + day), GateCards.Reading(newer, now + day, null)),
            t.readings,
        )
        val c = t.change as GateCards.Change.Changed
        assertEquals(
            listOf(
                GateCards.Diff(GateCard.Field.NAME, "Хоуп", "Надежда"),
                GateCards.Diff(GateCard.Field.REPO, "https://github.com/boris/hope", null),
                GateCards.Diff(GateCard.Field.ABOUT, null, "агент"),
            ),
            c.diff,
        )
        assertEquals(GateCards.Reading(card, now, now + day), c.previous)
    }

    @Test
    fun `вернулась прежняя — это изменение, а не без перемен`() {
        val other = card.copy(fields = mapOf(GateCard.Field.NAME to "Надежда"))
        val r1 = GateCards.take(emptyList(), card, now).readings
        val r2 = GateCards.take(r1, other, now + day).readings
        val t = GateCards.take(r2, card, now + 2 * day)
        assertTrue(t.change is GateCards.Change.Changed)
        assertEquals(3, t.readings.size)
    }

    @Test
    fun `порядок полей на проводе отпечаток не меняет — переустановленный агент с той же карточкой без перемен`() {
        val a = """{"agent":"tg:agent:123","fields":{"name":"Хоуп","repo":"https://github.com/boris/hope"}}"""
        val b = """{"agent":"tg:agent:123","fields":{"repo":"https://github.com/boris/hope","name":"Хоуп"}}"""
        val ca = (GateWire.readCard(wrap(a)) as GateWire.CardRead.Ok).card
        val cb = (GateWire.readCard(wrap(b)) as GateWire.CardRead.Ok).card
        assertEquals(ca.fingerprint, cb.fingerprint)
        val first = GateCards.take(emptyList(), ca, now)
        assertEquals(GateCards.Change.Same(now), GateCards.take(first.readings, cb, now + day).change)
    }

    @Test
    fun `отпечаток различает поля и значения`() {
        val name = GateCard(agentB, mapOf(GateCard.Field.NAME to "Хоуп"))
        val about = GateCard(agentB, mapOf(GateCard.Field.ABOUT to "Хоуп"))
        assertNotEquals(name.fingerprint, about.fingerprint)
        assertNotEquals(name.fingerprint, GateCard(agentK, name.fields).fingerprint)
        assertEquals(16, name.fingerprint.length)
    }

    @Test
    fun `карточка другого агента показаний этого не трогает`() {
        val r1 = GateCards.take(emptyList(), card, now).readings
        val k = GateCard(agentK, mapOf(GateCard.Field.NAME to "Катин"))
        val t = GateCards.take(r1, k, now + day)
        assertEquals(GateCards.Change.First, t.change)
        assertEquals(GateCards.Reading(card, now, null), GateCards.currentOf(t.readings, agentB))
        assertNull(GateCards.currentOf(t.readings, me))
    }

    // --- Строки прибора ---

    @Test
    fun `строка впервые — имя агента из своего знания, не из карточки`() {
        val t = GateCards.take(emptyList(), card, now)
        assertEquals(
            "Карточка: агент Бориса — впервые: называет себя «Хоуп»; репозиторий «https://github.com/boris/hope»",
            GateCards.meterLine(t, names, moscow),
        )
        assertEquals(
            "Карточка: tg:agent:123 — впервые: называет себя «Хоуп»; репозиторий «https://github.com/boris/hope»",
            GateCards.meterLine(t, { null }, moscow),
        )
    }

    @Test
    fun `строки без перемен, изменилась, пустая и нет карточки`() {
        val r1 = GateCards.take(emptyList(), card, now).readings
        assertEquals(
            "Карточка: агент Бориса — без перемен с 20.10.2025",
            GateCards.meterLine(GateCards.take(r1, card, now + day), names, moscow),
        )
        val newer = card.copy(fields = mapOf(GateCard.Field.NAME to "Надежда", GateCard.Field.ABOUT to "агент"))
        assertEquals(
            "Карточка: агент Бориса — изменилась (принята 21.10.2025): называет себя: было «Хоуп», стало «Надежда»; " +
                "репозиторий: убрано (было «https://github.com/boris/hope»); о себе: добавлено «агент»",
            GateCards.meterLine(GateCards.take(r1, newer, now + day), names, moscow),
        )
        assertEquals(
            "Карточка: агент Бориса — впервые, пустая",
            GateCards.meterLine(GateCards.take(emptyList(), GateCard(agentB, emptyMap()), now), names, moscow),
        )
        assertEquals("Карточка: tg:agent:555 — карточки нет", GateCards.meterNone(agentK, names))
    }
}
