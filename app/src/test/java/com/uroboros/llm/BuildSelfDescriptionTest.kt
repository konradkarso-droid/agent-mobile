package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `порядок частей — человек, сборка, нажитое, проверочная в конце`() {
        assertEquals(
            "стена\nа\nб\nнажито\nпроба",
            BuildSelfDescription.compose("стена", listOf("а", "б"), listOf("нажито"), "проба"),
        )
        val withProbe = BuildSelfDescription.compose("стена", listOf("а"), probe = BuildSelfDescription.PROBE_LINE)
        assertTrue(withProbe.endsWith("\n" + BuildSelfDescription.PROBE_LINE))
    }

    @Test
    fun `пустые части не дают пустых строк`() {
        assertEquals("стена", BuildSelfDescription.compose("стена", emptyList(), emptyList(), null))
        assertEquals("стена\nпроба", BuildSelfDescription.compose("стена", emptyList(), listOf(""), "проба"))
        assertFalse(BuildSelfDescription.compose("стена", listOf("а"), listOf("б"), null).contains("\n\n"))
    }

    @Test
    fun `разница строк — добавлено`() {
        val d = BuildSelfDescription.diff("стена\nа", "стена\nа\nпроба")
        assertEquals(listOf("проба"), d.added)
        assertEquals(emptyList<String>(), d.removed)
        assertEquals(
            "Стена сменилась на лету перед ходом 3: добавлено «проба»",
            BuildSelfDescription.changeLine(3, "стена\nа", "стена\nа\nпроба"),
        )
    }

    @Test
    fun `разница строк — убрано`() {
        val d = BuildSelfDescription.diff("стена\nа\nпроба", "стена\nа")
        assertEquals(emptyList<String>(), d.added)
        assertEquals(listOf("проба"), d.removed)
        assertEquals(
            "Стена сменилась на лету перед ходом 4: убрано «проба»",
            BuildSelfDescription.changeLine(4, "стена\nа\nпроба", "стена\nа"),
        )
    }

    @Test
    fun `разница строк — ничего`() {
        val d = BuildSelfDescription.diff("стена\nа", "стена\nа")
        assertTrue(d.added.isEmpty() && d.removed.isEmpty())
        assertEquals(
            "Стена сменилась на лету перед ходом 1: строки те же, сменился порядок",
            BuildSelfDescription.changeLine(1, "стена\nа\nб", "стена\nб\nа"),
        )
    }

    @Test
    fun `та же стена ничего не ожидает`() {
        assertNull(BuildSelfDescription.pendingAfter("стена", "стена"))
    }

    @Test
    fun `другая стена ожидает`() {
        assertEquals("стена\nпроба", BuildSelfDescription.pendingAfter("стена", "стена\nпроба"))
    }

    @Test
    fun `без стоящей стены ждать нечего — загрузка соберёт сама`() {
        assertNull(BuildSelfDescription.pendingAfter(null, "стена"))
    }
}
