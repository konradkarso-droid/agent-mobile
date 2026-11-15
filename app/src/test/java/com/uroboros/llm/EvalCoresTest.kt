package com.uroboros.llm

import com.uroboros.llm.EvalCores.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalCoresTest {

    private val none = Reading("", -1, -1)

    @Test
    fun `на экране, ядер хватает — только место и ядра, без предупреждения`() {
        val top = Reading("top-app", 8, 6)
        assertEquals(
            "Ядра: на экране (top-app), дано 8 из 8, быстрых 6 из 6",
            EvalCores.line(top, top, 8, 6, 6),
        )
    }

    @Test
    fun `в фоне ядер дали меньше, чем потоков, — строка это говорит`() {
        val bg = Reading("background", 3, 1)
        assertEquals(
            "Ядра: в фоне (background), дано 3 из 8, быстрых 1 из 6 · потоков обсчёта 6 больше, чем дано быстрых ядер (1)",
            EvalCores.line(bg, bg, 8, 6, 6),
        )
    }

    @Test
    fun `служба без экрана со всеми ядрами — не на экране, но без предупреждения`() {
        val fg = Reading("foreground", 8, 6)
        val line = EvalCores.line(fg, fg, 8, 6, 6)
        assertEquals("Ядра: не на экране, служба (foreground), дано 8 из 8, быстрых 6 из 6", line)
        assertFalse(line.contains("больше"))
    }

    @Test
    fun `за обсчёт ушёл с экрана — видно оба чтения, предупреждение по меньшему`() {
        val line = EvalCores.line(Reading("top-app", 8, 6), Reading("foreground", 7, 5), 8, 6, 6)
        assertEquals(
            "Ядра: на экране (top-app), дано 8 из 8, быстрых 6 из 6 → к концу обсчёта: не на экране, служба (foreground), дано 7 из 8, быстрых 5 из 6" +
                " · потоков обсчёта 6 больше, чем дано быстрых ядер (5)",
            line,
        )
    }

    @Test
    fun `конец не прочитался — показывается только начало`() {
        val top = Reading("top-app", 8, 6)
        assertEquals("Ядра: на экране (top-app), дано 8 из 8, быстрых 6 из 6", EvalCores.line(top, none, 8, 6, 6))
    }

    @Test
    fun `быстрые не известны — сравнение со всеми ядрами`() {
        assertEquals(
            "Ядра: в фоне (background), дано 2 из 8 · потоков обсчёта 4 больше, чем дано ядер (2)",
            EvalCores.line(Reading("background", 2, -1), none, 8, 0, 4),
        )
        assertEquals("Ядра: в фоне (background), дано 4 из 8", EvalCores.line(Reading("background", 4, -1), none, 8, 0, 4))
    }

    @Test
    fun `ничего не прочиталось — так и сказано, без предупреждения`() {
        assertEquals("Ядра: не прочитались", EvalCores.line(none, none, -1, 0, 6))
    }

    @Test
    fun `число потоков не известно — предупреждения нет`() {
        val bg = Reading("background", 1, 1)
        assertFalse(EvalCores.line(bg, bg, 8, 6, -1).contains("больше"))
    }

    @Test
    fun `группа словами и чужое слово системы как есть`() {
        assertEquals("в системном фоне (system-background)", EvalCores.where("system-background"))
        assertEquals("под ограничением системы (restricted)", EvalCores.where("restricted"))
        assertEquals("без группы", EvalCores.where("root"))
        assertEquals("группа не прочиталась", EvalCores.where(""))
        assertTrue(EvalCores.where("camera-daemon").contains("camera-daemon"))
    }
}
