package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки [DisputeNotice].
 *
 * ЧТО ЗДЕСЬ ЗАКРЕПЛЯЕТСЯ, КРОМЕ РАБОТЫ МЕХАНИЗМА. Три проверки стоят на
 * МОЛЧАНИЕ: непротиворечивые записи, одна запись, пробельная строка. Без них
 * «механизм молчит, потому что ловить нечего» и «механизм молчит, потому что
 * сломан» неразличимы, и случайная починка, начавшая находить расхождения
 * везде, прошла бы зелёной.
 *
 * ТЕКСТЫ ВЗЯТЫ С ЖИВОГО МАТЕРИАЛА, а не выдуманы: пара про рубанок — та самая,
 * на которой наблюдался спор в памяти. Правило сравнивает пару только после
 * порога схожести, поэтому пары здесь различаются одним словом или одним
 * числом: придуманные «разные» фразы до сравнения просто не доходят, и тест
 * зеленел бы, ничего не проверяя.
 */
class DisputeNoticeTest {

    private val instrument = "Мой любимый инструмент — рубанок с деревянной колодкой"
    private val instrumentDenied = "Мой любимый инструмент — не рубанок с деревянной колодкой"
    private val rate5 = "Ставка по договору 5 процентов годовых"
    private val rate6 = "Ставка по договору 6 процентов годовых"
    private val rate7 = "Ставка по договору 7 процентов годовых"
    private val rate9 = "Ставка по договору 9 процентов годовых"

    @Test
    fun `пустой список — сверять нечего`() {
        assertEquals(DisputeNotice.Result.NothingToCompare, DisputeNotice.of(emptyList()))
    }

    @Test
    fun `одна запись — сверять нечего`() {
        assertEquals(
            DisputeNotice.Result.NothingToCompare,
            DisputeNotice.of(listOf(instrument)),
        )
    }

    @Test
    fun `пробельная строка за запись не считается`() {
        assertEquals(
            DisputeNotice.Result.NothingToCompare,
            DisputeNotice.of(listOf(instrument, "   ")),
        )
    }

    @Test
    fun `молчание — записи без расхождения дают чистый исход`() {
        val result = DisputeNotice.of(listOf(instrument, rate5))
        assertEquals(DisputeNotice.Result.Clean, result)
    }

    @Test
    fun `отрицание названо именем признака`() {
        val result = DisputeNotice.of(listOf(instrument, instrumentDenied))
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        assertEquals(1, result.pairsFound)
        assertEquals(1, result.pairsShown)
        assertTrue(result.text.contains("отрицание"))
        // Числа в этой паре не расходятся, и признак числа назваться не должен.
        assertTrue(!result.text.contains("числа разошлись"))
    }

    @Test
    fun `числа названы своим признаком`() {
        val result = DisputeNotice.of(listOf(rate5, rate9))
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        assertTrue(result.text.contains("числа разошлись"))
    }

    @Test
    fun `граница механизма стоит последней строкой и один раз`() {
        val result = DisputeNotice.of(listOf(rate5, rate6, rate7))
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        val lines = result.text.split("\n")
        assertEquals(DisputeNotice.LIMIT_LINE, lines.last())
        assertEquals(1, lines.count { it == DisputeNotice.LIMIT_LINE })
    }

    @Test
    fun `при обрезке названо, сколько пар осталось за потолком`() {
        // Четыре записи, различающиеся только числом, дают шесть пар, и все
        // шесть расходятся. Названы должны быть три.
        val result = DisputeNotice.of(listOf(rate5, rate6, rate7, rate9))
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        assertEquals(6, result.pairsFound)
        assertEquals(DisputeNotice.MAX_PAIRS, result.pairsShown)
        assertTrue(result.text.contains("Названы первые 3 расхождения из 6."))
    }

    @Test
    fun `без обрезки о потолке не говорится`() {
        val result = DisputeNotice.of(listOf(rate5, rate9))
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        assertTrue(!result.text.contains("Названы первые"))
    }

    @Test
    fun `длинная запись цитируется началом`() {
        val result = DisputeNotice.of(listOf(instrument, instrumentDenied))
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        // Обе записи длиннее черты, значит обе стоят в тексте обрезанными.
        assertTrue(result.text.contains(instrument.take(DisputeNotice.QUOTE_CHARS) + "…"))
        assertTrue(result.text.contains(instrumentDenied.take(DisputeNotice.QUOTE_CHARS) + "…"))
    }

    @Test
    fun `перенос строки внутри записи не ломает построчность блока`() {
        val result = DisputeNotice.of(
            listOf("Ставка по договору\n5 процентов годовых", rate9),
        )
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        // Одна пара — значит ровно две строки: строка о паре и граница.
        assertEquals(2, result.text.split("\n").size)
    }
}
