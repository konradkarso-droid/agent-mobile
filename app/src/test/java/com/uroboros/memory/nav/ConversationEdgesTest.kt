package com.uroboros.memory.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEdgesTest {

    private val ribbons = listOf(
        listOf("Привет", "Рубанок наточил", "Сладких снов"),
        listOf("Привет", "Станок гудит", "Рубанок тупой", "Сладких снов"),
        listOf("Привет", "Рубанок сломался", "Сладких снов"),
        listOf("Рубанок купил"),
    )

    @Test
    fun `слово почти всегда на краю - кандидат, не из словаря видно отдельно`() {
        val edges = ConversationEdges.of(ribbons, dictionary = null)
        assertEquals(listOf("привет", "сладких", "снов"), edges.map { it.word }.sorted())
        assertTrue(edges.first { it.word == "привет" }.inVocabulary)
        val line = ConversationEdges.meterLine(edges, ribbons.size)
        assertTrue(line, line.contains("не в словаре обычая: сладких, снов") || line.contains("не в словаре обычая: снов, сладких"))
    }

    @Test
    fun `молчит - слово в середине и лента из одной реплики`() {
        val edges = ConversationEdges.of(ribbons, dictionary = null)
        // «рубанок» — 3 раза в лентах длиннее одной реплики, на краю ни разу;
        // лента из одной реплики не считается.
        assertTrue(edges.none { it.word == "рубанок" })
        assertEquals("Края разговоров: кандидатов нет (лент 1)", ConversationEdges.meterLine(ConversationEdges.of(listOf(listOf("Привет")), null), 1))
    }
}
