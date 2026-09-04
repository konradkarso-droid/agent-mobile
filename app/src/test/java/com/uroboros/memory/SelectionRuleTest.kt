package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило отсева кандидатов (scoreCandidate) и два его порога.
 *
 * Что здесь закрепляется и почему именно так.
 *
 * ЧИСЛА ПИШУТСЯ ЛИТЕРАЛАМИ. Тест, вычисляющий ожидаемое из тех же констант,
 * согласится с любой их правкой и проверит только арифметику. Тот же приём и по
 * той же причине применён в ImportanceRankTest.
 *
 * ГРАНИЦЫ ВАЖНЕЕ СЕРЕДИНЫ. Оба сравнения в правиле строгие, то есть ровно
 * пороговое значение ПРОХОДИТ. Замена `<` на `<=` или `>` на `>=` меняет
 * правило, не меняя ни одного числа, и без проверок ровно на пороге такая
 * правка прошла бы незамеченной.
 *
 * ЧЕГО ЭТОТ ТЕСТ НЕ ПОКРЫВАЕТ, сознательно. Он ничего не говорит о том,
 * правильно ли собран matched: за это отвечает лестница префиксов в
 * searchByWords, а она ходит в базу и здесь недосягаема. Не говорит он и о
 * судьбе записи, которую поиск потерял вовсе, — а это как раз промах в
 * невосстановимую сторону, и ловить его придётся другим тестом, с подставным
 * DAO.
 */
class SelectionRuleTest {

    /**
     * Правка порога должна быть заметной.
     *
     * Этот тест не утверждает, что 0.5 и 20.0 — верные числа: верных чисел здесь
     * пока никто не знает, оба выведены на базе в 13 записей. Он утверждает
     * только то, что молча их подвинуть нельзя. Красный тест после
     * перекалибровки — не поломка, а требование прийти сюда и переписать
     * ожидания осознанно.
     */
    @Test
    fun `пороги закреплены и не двигаются молча`() {
        assertEquals(0.5, MIN_COVERAGE, 0.0)
        assertEquals(20.0, MAX_CHARS_PER_MATCH, 0.0)
    }

    // --- Граница проверки 1: доля слов вопроса ---

    /**
     * Ровно половина слов вопроса — проходит. Это записано и в KDoc порога:
     * два слова из четырёх уже означают разговор об одном и том же.
     */
    @Test
    fun `ровно половина слов вопроса проходит`() {
        val score = scoreCandidate(
            matched = mapOf("рубанок" to 7, "дерево" to 6),
            totalWords = 4,
            contentLength = 100
        )
        assertEquals(0.5, score.coverage, 1e-9)
        assertFalse(score.tooNarrow)
        assertTrue(score.passed)
        assertEquals("взято", score.verdict)
    }

    /** Чуть ниже половины — отсев. Соседняя точка к предыдущей проверке. */
    @Test
    fun `меньше половины слов вопроса отсеивается как узкое`() {
        val score = scoreCandidate(
            matched = mapOf("рубанок" to 7, "дерево" to 6),
            totalWords = 5,
            contentLength = 100
        )
        assertEquals(0.4, score.coverage, 1e-9)
        assertTrue(score.tooNarrow)
        assertFalse(score.passed)
        assertEquals("отсев: узко", score.verdict)
    }

    // --- Граница проверки 2: цена места ---

    /** Ровно пороговая цена места проходит: 140 знаков на 7 совпавших — это 20.0. */
    @Test
    fun `ровно пороговая цена места проходит`() {
        val score = scoreCandidate(
            matched = mapOf("рубанок" to 7),
            totalWords = 1,
            contentLength = 140
        )
        assertEquals(20.0, score.charsPerMatch, 1e-9)
        assertFalse(score.tooExpensive)
        assertTrue(score.passed)
    }

    /** Один лишний знак объёма переводит запись за порог. */
    @Test
    fun `на знак дороже порога уже отсев`() {
        val score = scoreCandidate(
            matched = mapOf("рубанок" to 7),
            totalWords = 1,
            contentLength = 141
        )
        assertTrue(score.charsPerMatch > 20.0)
        assertTrue(score.tooExpensive)
        assertFalse(score.passed)
        assertEquals("отсев: дорого", score.verdict)
    }

    // --- Вердикт: то, что увидит человек ---

    /**
     * Оба признака сразу называются оба. Строка вердикта — единственное, по чему
     * решение об отсеве читается снаружи, и она обязана различать три причины, а
     * не сводить их к общему "не подошло".
     */
    @Test
    fun `узко и дорого одновременно называются оба`() {
        val score = scoreCandidate(
            matched = mapOf("дерево" to 5),
            totalWords = 4,
            contentLength = 200
        )
        assertTrue(score.tooNarrow)
        assertTrue(score.tooExpensive)
        assertEquals("отсев: узко и дорого", score.verdict)
    }

    // --- Меры ---

    /** Совпадение целым словом даёт точность 1.0, укороченное — меньше. */
    @Test
    fun `точность падает от укороченного совпадения`() {
        val whole = scoreCandidate(mapOf("рубанок" to 7), totalWords = 1, contentLength = 50)
        val cut = scoreCandidate(mapOf("рубанок" to 5), totalWords = 1, contentLength = 50)
        assertEquals(1.0, whole.precision, 1e-9)
        assertEquals(5.0 / 7.0, cut.precision, 1e-9)
        assertTrue(cut.precision < whole.precision)
    }

    /**
     * Цена места считается по РЕАЛЬНО совпавшим знакам, а не по длине исходных
     * слов. Иначе размытая ступень покупала бы место в контексте даром: слово
     * нашлось четырьмя буквами, а платило бы как за десять.
     */
    @Test
    fun `цена места считается по совпавшим знакам`() {
        val precise = scoreCandidate(mapOf("рубанок" to 7), totalWords = 1, contentLength = 140)
        val vague = scoreCandidate(mapOf("рубанок" to 4), totalWords = 1, contentLength = 140)
        assertEquals(20.0, precise.charsPerMatch, 1e-9)
        assertEquals(35.0, vague.charsPerMatch, 1e-9)
        assertTrue(vague.tooExpensive)
    }

    // --- Проверки на молчание: чего правило НЕ делает ---

    /**
     * Пустой matched даёт отсев по обоим признакам, а не деление на ноль и не
     * случайное прохождение. В отборе такого кандидата не бывает, но правило
     * определено на всём своём входе и не полагается на проверку вызывающего.
     */
    @Test
    fun `пустое совпадение отсеивается по обоим признакам`() {
        val score = scoreCandidate(emptyMap(), totalWords = 3, contentLength = 50)
        assertEquals(0, score.matchedWords)
        assertEquals(0.0, score.coverage, 1e-9)
        assertEquals(0.0, score.precision, 1e-9)
        assertTrue(score.tooNarrow)
        assertTrue(score.tooExpensive)
        assertFalse(score.passed)
    }

    /**
     * Правило не читает смысл слов — только их длину и ступень, на которой они
     * нашлись. Два разных вопроса с одинаковой геометрией совпадений дают
     * одинаковые меры.
     *
     * Проверка на молчание: она закрепляет, что отбор стоп-слов, важность записи
     * и её слой сюда не заглядывают. Если однажды кто-нибудь решит учесть здесь
     * "значимость" слова, этот тест покраснеет и потребует объяснения.
     */
    @Test
    fun `правило не смотрит на сами слова`() {
        val a = scoreCandidate(mapOf("рубанок" to 5), totalWords = 2, contentLength = 60)
        val b = scoreCandidate(mapOf("колодка" to 5), totalWords = 2, contentLength = 60)
        assertEquals(a.coverage, b.coverage, 1e-9)
        assertEquals(a.precision, b.precision, 1e-9)
        assertEquals(a.charsPerMatch, b.charsPerMatch, 1e-9)
        assertEquals(a.verdict, b.verdict)
    }

    // --- Итог отбора: то, что читает человек ---

    /**
     * Пять состояний — пять разных текстов.
     *
     * Это и есть довод в пользу запечатанного типа: при счёте одними числами
     * "искать было не по чему" и "искали, не нашли" дали бы одинаковые нули, а
     * лечатся они противоположным. Тест ловит слияние любых двух состояний в
     * одну формулировку.
     */
    @Test
    fun `пять состояний отбора называются по-разному`() {
        val texts = listOf(
            SelectionSummary.NoQuery.text,
            SelectionSummary.NoRoom.text,
            SelectionSummary.NoSearchableWords.text,
            SelectionSummary.NothingFound(words = 4, hidden = 0).text,
            SelectionSummary.Weighed(
                words = 4, candidates = 12, passed = 3, shown = 3,
                tooNarrow = 7, tooExpensive = 2, tooBoth = 0, hidden = 0
            ).text
        )
        assertEquals(5, texts.toSet().size)
        assertTrue(texts.none { it.isBlank() })
    }

    /**
     * Потеря по лимиту названа отдельно от отсева порогом.
     *
     * Из трёх способов не дойти до экрана этот единственный, о котором иначе
     * нельзя было бы узнать: со стороны обрезанная лимитом запись выглядит точно
     * так же, как отсеянная, а лечится противоположным — лимитом, а не порогом.
     */
    @Test
    fun `обрезка лимитом названа отдельно от отсева`() {
        val summary = SelectionSummary.Weighed(
            words = 4, candidates = 12, passed = 5, shown = 3,
            tooNarrow = 6, tooExpensive = 1, tooBoth = 0, hidden = 0
        )
        assertEquals(2, summary.trimmed)
        assertTrue(summary.text.contains("лимит отрезал 2"))
        assertTrue(summary.text.contains("прошло 5"))
        assertTrue(summary.text.contains("показано 3"))
    }

    /** Когда лимит ничего не отрезал, про него не говорится вовсе. */
    @Test
    fun `без обрезки про лимит не упоминается`() {
        val summary = SelectionSummary.Weighed(
            words = 3, candidates = 4, passed = 2, shown = 2,
            tooNarrow = 2, tooExpensive = 0, tooBoth = 0, hidden = 0
        )
        assertEquals(0, summary.trimmed)
        assertFalse(summary.text.contains("лимит"))
    }

    /** Причины отсева перечисляются поимённо, а не сводятся к общему числу. */
    @Test
    fun `причины отсева названы поимённо`() {
        val summary = SelectionSummary.Weighed(
            words = 4, candidates = 10, passed = 1, shown = 1,
            tooNarrow = 5, tooExpensive = 3, tooBoth = 1, hidden = 0
        )
        assertEquals(9, summary.rejected)
        assertTrue(summary.text.contains("отсеяно 9"))
        assertTrue(summary.text.contains("узко 5"))
        assertTrue(summary.text.contains("дорого 3"))
        assertTrue(summary.text.contains("узко и дорого 1"))
    }

    /**
     * Когда не отсеяли никого, это говорится прямо, а не молчанием: строка
     * должна отвечать на вопрос об отсеве при любом исходе.
     */
    @Test
    fun `нулевой отсев назван прямо`() {
        val summary = SelectionSummary.Weighed(
            words = 2, candidates = 3, passed = 3, shown = 3,
            tooNarrow = 0, tooExpensive = 0, tooBoth = 0, hidden = 0
        )
        assertEquals(0, summary.rejected)
        assertTrue(summary.text.contains("отсеяно 0"))
    }

    // --- Карантин: записи, до отбора не доехавшие ---

    /**
     * Скрытые названы и тогда, когда не нашлось ничего. Это главный случай, ради
     * которого счётчик заведён: все три запроса пути чтения начинаются с
     * reviewPending = 0, поэтому запись в карантине выглядит на экране как
     * отсутствующая в памяти. Лечится это противоположным — не порогами, а
     * разбором очереди.
     */
    @Test
    fun `скрытые названы даже когда не нашлось ничего`() {
        val summary = SelectionSummary.NothingFound(words = 3, hidden = 2)
        assertTrue(summary.text.contains("не нашлось ни одной записи"))
        assertTrue(summary.text.contains("скрыто карантином 2"))
    }

    /**
     * Пустой отбор с карантином и без него — разные строки. Тест ловит ровно то
     * слияние, против которого счётчик и сделан: два разных положения дел не
     * должны выглядеть на экране одинаково.
     */
    @Test
    fun `пустой отбор с карантином и без него называется по-разному`() {
        assertNotEquals(
            SelectionSummary.NothingFound(words = 3, hidden = 0).text,
            SelectionSummary.NothingFound(words = 3, hidden = 2).text
        )
    }

    /** Непустой карантин виден и во взвешенном итоге, не только в пустом. */
    @Test
    fun `скрытые названы во взвешенном итоге`() {
        val summary = SelectionSummary.Weighed(
            words = 4, candidates = 6, passed = 2, shown = 2,
            tooNarrow = 4, tooExpensive = 0, tooBoth = 0, hidden = 3
        )
        assertTrue(summary.text.contains("скрыто карантином 3"))
    }

    /**
     * Ноль скрытых говорится прямо, в обоих состояниях, где поле есть.
     *
     * Проверка на молчание, и сейчас она важнее прочих: очередь карантина пуста,
     * значит ноль — единственное показание, которое счётчик вообще выдаёт на
     * живой базе. Молчал бы он при нуле — был бы неотличим от неподключённого.
     */
    @Test
    fun `нулевой карантин назван прямо в обоих состояниях`() {
        assertTrue(
            SelectionSummary.NothingFound(words = 3, hidden = 0)
                .text.contains("скрыто карантином 0")
        )
        assertTrue(
            SelectionSummary.Weighed(
                words = 2, candidates = 3, passed = 3, shown = 3,
                tooNarrow = 0, tooExpensive = 0, tooBoth = 0, hidden = 0
            ).text.contains("скрыто карантином 0")
        )
    }
}
