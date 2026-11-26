package com.uroboros.memory.nav

import com.uroboros.util.TextFold
import kotlin.math.sqrt

/**
 * Признаки смысла на том же словаре и файле Navec, что у прибора «Смысл».
 * Близость слов отвечает за тему. Классификация реплики и решение о действии
 * остаются у вызывающего кода: у похожих слов бывают противоположные значения.
 */
internal class SemanticFeatures(
    private val vectors: MeaningVectors,
    private val dictionary: LemmaDictionary,
) {
    internal class Word(
        val surface: String, val lemma: String, val vector: FloatArray?,
        val imperative: Boolean, val noun: Boolean,
    )

    fun words(forms: Iterable<String>): List<Word> = forms.distinct().take(MAX_TERMS).map { form ->
        val folded = TextFold.fold(form)
        val analyses = dictionary.analyze(folded)
        val lemma = analyses.firstOrNull()?.lemma ?: folded
        Word(
            folded, lemma, vectors.vector(lemma) ?: vectors.vector(folded),
            imperative = analyses.any { "impr" in it.tags },
            noun = analyses.isNotEmpty() && analyses.all { it.partOfSpeech == "NOUN" },
        )
    }.distinctBy { it.lemma }

    fun matches(word: Word, concepts: List<Word>, atLeast: Double): Boolean =
        concepts.any { score(word, it) >= atLeast }

    fun score(left: Word, right: Word): Double {
        if (left.lemma == right.lemma || left.surface == right.surface) return 1.0
        val a = left.vector ?: return 0.0
        val b = right.vector ?: return 0.0
        var product = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            product += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        val norm = sqrt(normA * normB)
        return if (norm == 0.0) 0.0 else product / norm
    }

    companion object {
        private const val MAX_TERMS = 24

        fun available(): SemanticFeatures? {
            val vectors = MeaningHolder.loaded ?: return null
            val dictionary = LemmaHolder.loaded ?: return null
            return SemanticFeatures(vectors, dictionary)
        }
    }
}
