package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Границы признаков спора — того, что правило противоречия называет помимо
 * ответа «да».
 *
 * ЗАЧЕМ ОНИ ЕСТЬ. На признаках стоит подсветка в очереди: экран закрашивает в
 * цитате ровно те слова, что названы здесь. Ошибка не падает и в логе не
 * видна — закрашенное не то слово выглядит убедительнее, чем незакрашенное
 * ничего, и человек нажимает «убрать» по ложной наводке. Сторож у этого один,
 * и это данный файл.
 *
 * САМЫЙ ВАЖНЫЙ ЗДЕСЬ — ТЕСТ ПРО ИНВАРИАНТ. Экран опирается на то, что у пары,
 * признанной спорящей, признак всегда есть хотя бы один, и печатает «признаков
 * нет, и это поломка», когда их нет. Разойдись ответ «да» с пустым списком —
 * и очередь начнёт ругаться на исправный механизм. Добавляя правилу новое
 * слагаемое, надо добавлять и признак к нему; именно это здесь и стережётся.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ:
 *  - что пары, признанные спорящими, спорят на самом деле. Правило грубое
 *    намеренно, и мерка у него своя;
 *  - что заливка легла на экран. Признаки — это слова, а не места; поиск
 *    вхождения и рисование живут в MainActivity и сюда не доезжают;
 *  - что порог схожести выбран верно. Пары ниже него сюда просто не доходят.
 */
class RiskTriggerMarksTest {

    // Пара наблюдалась на устройстве: обе записи лежали в одном деле, и
    // правило признало их спорящими. Числа порога здесь ни при чём — пара
    // взята из живых данных, а не подобрана под него.
    private val withNegation = "Мой любимый инструмент — не рубанок"
    private val withoutNegation = "Мой любимый инструмент — рубанок с деревянной колодкой"

    @Test
    fun `спор признан тогда и только тогда, когда назван хотя бы один признак`() {
        // Обе половины инварианта, на котором стоит показ. Первая — что «да»
        // не бывает без признака; вторая — что признак не появляется там, где
        // спора нет.
        assertTrue(RiskTrigger.contradicts(withNegation, withoutNegation))
        assertTrue(
            "сказав «спорят», правило обязано назвать, за что зацепилось",
            RiskTrigger.contradictionMarks(withNegation, withoutNegation).isNotEmpty()
        )

        val unrelated = "Сегодня с утра шёл дождь"
        assertTrue(!RiskTrigger.contradicts(withNegation, unrelated))
        assertEquals(
            "у непохожих текстов признаков нет",
            emptyList<RiskTrigger.ContradictionMark>(),
            RiskTrigger.contradictionMarks(withNegation, unrelated)
        )
    }

    @Test
    fun `отрицание названо только с той стороны, где оно стоит`() {
        // Признак в том и состоит, что у второй стороны отрицания НЕТ.
        // Пустая сторона здесь — не пробел, а само содержание признака, и
        // экран на это опирается: чистая цитата противника законна.
        val marks = RiskTrigger.contradictionMarks(withNegation, withoutNegation)

        assertEquals(1, marks.size)
        assertEquals(RiskTrigger.MarkKind.NEGATION, marks[0].kind)
        assertEquals(setOf("не"), marks[0].first)
        assertTrue("выделять на второй стороне нечего", marks[0].second.isEmpty())
    }

    @Test
    fun `числительное словом возвращается словом, а не цифрой`() {
        // Сравниваются числа цифровой записью, иначе «семь» и «7» считались бы
        // разными. Но выделять в тексте надо написанное: искать там «7», когда
        // стоит «семь», не по чему.
        val marks = RiskTrigger.contradictionMarks(
            "Ставка по вкладу семь процентов годовых",
            "Ставка по вкладу 8 процентов годовых",
        )

        assertEquals(1, marks.size)
        assertEquals(RiskTrigger.MarkKind.NUMBER, marks[0].kind)
        assertEquals(setOf("семь"), marks[0].first)
        assertEquals(setOf("8"), marks[0].second)
    }

    @Test
    fun `названы только разошедшиеся числа, общее молчит`() {
        // Числа одной стороны — подмножество чисел другой. Спор засчитан,
        // сторона с недостачей чиста, и это законно: выделять там нечего.
        val marks = RiskTrigger.contradictionMarks(
            "В квартире 3 окна",
            "В квартире 3 окна и 2 двери",
        )

        assertEquals(1, marks.size)
        assertEquals(RiskTrigger.MarkKind.NUMBER, marks[0].kind)
        assertTrue("общее число не называется", marks[0].first.isEmpty())
        assertEquals(setOf("2"), marks[0].second)
    }

    @Test
    fun `порядок признаков устойчив - отрицание раньше числа`() {
        // Экран печатает признаки подряд, и переставляться от запуска к
        // запуску они не должны.
        val marks = RiskTrigger.contradictionMarks(
            "В нашем старом доме не 7 окон",
            "В нашем старом доме 8 окон",
        )

        assertEquals(2, marks.size)
        assertEquals(
            listOf(RiskTrigger.MarkKind.NEGATION, RiskTrigger.MarkKind.NUMBER),
            marks.map { it.kind }
        )
    }

    @Test
    fun `стороны меняются местами вместе с текстами`() {
        // На симметрии правила стоит сборка дел: связь A-B берётся из отчёта
        // любой из сторон. Здесь проверяется, что и признаки переворачиваются
        // вместе с ней, а не остаются приклеенными к первому аргументу.
        val straight = RiskTrigger.contradictionMarks(withNegation, withoutNegation)
        val reversed = RiskTrigger.contradictionMarks(withoutNegation, withNegation)

        assertEquals(straight.map { it.kind }, reversed.map { it.kind })
        assertEquals(straight[0].first, reversed[0].second)
        assertEquals(straight[0].second, reversed[0].first)
    }
}
