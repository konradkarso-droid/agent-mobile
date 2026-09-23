package com.uroboros.memory.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Сито судьи. Примеры — из испытательной базы: пара настоящего спора, пара
 * одной темы без спора, пара без общих слов.
 */
class JudgeSieveTest {

    @Test
    fun `настоящий спор — внутреннее кольцо`() {
        assertEquals(
            JudgeRing.INNER,
            JudgeSieve.ring("Меркурий светит белым на закате.", "Меркурий не светит белым на закате."),
        )
    }

    @Test
    fun `короткую запись длинная не топит — доля считается от меньшей`() {
        // Жаккар здесь 0,25 и пару бы потерял; доля от меньшей — 0,5.
        assertEquals(
            JudgeRing.INNER,
            JudgeSieve.ring("Меркурий светит белым на закате.", "Меркурий светит красным за заходе"),
        )
    }

    @Test
    fun `одна тема без спора — внешнее кольцо, но судится`() {
        assertEquals(
            JudgeRing.OUTER,
            JudgeSieve.ring("Алеет солнце на закате.", "Меркурий светит белым на закате."),
        )
    }

    @Test
    fun `без общих значимых слов пара не судится`() {
        assertNull(JudgeSieve.ring("Чёрный чай заваривают кипятком", "Бетономешалка мешает бетон"))
    }

    @Test
    fun `общие служебные слова пару не сводят`() {
        // «на» и «по» — не значимые слова; сводить пару они не должны.
        assertNull(JudgeSieve.ring("Я работаю по субботам", "Вода кипит при 300 градусах"))
    }

    @Test
    fun `кольцо не зависит от порядка записей`() {
        val a = "Мой любимый инструмент — рубанок с деревянной колодкой"
        val b = "Рубанок - не мой любимый инструмент."
        assertEquals(JudgeSieve.ring(a, b), JudgeSieve.ring(b, a))
    }

    @Test
    fun `ровно половина — внутреннее кольцо`() {
        assertEquals(JudgeRing.INNER, JudgeSieve.ring(setOf("а", "б"), setOf("а", "в", "г")))
        assertEquals(JudgeRing.OUTER, JudgeSieve.ring(setOf("а", "б", "в"), setOf("а", "г", "д")))
    }

    @Test
    fun `пустая запись ни с кем не судится`() {
        assertNull(JudgeSieve.ring(emptySet(), setOf("а")))
    }
}
