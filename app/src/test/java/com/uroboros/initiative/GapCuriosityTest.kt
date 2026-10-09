package com.uroboros.initiative

import com.uroboros.llm.ConversationJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Любопытство разрыва (GapCuriosity): порог по теме и ходу, строка модели. */
class GapCuriosityTest {

    private val hour = 60L * 60 * 1000

    private fun turn(question: String, agent: String = "Понятно.") =
        ConversationJournal.Turn(userContent = question, agentContent = agent, question = question)

    private val withTopic = listOf(turn("Сегодня весь день строгал рубанком доску для стеллажа"))

    @Test
    fun `тема есть, ход у собеседника - порог 12 часов`() {
        val refuse = GapCuriosity.decide(11 * hour, withTopic)
        assertTrue(refuse is GapCuriosity.Decision.Refuse)
        assertEquals("разрыв — молчание 11 ч из 12 (тема есть, ход у собеседника)", (refuse as GapCuriosity.Decision.Refuse).reason)
        assertTrue(GapCuriosity.decide(12 * hour, withTopic) is GapCuriosity.Decision.Ask)
    }

    @Test
    fun `ход у агента - порог 6 часов`() {
        val talk = listOf(turn("Сегодня весь день строгал рубанком доску для стеллажа"), turn("Ок."))
        assertTrue(GapCuriosity.decide(6 * hour, talk) is GapCuriosity.Decision.Ask)
        assertTrue(GapCuriosity.decide(5 * hour, talk) is GapCuriosity.Decision.Refuse)
    }

    @Test
    fun `темы нет - сутки, и спрашивает что нового`() {
        assertTrue(GapCuriosity.decide(23 * hour, emptyList()) is GapCuriosity.Decision.Refuse)
        val ask = GapCuriosity.decide(25 * hour, emptyList()) as GapCuriosity.Decision.Ask
        assertEquals("что нового", ask.what)
        assertEquals("Мы не говорили 25 ч. Спроси пользователя, что у него нового, одним вопросом.", ask.line)
    }

    @Test
    fun `строка называет тему как то, о чём говорили`() {
        val ask = GapCuriosity.decide(13 * hour, withTopic) as GapCuriosity.Decision.Ask
        assertTrue(ask.line, ask.line.startsWith("Мы не говорили 13 ч. В прошлый раз говорили о: «"))
        assertTrue(ask.line, ask.line.endsWith("Спроси пользователя, что у него нового, одним вопросом."))
        assertEquals(GapCuriosity.TOPIC_WORDS, GapCuriosity.topic(withTopic).size)
    }

    @Test
    fun `собеседник ещё не писал - молчит`() {
        assertTrue(GapCuriosity.decide(null, withTopic) is GapCuriosity.Decision.Refuse)
    }
}
