package com.uroboros.memory.nav

import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.Coordinates.Address
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudsTest {

    /** Таблица глаголов — как на телефоне (см. TestVerbs). */
    @Before
    fun verbs() = TestVerbs.install()

    private val now = 100 * Clouds.HALF_LIFE_MS
    private val owner = SourceKind.USER_STATED.name

    @Test
    fun `смешанная запись даёт вес обоим облакам`() {
        val sources = listOf(Clouds.fromRecord("Работаю над твоей памятью", owner, now))
        val o = Clouds.of(PersonKey.OWNER, sources, now)
        val a = Clouds.of(PersonKey.AGENT, sources, now)
        assertTrue(o.elements.any { it.stem.startsWith("памят") })
        assertTrue(a.elements.any { it.stem.startsWith("памят") })
        // Вес набран словами владельца.
        assertTrue(a.elements.first { it.stem.startsWith("памят") }.bySpeaker.containsKey(PersonKey.OWNER))
    }

    @Test
    fun `запись о мире в облака персон не идёт`() {
        val sources = listOf(Clouds.fromRecord("Вода кипит при ста градусах", owner, now))
        assertTrue(Clouds.of(PersonKey.OWNER, sources, now).isEmpty)
        assertTrue(Clouds.of(PersonKey.AGENT, sources, now).isEmpty)
    }

    @Test
    fun `вес убывает с возрастом — полураспад`() {
        assertEquals(1.0, Clouds.decay(0), 1e-9)
        assertEquals(0.5, Clouds.decay(Clouds.HALF_LIFE_MS), 1e-9)
        val fresh = Clouds.of(PersonKey.OWNER, listOf(Clouds.fromRecord("Я люблю рубанок", owner, now)), now)
        val old = Clouds.of(
            PersonKey.OWNER,
            listOf(Clouds.fromRecord("Я люблю рубанок", owner, now - 2 * Clouds.HALF_LIFE_MS)),
            now,
        )
        assertTrue(fresh.elements.first().weight > old.elements.first().weight)
    }

    @Test
    fun `частое тяжелее редкого`() {
        val sources = listOf(
            Clouds.fromRecord("Я люблю рубанок", owner, now),
            Clouds.fromRecord("Я точу рубанок", owner, now),
            Clouds.fromRecord("Я пью чай", owner, now),
        )
        assertTrue(Clouds.of(PersonKey.OWNER, sources, now).elements.first().stem.startsWith("рубан"))
    }

    @Test
    fun `мы — пересечение облаков`() {
        val sources = listOf(
            Clouds.fromRecord("Я люблю радугу", owner, now),
            Clouds.ofAgent("Радуга мне снилась", now),
            Clouds.ofAgent("Рубанок стоит в углу", now),
        )
        val we = Clouds.we(Clouds.of(PersonKey.OWNER, sources, now), Clouds.of(PersonKey.AGENT, sources, now))
        assertEquals(1, we.elements.size)
        assertTrue(we.elements.first().stem.startsWith("радуг"))
    }

    @Test
    fun `пустая память — облако пусто, сбой — не считалось, без адреса — не определён`() {
        val empty = Clouds.Cloud(emptyList())
        assertEquals("Облако адреса: агент · облако пусто", Clouds.addressLine(Address.AGENT, empty, empty))
        assertEquals(
            "Облако адреса: не считалось — IOException",
            Clouds.addressLine(Address.AGENT, null, null, "IOException"),
        )
        assertEquals("Облако адреса: адрес не определён", Clouds.addressLine(Address.UNDEFINED, null, null))
    }

    @Test
    fun `строка хода — пять верхних элементов с весом`() {
        val sources = (1..7).map { Clouds.ofAgent("слово$it другое$it", now) }
        val line = Clouds.addressLine(Address.AGENT, Clouds.Cloud(emptyList()), Clouds.of(PersonKey.AGENT, sources, now))
        assertEquals(5, line.substringAfter("агент · ").split(", ").size)
    }

    @Test
    fun `раздел облаков называет чьими словами набран вес`() {
        val sources = listOf(Clouds.fromRecord("Работаю над твоей памятью", owner, now))
        val text = Clouds.section(Clouds.of(PersonKey.OWNER, sources, now), Clouds.of(PersonKey.AGENT, sources, now))
        assertTrue(text.startsWith("ОБЛАКА"))
        assertTrue(text.contains("владелец"))
    }
}
