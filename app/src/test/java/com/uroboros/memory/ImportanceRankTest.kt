package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Проверка на молчание для правила «важность → место в выдаче».
 *
 * Правило записано в проекте дважды, и свести копии нельзя: одна живёт в
 * importanceRank (HourglassMemory.kt), вторая — строкой SQL в @Query у
 * StickerDao.getRanked, а SQL не может позвать функцию Kotlin. Этот тест —
 * единственное, что удерживает копии вместе.
 *
 * Числа ниже намеренно записаны литералами, а не вычислены из самого Importance.
 * Тест, добывающий ordinal тем же способом, что и проверяемый код, согласился бы
 * с любой перестановкой значений и не поймал бы ничего. Литералы здесь — копия
 * того, что стоит в CASE, и в этом весь смысл: разъедется enum — тест упадёт.
 *
 * Чего тест НЕ умеет: он не читает саму строку запроса и не может убедиться, что
 * в CASE стоят именно эти числа. Он ловит изменение enum, а не опечатку в SQL.
 * Опечатку в SQL ловит только чтение глазами.
 */
class ImportanceRankTest {

    @Test
    fun `порядок значений Importance тот же, что зашит в CASE у getRanked`() {
        assertEquals(listOf("LOW", "MEDIUM", "HIGH"), Importance.values().map { it.name })
        assertEquals(0, Importance.LOW.ordinal)
        assertEquals(1, Importance.MEDIUM.ordinal)
        assertEquals(2, Importance.HIGH.ordinal)
    }

    @Test
    fun `известные значения получают свой ранг`() {
        assertEquals(0, importanceRank("LOW"))
        assertEquals(1, importanceRank("MEDIUM"))
        assertEquals(2, importanceRank("HIGH"))
    }

    @Test
    fun `неизвестное значение получает ранг LOW, а не середину`() {
        assertEquals(0, importanceRank("ОЧЕНЬ ВАЖНО"))
        assertEquals(0, importanceRank(""))
    }

    /**
     * Регистр не приводится намеренно: valueOf чувствителен к регистру, и "medium"
     * из чужой базы — такое же неизвестное значение, как любая другая строка.
     * Строка закрепляет это как решение, а не как случайность реализации.
     */
    @Test
    fun `значение в другом регистре считается неизвестным`() {
        assertEquals(0, importanceRank("medium"))
    }
}
