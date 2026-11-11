package com.uroboros.memory.nav

import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactAgingTest {

    private val t0 = 1_000_000_000_000L

    private fun line(id: Long, text: String, at: Long) = Portrait.Line(id, text, at, fromArchive = false, fact = true)
    private fun key(l: Portrait.Line) = PersonFacts.Key(l.recordId, l.sentence)

    private val moscow = line(1, "Я живу в Москве.", t0)
    private val samara = line(2, "Теперь я живу в Самаре.", t0 + 10)
    private val chemist = line(3, "По профессии я химик.", t0 + 1)
    private val coder = line(4, "Я сменил профессию, теперь я программист.", t0 + 20)
    private val smoke = line(5, "Я не курю.", t0 + 2)
    private val smokeAgain = line(6, "Я снова начал курить.", t0 + 30)
    private val tea = line(7, "Мне нравится чай.", t0 + 3)

    // --- Ответ модели ---

    @Test
    fun `ответ читается словом`() {
        assertEquals(FactAging.Verdict.OUTDATES, FactAging.parse("Отменяет."))
        assertEquals(FactAging.Verdict.KEEPS, FactAging.parse("нет"))
        assertEquals(FactAging.Verdict.UNCLEAR, FactAging.parse("может быть"))
    }

    // --- Какие пары ---

    @Test
    fun `пары — по общему значимому слову в начальной форме, старый к новому`() {
        val p = FactAging.toAsk(listOf(listOf(coder, samara, chemist, moscow, smoke, smokeAgain, tea)), dictionary, emptySet(), emptySet())
        val pairs = p.next.map { it.old.recordId to it.new.recordId }.toSet()
        assertTrue(pairs.toString(), 1L to 2L in pairs)
        assertTrue("профессия / профессию", 3L to 4L in pairs)
        assertTrue("курю / курить", 5L to 6L in pairs)
        assertFalse("чай ни с чем не связан", pairs.any { it.first == 7L || it.second == 7L })
        assertFalse("новый к старому не спрашивается", 2L to 1L in pairs)
    }

    @Test
    fun `местоимения и служебные слова пары не делают`() {
        val a = line(10, "Я люблю поезда.", t0)
        val b = line(11, "Мне нравится чай.", t0 + 1)
        assertTrue(FactAging.toAsk(listOf(listOf(a, b)), dictionary, emptySet(), emptySet()).next.isEmpty())
    }

    @Test
    fun `повтор той же фразы — не пара`() {
        val a = line(10, "Я - Иван.", t0)
        val b = line(11, "Я - Иван.", t0 + 1)
        assertTrue(FactAging.toAsk(listOf(listOf(a, b)), dictionary, emptySet(), emptySet()).next.isEmpty())
    }

    @Test
    fun `пары только внутри одного собеседника`() {
        val p = FactAging.toAsk(listOf(listOf(moscow), listOf(samara)), dictionary, emptySet(), emptySet())
        assertTrue(p.next.isEmpty())
    }

    @Test
    fun `спрошенные и устаревшие не спрашиваются, потолок держится, новые первыми`() {
        val all = listOf(coder, samara, chemist, moscow, smoke, smokeAgain)
        val asked = setOf(FactAging.Pair(key(moscow), key(samara)))
        val p = FactAging.toAsk(listOf(all), dictionary, asked, setOf(key(chemist)), limit = 1)
        assertEquals(listOf(5L to 6L), p.next.map { it.old.recordId to it.new.recordId })
        assertEquals(1, p.total)
    }

    // --- Устаревшее в портрете ---

    @Test
    fun `устаревшее — пока жива запись нового факта`() {
        val pair = FactAging.Pair(key(moscow), key(samara))
        assertEquals(setOf(key(moscow)), FactAging.outdated(listOf(pair), setOf(1L, 2L)))
        assertTrue("новый отвергнут — старый снова в портрете", FactAging.outdated(listOf(pair), setOf(1L)).isEmpty())
    }

    @Test
    fun `устаревшая строка уходит из портрета, запись остаётся`() {
        val records = listOf(
            Sticker(id = 1, content = "Я живу в Москве.", createdAt = t0, source = SourceKind.USER_STATED.name),
            Sticker(id = 2, content = "Теперь я живу в Самаре.", createdAt = t0 + 10, source = SourceKind.USER_STATED.name),
        )
        val r = Portrait.of(records, outdated = setOf(PersonFacts.Key(1, "Я живу в Москве.")))
        assertEquals(listOf("Теперь я живу в Самаре."), r.passed.map { it.sentence })
        assertEquals(1, r.outdated)
        assertEquals(2, r.records)
        assertTrue(PersonFacts.marksLine(r), PersonFacts.marksLine(r).endsWith(", устаревших 1"))
    }

    @Test
    fun `без устаревших портрет прежний`() {
        val records = listOf(Sticker(id = 1, content = "Я живу в Москве.", createdAt = t0, source = SourceKind.USER_STATED.name))
        val r = Portrait.of(records)
        assertEquals(1, r.passed.size)
        assertEquals(0, r.outdated)
        assertFalse(PersonFacts.marksLine(r).contains("устаревших"))
    }

    // --- Итог ---

    @Test
    fun `итог ночи различает все случаи`() {
        assertEquals("Устаревшее: не сравнивал — словарь начальных форм не загружен", FactAging.outcome(0, emptyList(), 0, null, noDictionary = true))
        assertEquals("Устаревшее: сравнивать нечего — новых пар фактов на одну тему нет", FactAging.outcome(0, emptyList(), 0, null))
        assertEquals("Устаревшее: не сравнивал — модель занята", FactAging.outcome(0, emptyList(), 4, "модель занята"))
        assertEquals(
            "Устаревшее: пар 3, отменено 1: №1 ← №2, осталось 0",
            FactAging.outcome(3, listOf(FactAging.Pair(key(moscow), key(samara))), 0, null),
        )
        assertEquals("Устаревшее: пар 2, не отменено ничего, осталось 5", FactAging.outcome(2, emptyList(), 5, null))
    }

    companion object {
        private val dictionary: LemmaDictionary by lazy {
            val file = listOf("src/main/assets/lemma_forms.txt", "app/src/main/assets/lemma_forms.txt")
                .map { java.io.File(it) }.first { it.exists() }
            file.bufferedReader(Charsets.UTF_8).useLines { LemmaDictionary.read(it) }
        }
    }
}
