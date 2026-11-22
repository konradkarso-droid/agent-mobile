package com.uroboros.search

import com.uroboros.memory.nav.LemmaDictionary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.ln

internal fun searchTerms(query: String): List<String> = searchGroups(query, null).flatten()

private val word = Regex("[\\p{L}\\p{N}]+")
private val filler = setOf(
    "как", "где", "что", "это", "когда", "какой", "какие", "для", "или", "при",
    "во", "на", "по", "из", "об", "от", "за", "про", "такое",
    "почему", "зачем", "сколько", "чем", "можно", "ли",
    "the", "and", "how", "is", "to", "what", "when", "where", "which", "does", "with",
)

private fun searchGroups(query: String, dictionary: LemmaDictionary?): List<Set<String>> =
    linkedMapOf<String, MutableSet<String>>().apply {
        val tokens = word.findAll(query.lowercase(Locale.ROOT)).map { it.value }
            .filter { it.length in 2..48 && it !in filler }.distinct().take(12)
        for (token in tokens) {
            val lemma = dictionary?.analyze(token)?.firstOrNull()?.lemma?.lowercase(Locale.ROOT)
                ?.takeIf(word::matches)
            val forms = getOrPut(lemma ?: token) { linkedSetOf() }
            forms.add(token)
            if (lemma != null && lemma.length >= 5) {
                forms.add(lemma)
                forms.add(lemma.dropLast(1) + "*")
            }
        }
    }.values.toList()

internal data class MatchScore(val missingWords: List<String>, val weight: Double)

internal class FtsQuery(query: String, dictionary: LemmaDictionary? = null) {
    private val groups = searchGroups(query, dictionary)
    val terms = groups.flatten().distinct()
    private val termGroups = terms.map { term -> groups.indices.filter { term in groups[it] } }

    fun score(blob: ByteArray): MatchScore {
        val data = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder())
        val phrases = data.int
        val columns = data.int
        check(phrases == terms.size)
        val weights = DoubleArray(groups.size * columns)
        repeat(phrases) { phrase ->
            repeat(columns) { column ->
                val hits = data.int
                data.int // Число совпадений с этой фразой во всём индексе.
                val documents = data.int
                if (hits > 0) {
                    val weight = (if (column == 0) 2 else 1) * (1 + ln(1.0 + hits)) / ln(2.0 + documents)
                    // У каждой лексемы одна прибавка на колонку, даже если совпало несколько её форм.
                    for (group in termGroups[phrase]) {
                        val offset = group * columns + column
                        weights[offset] = maxOf(weights[offset], weight)
                    }
                }
            }
        }
        val missing = groups.indices.mapNotNull { group ->
            if ((0 until columns).all { column -> weights[group * columns + column] == 0.0 }) {
                groups[group].first()
            } else null
        }
        return MatchScore(missing, weights.sum())
    }
}
