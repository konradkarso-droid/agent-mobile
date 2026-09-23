package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строки сборки «о себе».
 *
 * ГЛАВНОЕ ЗДЕСЬ: строка о памяти стоит всегда и первой — без неё модель на
 * ходе без записей говорит о себе «памяти нет»; строка о запрете иероглифов —
 * только при включённом запрете, иначе это ложь о себе.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что модель этим строкам верит. Это видно только
 * по ответам на устройстве.
 */
class BuildSelfDescriptionTest {

    @Test
    fun `память первой при любом состоянии запрета`() {
        for (state in listOf(-1, 0, 1, 2, 3)) {
            assertEquals(BuildSelfDescription.MEMORY_LINE, BuildSelfDescription.lines(state).first())
        }
    }

    @Test
    fun `запрет иероглифов — только когда включён`() {
        assertTrue(BuildSelfDescription.SCRIPT_BAN_LINE in BuildSelfDescription.lines(1))
        for (state in listOf(-1, 0, 2, 3)) {
            assertFalse(BuildSelfDescription.SCRIPT_BAN_LINE in BuildSelfDescription.lines(state))
        }
    }

    @Test
    fun `стена — текст человека, за ним строки сборки`() {
        assertEquals("стена\nа\nб", BuildSelfDescription.compose("стена", listOf("а", "б")))
        assertEquals("стена", BuildSelfDescription.compose("стена", emptyList()))
    }
}
