package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
