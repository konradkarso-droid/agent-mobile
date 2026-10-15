package com.uroboros.memory.nav

import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.Coordinates.Address
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val text = Clouds.section(
            Clouds.of(PersonKey.OWNER, sources, now),
            Clouds.of(PersonKey.AGENT, sources, now),
            Clouds.Gathered(sources, 0, 0),
        )
        assertTrue(text.startsWith("ОБЛАКА"))
        assertTrue(text.contains("владелец"))
    }

    @Test
    fun `слова для строки имени — написанные формы, без слова «агент»`() {
        val sources = listOf(
            Clouds.ofAgent("Цвета радуги красивые", now),
            Clouds.ofAgent("Радуги бывают двойные", now),
            Clouds.ofAgent("Радуга над рекой", now),
            Clouds.ofAgent("Я агент, агент на телефоне", now),
        )
        val words = Clouds.words(Clouds.of(PersonKey.AGENT, sources, now), 5, "агент")
        assertTrue(words.toString(), "радуги" in words)
        assertTrue(words.toString(), words.none { it.startsWith("агент") })
        assertTrue(words.toString(), words.none { it == "радуг" })
    }

    // ---- Чистка ----

    private fun stems(cloud: Clouds.Cloud) = cloud.elements.map { it.stem }

    @Test
    fun `запись, что ничего не утверждает, в облака не идёт`() {
        assertFalse(Clouds.keepsRecord("Что тебе снилось?"))
        assertFalse(Clouds.keepsRecord("Привет, назови температуру и уровень заряда."))
        assertFalse(Clouds.keepsRecord("Доброй ночи."))
        assertTrue(Clouds.keepsRecord("Я живу в телефоне."))
        assertTrue(Clouds.keepsRecord("Я точу рубанок. Ты умеешь точить?"))
    }

    @Test
    fun `своя речь не о себе в облако агента не идёт`() {
        assertFalse(Clouds.keepsOwn("Белки поддерживают температуру тела."))
        assertFalse(Clouds.keepsOwn("Море было тёплым."))
        assertTrue(Clouds.keepsOwn("Я живу в телефоне."))
        assertTrue(Clouds.keepsOwn("Мне снилось море."))
    }

    @Test
    fun `слова-связки снимаются по словарю`() {
        val sources = listOf(Clouds.ofAgent("Спасибо тебе за этот разговор, пока", now))
        val cloud = Clouds.of(PersonKey.AGENT, sources, now, TestVerbs.lemmas)
        assertTrue(stems(cloud).toString(), stems(cloud).none { it.startsWith("теб") || it.startsWith("пок") })
        assertTrue(stems(cloud).toString(), stems(cloud).any { it.startsWith("разговор") })
        assertTrue(cloud.linkWords!! >= 2)
    }

    @Test
    fun `притяжательные снимаются, а существительные остаются`() {
        val sources = listOf(Clouds.ofAgent("Твою память и свою модель храню в телефоне", now))
        val s = stems(Clouds.of(PersonKey.AGENT, sources, now, TestVerbs.lemmas))
        assertTrue(s.toString(), s.none { it.startsWith("тво") || it.startsWith("сво") })
        assertTrue(s.toString(), listOf("памят", "модел", "телефон").all { p -> s.any { it.startsWith(p) } })
    }

    @Test
    fun `без словаря связки не снимаются, и строка это говорит`() {
        val sources = listOf(Clouds.ofAgent("Спасибо тебе за этот разговор", now))
        val agent = Clouds.of(PersonKey.AGENT, sources, now)
        assertTrue(stems(agent).toString(), stems(agent).any { it.startsWith("теб") })
        assertEquals(null, agent.linkWords)
        val line = Clouds.cleaningLine(Clouds.Gathered(sources, 3, 2), Clouds.Cloud(emptyList()), agent)
        assertEquals(
            "Снято: записей, что ничего не утверждают, 3 · предложений своей речи не о себе 2 · " +
                "слова-связки не сняты — словарь не загружен",
            line,
        )
    }

    @Test
    fun `строка чистки со словарём считает связки порознь`() {
        val sources = listOf(
            Clouds.fromRecord("Я люблю твою память", owner, now),
            Clouds.ofAgent("Спасибо тебе за разговор", now),
        )
        val o = Clouds.of(PersonKey.OWNER, sources, now, TestVerbs.lemmas)
        val a = Clouds.of(PersonKey.AGENT, sources, now, TestVerbs.lemmas)
        val line = Clouds.cleaningLine(Clouds.Gathered(sources, 0, 0), o, a)
        assertTrue(line, line.endsWith("слов-связок: у владельца ${o.linkWords}, у агента ${a.linkWords}"))
        assertTrue(Clouds.section(o, a, Clouds.Gathered(sources, 0, 0)).contains(line))
    }
}
