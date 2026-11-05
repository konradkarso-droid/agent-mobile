package com.uroboros.gate

import com.uroboros.llm.AnswerLadder
import com.uroboros.llm.GenerationEnd
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTurnMeterTest {

    @Test
    fun `ответ — когда, за сколько, сколько знаков`() {
        assertEquals("ответил 14:05 за 38.4 с, 412 зн.", ChatTurnMeter.answered("14:05", 38_400, 412))
        assertEquals(
            "ответил 14:05 за 52.0 с, 90 зн. · глянул на приборы",
            ChatTurnMeter.answered("14:05", 52_000, 90, "глянул на приборы"),
        )
    }

    private fun result(glanced: Boolean = false, retried: Boolean = false, intercept: AnswerLadder.Intercept? = null, deskByCode: String? = null) =
        AnswerLadder.Result(
            answer = "", tokensSeen = 1, firstTokenAtMs = null, metrics = null, generationEnd = GenerationEnd.COMPLETED,
            failure = null, intercept = intercept, hidden = false, kept = null, glanced = glanced, glanceRetried = retried,
            loop = null, haltedBy = null, deskByCode = deskByCode,
        )

    private fun intercept(cause: AnswerLadder.Cause, replaced: Boolean, third: AnswerLadder.Third? = null) =
        AnswerLadder.Intercept(cause, "", GenerationEnd.COMPLETED, 0, null, replaced, false, emptyList(), third)

    @Test
    fun `лестница словами — взгляд и что ушло после перехвата`() {
        assertEquals("не глядел на приборы", ChatTurnMeter.ladderNote(result(), sentFirst = false))
        assertEquals("глянул на приборы", ChatTurnMeter.ladderNote(result(glanced = true), sentFirst = false))
        val damage = AnswerLadder.Cause.Damage(listOf("повторяюек"))
        assertEquals(
            "не глядел на приборы · перехват: порча «повторяюек» → ушла вторая попытка",
            ChatTurnMeter.ladderNote(result(intercept = intercept(damage, replaced = true)), sentFirst = false),
        )
        assertEquals(
            "не глядел на приборы · перехват: порча «повторяюек» → вторая не лучше, ушёл первый ответ",
            ChatTurnMeter.ladderNote(result(intercept = intercept(damage, replaced = false)), sentFirst = false),
        )
        assertEquals(
            "не глядел на приборы · перехват: повтор «Привет.» → годного нет, ушёл первый ответ",
            ChatTurnMeter.ladderNote(result(intercept = intercept(AnswerLadder.Cause.Repeat("Привет.", setOf(0)), replaced = false)), sentFirst = true),
        )
    }

    @Test
    fun `доску показал код — отдельно от «глянул сам»`() {
        assertEquals("доску показал код (слово «записей»)", ChatTurnMeter.ladderNote(result(glanced = true, deskByCode = "записей"), sentFirst = false))
        assertEquals(
            "доску показал код (слово «записей»); после доски было пусто — проход повторён",
            ChatTurnMeter.ladderNote(result(glanced = true, retried = true, deskByCode = "записей"), sentFirst = false),
        )
        assertEquals("глянул на приборы", ChatTurnMeter.ladderNote(result(glanced = true), sentFirst = false))
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
