package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceDeskTest {

    private val readings = GlanceDesk.Readings(
        body = "Тело: заряд 52%, 34.0°C, зона норма",
        ribbon = "Лента: 1200 из 8192 токенов",
        model = "Модель: Falcon-H1-7B.gguf, контекст 8192",
        records = 677,
        speed = "12.5",
        promises = listOf("Обещал вам в телеграме (личка): «Напомню завтра.»"),
    )

    @Test
    fun `доска владельца — всё`() {
        assertEquals(
            listOf(
                "Тело: заряд 52%, 34.0°C, зона норма",
                "Лента: 1200 из 8192 токенов",
                "Модель: Falcon-H1-7B.gguf, контекст 8192",
                "Память: записей 677",
                "Последний ход: выдача 12.5 ток/с",
                "Обещал вам в телеграме (личка): «Напомню завтра.»",
            ),
            GlanceDesk.of(readings, GlanceDesk.Trust.OWNER).lines(),
        )
    }

    @Test
    fun `доска телеграма молчит о модели, контексте и ленте экрана, даже если они поданы`() {
        val d = GlanceDesk.of(readings, GlanceDesk.Trust.OUTSIDE)
        assertFalse("Falcon" in d)
        assertFalse("контекст 8192" in d)
        assertFalse(d.lines().any { it.startsWith("Лента:") })
        assertEquals(GlanceDesk.NOT_HERE, d.lines().last())
        assertTrue("Память: записей 677" in d.lines())
        assertTrue(readings.body in d.lines())
    }

    @Test
    fun `нет числа записей — сказано словами, нет скорости — строки нет`() {
        val d = GlanceDesk.of(readings.copy(records = null, speed = null), GlanceDesk.Trust.OUTSIDE)
        assertTrue("Память: число записей сейчас не прочиталось." in d.lines())
        assertFalse("Последний ход" in d)
    }
}
