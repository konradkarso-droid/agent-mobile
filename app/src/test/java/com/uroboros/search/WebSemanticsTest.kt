package com.uroboros.search

import com.uroboros.memory.nav.TestVerbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSemanticsTest {
    @Test
    fun quantizedCosineKeepsSignsAndDirection() {
        val query = checkNotNull(QuantizedWebVector.from(floatArrayOf(0f, -0.16f, 0.9f)))
        assertEquals(1f, query.cosine(query.values, query.inverseNorm), 0.0001f)
        val opposite = checkNotNull(QuantizedWebVector.from(floatArrayOf(0f, 0.16f, -0.9f)))
        assertEquals(-1f, query.cosine(opposite.values, opposite.inverseNorm), 0.0001f)
        assertNull(QuantizedWebVector.from(FloatArray(5)))
    }

    @Test
    fun retainedSpansReachEndOfLargePageWithinStorageBudget() {
        val text = List(22) { "Абзац ".repeat(100) }.joinToString("\n")
        val spans = webSpans(text)
        assertEquals(MAX_WEB_SPANS, spans.size)
        assertEquals(0, spans.first().start)
        assertEquals(text.length, spans.last().end)
        assertTrue(spans.all { it.end - it.start <= 1_000 && it.text(text) != null })
        assertNull(WebSpan(0, text.length + 1).text(text))
    }

    @Test
    fun dictionaryFindsRelevantFragmentButRejectsUnknownQueryAndBrokenBlob() {
        val encoder = WebSemantics(TestVerbs.vectors, TestVerbs.lemmas)
        val unrelated = "Телескопы изучают галактики, туманности, звёзды и далёкие планеты. ".repeat(15)
        val relevant = "Врач осмотрел зубы, поставил пломбу и назначил повторный приём."
        val body = "$unrelated\n" + (relevant + " ").repeat(4)
        val page = IndexedPage("https://example.org/article", "Медицинская помощь", body)
        val query = checkNotNull(encoder.query("Стоматолог лечит кариес"))
        val encoded = encoder.encode(page)
        assertTrue(encoded.size <= MAX_WEB_SPANS * (12 + TestVerbs.vectors.dim))
        assertNotNull(encoder.best(encoded, query))
        assertTrue(checkNotNull(encoder.best(encoded, query)?.span?.text(body)).contains(relevant))
        assertNull(encoder.query("zzzxvv unknownidentifier"))
        assertNull(encoder.best(byteArrayOf(1, 2, 3), query))
    }

    @Test
    fun combiningRankingsKeepsLexicalEvidenceAndAddsSemanticSpans() {
        fun match(id: Int, span: WebSpan? = null) =
            IndexedMatch(IndexedPage("https://example.org/$id", "Статья", "Строка"), listOf("слово"), span)
        val span = WebSpan(0, 6)
        val combined = combineWebMatches(listOf(match(1), match(2)), listOf(match(3, span), match(2, span)))
        assertEquals(listOf(match(2, span), match(1), match(3, span)), combined)
    }
}
