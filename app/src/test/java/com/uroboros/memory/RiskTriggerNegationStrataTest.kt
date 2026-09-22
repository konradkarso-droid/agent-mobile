package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Два слоя признака отрицания: точный остаток (спор без вариантов) и
 * разошедшийся. Что это значит и чего не умеет — в KDoc
 * RiskTrigger.ContradictionMark.
 *
 * Половина проверок — на МОЛЧАНИЕ: где точного остатка быть не должно. Ложная
 * пара из разложения связки обязана остаться во втором слое; если её
 * «починят» в первый, первый перестанет значить «без вариантов».
 */
class RiskTriggerNegationStrataTest {

    private fun negation(a: String, b: String): RiskTrigger.ContradictionMark {
        val marks = RiskTrigger.contradictionMarks(a, b)
        return marks.single { it.kind == RiskTrigger.MarkKind.NEGATION }
    }

    @Test
    fun `одно и то же с не и без — точный остаток`() {
        assertTrue(negation("Я работаю по субботам", "Я не работаю по субботам").exactRemainder)
    }

    @Test
    fun `регистр и знаки препинания остатку не мешают`() {
        assertTrue(
            negation("Меркурий светит алым у полудня.", "меркурий НЕ светит алым у полудня")
                .exactRemainder
        )
    }

    @Test
    fun `несколько слов отрицания снимаются все`() {
        assertTrue(negation("Меркурий никогда не светит алым", "Меркурий светит алым").exactRemainder)
        assertTrue(negation("Купить станок невозможно", "Купить станок").exactRemainder)
    }

    @Test
    fun `слой не зависит от порядка сторон`() {
        val forward = negation("Я работаю по субботам", "Я не работаю по субботам")
        val backward = negation("Я не работаю по субботам", "Я работаю по субботам")
        assertEquals(forward.exactRemainder, backward.exactRemainder)
    }

    @Test
    fun `связка даёт точный остаток по своей отрицающей части`() {
        assertTrue(
            negation("Мой любимый инструмент — не рубанок, а стамеска", "Мой любимый инструмент — рубанок")
                .exactRemainder
        )
    }

    @Test
    fun `остаток разошёлся — второй слой`() {
        assertFalse(
            negation("Мой любимый инструмент рубанок с деревянной колодкой", "Мой любимый инструмент не рубанок")
                .exactRemainder
        )
    }

    @Test
    fun `ложный спор из разложения связки остаётся во втором слое`() {
        // Фразы согласны, правило видит спор (описано у clausesOf). Точным
        // остатком такая пара стать не должна.
        assertFalse(
            negation("Мой любимый инструмент — не рубанок, а стамеска", "Мой любимый инструмент — стамеска")
                .exactRemainder
        )
    }

    @Test
    fun `обороты с лишним словом не попадают в точный слой`() {
        assertFalse(negation("Не зря Меркурий светит алым", "Меркурий светит алым").exactRemainder)
    }

    @Test
    fun `двойное отрицание попадает в точный слой — известная граница`() {
        // «Не могу не работать» значит «обязан», и спора с «могу работать» нет.
        // Признак считает наличие отрицания, а не смысл. Тест закрепляет
        // границу: если она однажды исчезнет, это должно быть видно.
        assertTrue(
            negation("Я не могу не работать по субботам", "Я могу работать по субботам").exactRemainder
        )
    }

    @Test
    fun `у других признаков слоя нет`() {
        val marks = RiskTrigger.contradictionMarks("Ставка по вкладу 7 процентов", "Ставка по вкладу 8 процентов")
        val number = marks.single { it.kind == RiskTrigger.MarkKind.NUMBER }
        assertFalse(number.exactRemainder)
    }
}
