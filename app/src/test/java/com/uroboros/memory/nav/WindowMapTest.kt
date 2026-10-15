package com.uroboros.memory.nav

import com.uroboros.memory.nav.WindowMap.Footing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Карта окна: тема из слов владельца по разговору, опора реплики. Тесты на
 * молчание — первый ход без карты, глаголы просьб и обычаи не тема, прошлый
 * разговор (после тишины) в карту не идёт.
 */
class WindowMapTest {

    @Before
    fun dictionaries() = TestVerbs.install()

    private val minute = 60_000L
    private val now = 1_000 * minute

    private fun turns(vararg q: String) = q.mapIndexed { i, s -> WindowMap.Turn(s, now - (q.size - i) * minute) }

    private fun map(reply: String, vararg q: String) = WindowMap.of(turns(*q), reply, now, TestVerbs.lemmas)

    private fun labels(r: WindowMap.Result) = r.words.map { it.label }

    @Test
    fun `первый ход разговора — карты нет`() {
        val r = map("Что делают белки в клетке?")
        assertEquals(Footing.NO_MAP, r.footing)
        assertTrue(WindowMap.meterLine(r, 1).contains("первый ход разговора — карты нет"))
    }

    @Test
    fun `короткая реплика без своих слов опирается на карту`() {
        val r = map("А что было потом?", "Что делают белки в клетке?")
        assertEquals(Footing.MAP, r.footing)
        assertTrue(labels(r).toString(), labels(r).any { "клетка" in it })
    }

    @Test
    fun `формы одного слова сводятся и копятся`() {
        val r = map("Ладно.", "Какого ты пола?", "Пол у тебя какой?")
        val pol = r.words.first { "пол" in it.lemmas }
        assertTrue(pol.weight > 1.0)
        assertEquals(1, r.words.count { "пол" in it.lemmas })
    }

    @Test
    fun `двузначное слово держится всеми формами`() {
        val r = map("А дальше?", "Что делают белки в клетке?")
        val unit = r.words.first { "белка" in it.lemmas || "белок" in it.lemmas }
        assertTrue(unit.label, "белка" in unit.lemmas && "белок" in unit.lemmas)
    }

    @Test
    fun `глаголы просьб и обычаи темой не бывают`() {
        val r = map("Ещё.", "Привет.", "Расскажи подробно, перечисли всё.")
        assertTrue(labels(r).toString(), r.words.isEmpty())
    }

    @Test
    fun `смена темы — опора на реплику`() {
        val r = map("Тебе снилось море?", "Какой самый холодный континент?", "Антарктида или Гималаи?")
        assertEquals(Footing.NEW_TOPIC, r.footing)
        assertTrue(WindowMap.meterLine(r, 1).contains("смена темы — опора на реплику"))
    }

    @Test
    fun `слово реплики на карте — продолжение темы`() {
        val r = map("А континент Африка?", "Какой самый холодный континент?")
        assertEquals(Footing.CONTINUES, r.footing)
    }

    @Test
    fun `разговор до часовой тишины в карту не идёт`() {
        val old = WindowMap.Turn("Какой самый холодный континент?", now - 5 * 60 * minute)
        val r = WindowMap.of(listOf(old), "А белки?", now, TestVerbs.lemmas)
        assertEquals(Footing.NO_MAP, r.footing)
        assertEquals(0, r.turns)
    }

    @Test
    fun `незнакомое словарю слово остаётся как написано`() {
        val r = map("И что?", "Расскажи про битву при Каннах, про Ганнибалла.")
        assertTrue(labels(r).toString(), labels(r).contains("ганнибалла"))
    }

    @Test
    fun `без словаря — значимые слова как написаны, и строка это говорит`() {
        val r = WindowMap.of(turns("Что делают белки в клетке?"), "А потом?", now, null)
        assertFalse(r.dictionary)
        assertTrue(labels(r).toString(), labels(r).contains("белки"))
        assertTrue(WindowMap.meterLine(r, null).contains("словаря нет"))
    }

    @Test
    fun `свежий ход тяжелее старого`() {
        val r = map("Да?", "Расскажи про рубанок.", "А стамеска?")
        val rub = r.words.first { "рубанок" in it.lemmas }
        val st = r.words.first { "стамеска" in it.lemmas }
        assertTrue(st.weight > rub.weight)
    }

    @Test
    fun `сбой — строка называет причину`() {
        assertEquals("Карта окна: не считалась — IOException", WindowMap.meterLine(null, null, "IOException"))
    }
}
