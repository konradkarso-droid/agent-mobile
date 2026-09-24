package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистые правила круга дольменов: раздача мест, слова темы, правило окна
 * темы, строка прибора. Как окна ищут в базе — в [CircleSelectionTest].
 */
class DolmenCircleTest {

    private fun s(id: Long) = Sticker(id = id, content = "запись $id", createdAt = 0L, lastAccessedAt = 0L)

    private fun ids(list: List<Sticker>) = list.map { it.id }

    // --- Раздача мест ---

    @Test
    fun `каждое окно получает свои гарантированные места`() {
        val seating = DolmenCircle.seat(
            5,
            question = (1L..6L).map(::s),
            theme = (11L..13L).map(::s),
            cold = (21L..23L).map(::s),
        )
        assertEquals(listOf(1L, 2L, 3L), ids(seating.question))
        assertEquals(listOf(11L), ids(seating.theme))
        assertEquals(listOf(21L), ids(seating.cold))
        assertEquals(5, seating.all.size)
    }

    @Test
    fun `незанятые места уходят сперва вопросу`() {
        val seating = DolmenCircle.seat(5, (1L..6L).map(::s), emptyList(), emptyList())
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(seating.question))
    }

    @Test
    fun `вопросу нечего дать — места уходят теме, потом холоду`() {
        val seating = DolmenCircle.seat(5, listOf(s(1)), (11L..13L).map(::s), (21L..23L).map(::s))
        assertEquals(listOf(1L), ids(seating.question))
        assertEquals(listOf(11L, 12L, 13L), ids(seating.theme))
        assertEquals(listOf(21L), ids(seating.cold))
    }

    @Test
    fun `остаток после темы уходит холоду`() {
        val seating = DolmenCircle.seat(5, emptyList(), listOf(s(11)), (21L..26L).map(::s))
        assertEquals(listOf(11L), ids(seating.theme))
        assertEquals(listOf(21L, 22L, 23L, 24L), ids(seating.cold))
    }

    /** Главный случай окна темы: у вопроса нет находок, тема забирает места. */
    @Test
    fun `пустой вопрос отдаёт места теме`() {
        val seating = DolmenCircle.seat(5, emptyList(), (11L..17L).map(::s), emptyList())
        assertEquals(listOf(11L, 12L, 13L, 14L, 15L), ids(seating.theme))
    }

    @Test
    fun `запись, найденная вопросом, в теме второй раз не считается`() {
        val seating = DolmenCircle.seat(5, listOf(s(1), s(2)), listOf(s(2), s(11)), emptyList())
        assertEquals(listOf(1L, 2L), ids(seating.question))
        assertEquals(listOf(11L), ids(seating.theme))
        assertEquals(seating.all.size, seating.all.map { it.id }.toSet().size)
    }

    /** Найдена ранним окном, но им не усажена — позднее окно её всё равно не берёт. */
    @Test
    fun `найденная, но не усаженная вопросом, в тему не переходит`() {
        val question = (1L..7L).map(::s)
        val seating = DolmenCircle.seat(5, question, listOf(s(7), s(11)), emptyList())
        assertEquals(listOf(11L), ids(seating.theme))
        assertFalse(7L in ids(seating.all))
    }

    @Test
    fun `холод не повторяет найденное темой и вопросом`() {
        val seating = DolmenCircle.seat(5, listOf(s(1)), listOf(s(11)), listOf(s(1), s(11), s(21)))
        assertEquals(listOf(21L), ids(seating.cold))
    }

    @Test
    fun `мест меньше гарантированных — раздаются по порядку окон`() {
        val seating = DolmenCircle.seat(2, (1L..3L).map(::s), listOf(s(11)), listOf(s(21)))
        assertEquals(listOf(1L, 2L), ids(seating.question))
        assertTrue(seating.theme.isEmpty())
        assertTrue(seating.cold.isEmpty())
    }

    @Test
    fun `все окна пусты — мест не занято`() {
        assertTrue(DolmenCircle.seat(5, emptyList(), emptyList(), emptyList()).all.isEmpty())
    }

    /** Порядок в ответе: вопрос, тема, холод. */
    @Test
    fun `в ответ идут сперва вопрос, потом тема, потом холод`() {
        val seating = DolmenCircle.seat(5, listOf(s(1)), listOf(s(11)), listOf(s(21)))
        assertEquals(listOf(1L, 11L, 21L), ids(seating.all))
    }

    // --- Слова темы ---

    @Test
    fun `тема берётся из трёх последних непустых реплик минус слова вопроса`() {
        val words = DolmenCircle.themeWords(
            recentQuestions = listOf("старый рубанок забыт", "колодка рубанка", "", "берёза крепкая", "лезвие острое"),
            questionWords = listOf("лезвие"),
        )
        // «старый рубанок забыт» — четвёртая с конца непустая, за окном темы.
        assertTrue("рубанок" !in words)
        assertTrue("лезвие" !in words)
        assertEquals(setOf("острое", "берёза", "крепкая", "колодка", "рубанка"), words.toSet())
    }

    @Test
    fun `без ленты темы нет`() {
        assertTrue(DolmenCircle.themeWords(emptyList(), listOf("рубанок")).isEmpty())
        assertTrue(DolmenCircle.themeWords(listOf("", "  "), emptyList()).isEmpty())
    }

    /** Слова темы — тем же отсевом, что и слова вопроса: короткие и служебные уходят. */
    @Test
    fun `тема отсеивает короткие и служебные слова`() {
        val words = DolmenCircle.themeWords(listOf("что это было про рубанок"), emptyList())
        assertEquals(listOf("рубанок"), words)
    }

    // --- Правило окна темы ---

    @Test
    fun `тема из многих слов требует двух совпавших`() {
        assertTrue(scoreThemeCandidate(mapOf("рубанок" to 7), totalWords = 10, contentLength = 20).tooNarrow)
        assertTrue(
            scoreThemeCandidate(mapOf("рубанок" to 7, "колодка" to 7), totalWords = 10, contentLength = 20).passed
        )
    }

    /** Доля к теме не применяется: два слова из восемнадцати — это 11%, и всё же проходит. */
    @Test
    fun `доля вопроса к теме не применяется`() {
        val score = scoreThemeCandidate(mapOf("рубанок" to 7, "колодка" to 7), totalWords = 18, contentLength = 20)
        assertTrue(score.coverage < MIN_COVERAGE)
        assertTrue(score.passed)
    }

    @Test
    fun `тема из одного слова проходит одним совпадением`() {
        assertTrue(scoreThemeCandidate(mapOf("рубанок" to 7), totalWords = 1, contentLength = 20).passed)
    }

    /** Цена места — та же, что у вопроса: ровно пороговое значение проходит, выше — нет. */
    @Test
    fun `цена места у темы на пороге проходит, выше отсеивается`() {
        val matched = mapOf("рубанок" to 7, "колодка" to 7)
        val atLimit = (14 * MAX_CHARS_PER_MATCH).toInt()
        assertTrue(scoreThemeCandidate(matched, 10, atLimit).passed)
        val above = scoreThemeCandidate(matched, 10, atLimit + 1)
        assertTrue(above.tooExpensive)
        assertFalse(above.passed)
    }

    @Test
    fun `пустое совпадение темы отсеивается`() {
        assertFalse(scoreThemeCandidate(emptyMap(), totalWords = 3, contentLength = 20).passed)
    }

    // --- Прибор ---

    @Test
    fun `прибор различает четыре показания окна`() {
        val line = DolmenCircle.meter(
            question = DolmenCircle.Window.Found(4, 3),
            theme = DolmenCircle.Window.NoWords,
            themeWords = emptyList(),
            cold = DolmenCircle.Window.LayerEmpty,
            coldByTheme = false,
            red = 0,
        )
        assertTrue(line.startsWith("Круг:"))
        assertTrue(line.contains("вопрос — нашёл 4, мест 3"))
        assertTrue(line.contains("тема — нечем искать (нет слов)"))
        assertTrue(line.contains("холод — слой пуст"))
        assertTrue(line.contains("красный: 0 — в ответ не идут, ждут стенной части"))

        val empty = DolmenCircle.meter(
            DolmenCircle.Window.SearchedEmpty, DolmenCircle.Window.SearchedEmpty,
            listOf("рубанок", "колодка"), DolmenCircle.Window.SearchedEmpty, coldByTheme = true, red = 2,
        )
        assertTrue(empty.contains("вопрос — искал — пусто"))
        assertTrue(empty.contains("тема (рубанок, колодка) — искал — пусто"))
        assertTrue(empty.contains("холод по словам темы — искал — пусто"))
        assertTrue(empty.contains("красный: 2"))
    }

    @Test
    fun `счёт холода печатается, только если холод что-то дал`() {
        assertNull(DolmenCircle.coldUse(0, 0, 0))
        assertEquals("холод: использовано 0 из 1 · согрето 0", DolmenCircle.coldUse(1, 0, 0))
        assertEquals("холод: использовано 1 из 1 · согрето 1", DolmenCircle.coldUse(1, 1, 1))
    }

    /** Числа мест: сумма гарантированных — ровно пять мест ответа. */
    @Test
    fun `гарантированные места складываются в пять`() {
        assertEquals(5, DolmenCircle.QUESTION_SEATS + DolmenCircle.THEME_SEATS + DolmenCircle.COLD_SEATS)
    }
}
