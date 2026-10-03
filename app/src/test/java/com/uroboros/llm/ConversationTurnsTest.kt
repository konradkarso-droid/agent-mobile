package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка края перед ходом (ConversationTurns.gate). Главное здесь — порядок:
 * кончившаяся лента называется раньше «слишком длинно», иначе человеку
 * советовали бы сократить сообщение, которое не влезет никаким.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: сам ход (прогон, закрытие хода, запись на диск) —
 * он ходит в движок и базу, и обычным тестом его не достать.
 */
class ConversationTurnsTest {

    private val context = 8192
    private val answer = ConversationTurns.ANSWER_TOKEN_LIMIT

    private fun journal(promptTokens: Int) = ConversationJournal().apply { notePromptTokens(promptTokens) }

    @Test
    fun `место есть и реплика короткая — можно`() {
        assertNull(ConversationTurns.gate(journal(1000), "Как дела?", context, answer))
    }

    @Test
    fun `лента кончилась — это называется раньше длины реплики`() {
        val full = journal(context - answer - ConversationJournal.MIN_QUESTION_TOKENS)
        assertEquals(ConversationTurns.Outcome.JournalFull, ConversationTurns.gate(full, "x".repeat(100_000), context, answer))
        assertEquals(ConversationTurns.Outcome.JournalFull, ConversationTurns.gate(full, "?", context, answer))
    }

    @Test
    fun `реплика длиннее остатка — слишком длинно, с числами`() {
        val j = journal(1000)
        val max = j.maxContentChars(context, answer)
        assertNull("ровно по пределу проходит", ConversationTurns.gate(j, "x".repeat(max), context, answer))
        assertEquals(
            ConversationTurns.Outcome.TooLong(max + 1, max),
            ConversationTurns.gate(j, "x".repeat(max + 1), context, answer),
        )
    }

    /** Записи уходят вспоминанием агента, мимо реплики, — сторож меряет их вместе с ней. */
    @Test
    fun `вспоминание считается вместе с репликой`() {
        val j = journal(1000)
        val max = j.maxContentChars(context, answer)
        assertNull(ConversationTurns.gate(j, "x".repeat(max - 10), context, answer))
        assertEquals(
            ConversationTurns.Outcome.TooLong(max + 1, max),
            ConversationTurns.gate(j, "x".repeat(max - 10), context, answer, recall = "y".repeat(11)),
        )
    }

    // --- Петля до потолка ---

    private val record = "Твои слова в прошлом разговоре, пару недель назад: «Твой любимый инструмент — рубанок с железной колодкой.». "
    private val looped = "Твои слова в прошлом разговоре, с неделю назад: «Ты работаешь по субботам.». " + record + record

    /** Живой случай с телефона: круг перечисления повторился, ответ дошёл до потолка. */
    @Test
    fun `петля, дошедшая до потолка, — скрыть`() {
        val result = ConversationTurns.loopAtCeiling(looped, ConversationTurns.ANSWER_TOKEN_LIMIT)
        assertEquals(3, result?.loopAt)
        assertTrue(
            ConversationTurns.loopCutLine(result!!.loopAt!!, result.loop!!)
                .startsWith("Перехват: ответ ушёл в петлю — с предложения 3"),
        )
    }

    /** Молчание: петля в ответе, кончившемся самим, — законный повтор, ход ложится. */
    @Test
    fun `петля до потолка не дошла — не скрывать`() {
        assertNull(ConversationTurns.loopAtCeiling(looped, ConversationTurns.ANSWER_TOKEN_LIMIT - 1))
    }

    /** Молчание: длинный ответ без повтора, оборванный потолком, ложится — убрать его может человек. */
    @Test
    fun `потолок без петли — не скрывать`() {
        val long = "Ты работаешь по субботам. Твой любимый инструмент — рубанок. Ты был у стоматолога."
        assertNull(ConversationTurns.loopAtCeiling(long, ConversationTurns.ANSWER_TOKEN_LIMIT))
    }

    /** Вызов приборов в хвосте ответа в ленту не идёт и петлёй не меряется. */
    @Test
    fun `вызов приборов в хвосте не считается`() {
        val withCall = "Заряд батареи семьдесят процентов. <tool_call> Заряд батареи семьдесят процентов."
        assertNull(ConversationTurns.loopAtCeiling(withCall, ConversationTurns.ANSWER_TOKEN_LIMIT))
    }
}
