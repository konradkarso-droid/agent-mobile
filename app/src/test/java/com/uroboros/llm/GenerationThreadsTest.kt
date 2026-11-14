package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/** Выбор потоков на выдачу (GenerationThreads): перебор и строка экрана. */
class GenerationThreadsTest {

    @Test
    fun `перебор обходит все выборы и возвращается к движку`() {
        var n = 0
        val seen = mutableListOf<Int>()
        repeat(GenerationThreads.CHOICES.size) { seen += n; n = GenerationThreads.next(n) }
        assertEquals(GenerationThreads.CHOICES, seen)
        assertEquals("после последнего — снова движок", 0, n)
    }

    @Test
    fun `незнакомое число ведёт к выбору движка`() {
        assertEquals(0, GenerationThreads.next(13))
    }

    @Test
    fun `строка - на деле и что выбрано`() {
        assertEquals("Потоков на выдачу: 3 (как решил движок)", GenerationThreads.line(0, 3))
        assertEquals("Потоков на выдачу: 5 (задано 5)", GenerationThreads.line(5, 5))
        assertEquals("ядер меньше заданного — видно расхождение", "Потоков на выдачу: 8 (задано 9)", GenerationThreads.line(9, 8))
        assertEquals("до загрузки модели числа нет", "Потоков на выдачу: ? (задано 4)", GenerationThreads.line(4, 0))
    }
}
