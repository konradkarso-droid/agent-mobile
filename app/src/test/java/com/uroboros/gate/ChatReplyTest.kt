package com.uroboros.gate

import com.uroboros.memory.nav.AddressSorter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReplyTest {

    private fun inputs(
        answer: AddressSorter.Answer = AddressSorter.Answer.ANSWER,
        stop: Boolean = false,
        watchdog: String? = null,
        consent: Boolean = true,
        night: Boolean = false,
        age: Long = 10_000,
        newer: Int = 0,
        bot: Boolean = false,
        since: Int = 0,
    ) = ChatReply.Inputs(answer, stop, watchdog, consent, night, age, newer, bot, since)

    private fun silent(i: ChatReply.Inputs) = (ChatReply.decide(i) as ChatReply.Decision.Silent).why

    @Test
    fun `обращённое — ответ, сомнительное — переспрос`() {
        assertEquals(ChatReply.Decision.Answer, ChatReply.decide(inputs()))
        assertEquals(ChatReply.Decision.Ask, ChatReply.decide(inputs(answer = AddressSorter.Answer.ASK)))
    }

    @Test
    fun `молчит, когда должно`() {
        assertTrue(silent(inputs(answer = AddressSorter.Answer.SILENT)).contains("молчать"))
        assertTrue(silent(inputs(stop = true)).contains("аварийный стоп"))
        assertTrue(silent(inputs(watchdog = "зона WARNING — не «норма»")).contains("сторож"))
        assertTrue(silent(inputs(consent = false)).contains("нет разрешения"))
        assertTrue(silent(inputs(night = true)).contains("ночная"))
        assertTrue(silent(inputs(age = ChatReply.STALE_MS + 60_000)).contains("ветка ушла"))
        assertTrue(silent(inputs(newer = ChatReply.STALE_NEWER + 1)).contains("ветка ушла"))
        assertTrue(silent(inputs(bot = true, since = ChatReply.MAX_BOT_ANSWERS_IN_A_ROW)).contains("ботам подряд"))
    }

    @Test
    fun `переспрос проходит те же условия, что ответ`() {
        assertTrue(silent(inputs(answer = AddressSorter.Answer.ASK, consent = false)).contains("нет разрешения"))
        assertTrue(silent(inputs(answer = AddressSorter.Answer.ASK, stop = true)).contains("аварийный стоп"))
    }

    @Test
    fun `стоп важнее согласия, сторож важнее согласия`() {
        assertTrue(silent(inputs(stop = true, consent = false)).contains("аварийный стоп"))
        assertTrue(silent(inputs(watchdog = "нагрев", consent = false)).contains("сторож"))
    }

    @Test
    fun `предел глубины — только ботам`() {
        assertEquals(ChatReply.Decision.Answer, ChatReply.decide(inputs(bot = false, since = 10)))
        assertEquals(ChatReply.Decision.Answer, ChatReply.decide(inputs(bot = true, since = ChatReply.MAX_BOT_ANSWERS_IN_A_ROW - 1)))
    }

    @Test
    fun `счёт своих ответов с последней реплики человека`() {
        fun t(agent: Boolean, bot: Boolean = false) = ChatReply.Turn(agent, bot)
        assertEquals(0, ChatReply.answersSinceHuman(emptyList()))
        assertEquals(0, ChatReply.answersSinceHuman(listOf(t(true), t(false))))
        assertEquals(2, ChatReply.answersSinceHuman(listOf(t(false), t(true), t(false, bot = true), t(true), t(false, bot = true))))
        assertEquals("бот человеком не считается", 3, ChatReply.answersSinceHuman(listOf(t(true), t(false, true), t(true), t(false, true), t(true), t(false, true))))
    }
}
