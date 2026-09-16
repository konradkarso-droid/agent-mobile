package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разложение фразы с противительной связкой в правиле противоречия.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ ФАЙЛ. Сломанное разложение не падает: фраза, которую
 * шаблон не взял, сравнивается целиком, как до разложения, и прежние тесты
 * остаются зелёными. Видно это только здесь — на парах, где спор находится
 * ТОЛЬКО по частям. Целиком фразы из этих пар до сравнения не доходят,
 * схожесть ниже порога, так что каждый тест «спорят» ниже падает, если
 * разложение молчит.
 *
 * Фразы взяты из живой базы на устройстве, а не подобраны под шаблоны.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ: что найденное разложением — настоящий спор.
 * Известная ложная тревога закреплена ниже как граница, а не как цель.
 */
class RiskTriggerClausesTest {

    private val woodenPlane = "Мой любимый инструмент — рубанок с деревянной колодкой"
    private val notPlaneButChisel = "Мой любимый инструмент — не рубанок, а стамеска."
    private val sawNotPlane = "Мой любимый инструмент — ножовка, а не рубанок"

    @Test
    fun `построение «не Y, а Z» раскладывается`() {
        assertTrue(RiskTrigger.contradicts(woodenPlane, notPlaneButChisel))

        val marks = RiskTrigger.contradictionMarks(woodenPlane, notPlaneButChisel)
        assertEquals(listOf(RiskTrigger.MarkKind.NEGATION), marks.map { it.kind })
        assertTrue(marks[0].first.isEmpty())
        assertEquals(setOf("не"), marks[0].second)
    }

    @Test
    fun `построение «Z, а не Y» раскладывается`() {
        assertTrue(RiskTrigger.contradicts(woodenPlane, sawNotPlane))
    }

    @Test
    fun `регистр отрицания и связки не мешает`() {
        assertTrue(
            RiskTrigger.contradicts(woodenPlane, "Мой любимый инструмент — НЕ рубанок, А стамеска")
        )
    }

    @Test
    fun `без запятой перед «а» связка не раскладывается`() {
        // Закреплённая граница, а не цель. Если тест упал, шаблон стал брать
        // больше — надо решить, что это верно, и переписать KDoc у clausesOf.
        assertFalse(
            RiskTrigger.contradicts(woodenPlane, "Мой любимый инструмент — не рубанок а стамеска")
        )
    }

    @Test
    fun `известная ложная - отрицается одно, утверждается другое`() {
        // Спора нет: обе стороны отрицают рубанок. Правило видит часть
        // «…— стамеска» против «…— не рубанок» и засчитывает отрицание. Тест
        // упадёт, когда этот класс починят, — и починка будет видна.
        assertTrue(
            RiskTrigger.contradicts(notPlaneButChisel, "Мой любимый инструмент — не рубанок")
        )
    }

    @Test
    fun `обе фразы разложены - отрицание названо у обеих, и стороны меняются вместе с текстами`() {
        val straight = RiskTrigger.contradictionMarks(notPlaneButChisel, sawNotPlane)
        val reversed = RiskTrigger.contradictionMarks(sawNotPlane, notPlaneButChisel)

        assertEquals(listOf(RiskTrigger.MarkKind.NEGATION), straight.map { it.kind })
        assertEquals(setOf("не"), straight[0].first)
        assertEquals(setOf("не"), straight[0].second)
        assertEquals(straight.map { it.kind }, reversed.map { it.kind })
        assertEquals(straight[0].first, reversed[0].second)
        assertEquals(straight[0].second, reversed[0].first)
    }
}
