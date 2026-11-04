package com.uroboros.gate

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTurnMeterTest {

    @Test
    fun `ответ — когда, за сколько, сколько знаков`() {
        assertEquals("ответил 14:05 за 38.4 с, 412 зн. · не глядел на приборы", ChatTurnMeter.answered("14:05", 38_400, 412))
        assertEquals(
            "ответил 14:05 за 52.0 с, 90 зн. · глянул на приборы",
            ChatTurnMeter.answered("14:05", 52_000, 90, ChatGlance.Look.LOOKED),
        )
    }

    @Test
    fun `обсчёт — новые токены, скорость, что взято готовым`() {
        assertEquals(
            "Обсчёт (Петя, 14:05): 860 новых ток. за 31.0 с (27.7 ток/с) · Стена: взята готовой",
            ChatTurnMeter.evalLine("Петя", "14:05", 860, 31_000_000, "Стена: взята готовой"),
        )
        assertEquals(
            "Обсчёт (Петя, 14:05): новых токенов нет — всё взято готовым · Лента: …",
            ChatTurnMeter.evalLine("Петя", "14:05", 0, 0, "Лента: …"),
        )
    }

    @Test
    fun `генерация не дошла — чисел этого хода нет, и это сказано`() {
        assertEquals("Обсчёт (Петя, 14:05): не известно — генерация не дошла до ответа", ChatTurnMeter.noEvalLine("Петя", "14:05"))
    }
}
