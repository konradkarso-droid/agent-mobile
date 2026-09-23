package com.uroboros.memory.dream

import com.uroboros.memory.Layer
import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вспоминание агентом.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: слова вопроса и найденных записей не
 * засчитываются, одна случайная основа не засчитывается, красный слой и
 * недавно гретая запись не греются. Каждая держит петлю самонакачки.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что совпадение слов значит использование. Это
 * видно только по ответам на устройстве рядом со строкой прибора.
 */
class AgentRecallTest {

    private fun rec(id: Long, text: String, layer: Layer = Layer.PURPLE, lastRecall: Long? = null) =
        Sticker(id = id, content = text, layer = layer.name, lastAgentRecallAt = lastRecall)

    private val towel = rec(1, "Всегда носи с собой полотенце")
    private val sunset = rec(2, "Алеет солнце на закате", layer = Layer.GREEN)

    @Test
    fun `использовал в ответе — вспомнил`() {
        val got = AgentRecall.recalled(
            brought = listOf(towel),
            found = listOf(sunset),
            question = "Что ты думаешь о закате?",
            answer = "Мне снилось, что полотенце всегда стоит носить с собой.",
        )
        assertEquals(listOf(1L), got.map { it.id })
    }

    @Test
    fun `одна случайная основа не засчитывается`() {
        val got = AgentRecall.recalled(listOf(towel), listOf(sunset), "Как дела?", "Полотенце висит.")
        assertTrue(got.isEmpty())
    }

    @Test
    fun `слова вопроса и найденных записей не засчитываются`() {
        val got = AgentRecall.recalled(
            brought = listOf(towel),
            found = emptyList(),
            question = "Носи ли ты с собой полотенце всегда?",
            answer = "Всегда ношу с собой полотенце.",
        )
        assertTrue("это эхо вопроса, а не воспоминание", got.isEmpty())
    }

    @Test
    fun `архив греется на одну ступень, в холодный`() {
        assertEquals(Layer.BLUE, AgentRecall.warmTarget(towel, now = 0))
    }

    @Test
    fun `наверху срок продлевается, выше не поднимает`() {
        assertEquals(Layer.ORANGE, AgentRecall.warmTarget(rec(3, "x", Layer.ORANGE), now = 0))
    }

    @Test
    fun `красный и недавно гретая не греются`() {
        assertNull(AgentRecall.warmTarget(rec(4, "x", Layer.RED), now = 0))
        val day = AgentRecall.WARM_EVERY_MS
        assertNull(AgentRecall.warmTarget(rec(5, "x", Layer.BLUE, lastRecall = 0), now = day - 1))
        assertEquals(Layer.GREEN, AgentRecall.warmTarget(rec(5, "x", Layer.BLUE, lastRecall = 0), now = day))
    }

    @Test
    fun `скрытая и отвергнутая не греются`() {
        assertNull(AgentRecall.warmTarget(towel.copy(reviewPending = true), now = 0))
        assertNull(AgentRecall.warmTarget(towel.copy(rejectedAt = 1L), now = 0))
    }

    @Test
    fun `сон вспомнен, если вспомнена принесённая им запись`() {
        val withTowel = Dream(nightAt = 1, recordIds = "1,2", kind = "TIME") to listOf(towel, sunset)
        val other = Dream(nightAt = 1, recordIds = "2,3", kind = "TIME") to listOf(sunset, rec(3, "Чай"))
        val got = AgentRecall.recalledDreams(listOf(withTowel, other), setOf(1L))
        assertEquals(listOf("1,2"), got.map { it.recordIds })
    }

    @Test
    fun `прибор молчит, только когда сон ничего не принёс`() {
        assertNull(AgentRecall.meter(0, AgentRecall.Outcome(0, 0)))
        assertEquals("Вспомнено агентом: 0 из принесённых снами 2", AgentRecall.meter(2, AgentRecall.Outcome(0, 0)))
    }
}
