package com.uroboros.search

import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.memory.nav.MeaningVectors
import com.uroboros.util.TextFold
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class WebSpan(val start: Int, val end: Int) {
    fun text(body: String): String? = if (start in 0 until end && end <= body.length) body.substring(start, end).takeIf { it.isNotBlank() } else null
}

internal data class WebSimilarity(val span: WebSpan, val score: Float)

/** В базе лежат только границы в очищенном тексте и квантованный спектр. */
internal class WebSemantics(private val vectors: MeaningVectors, private val dictionary: LemmaDictionary) {
    private val dim = vectors.dim
    private val recordSize = 12 + dim
    private val wordVectors = HashMap<String, FloatArray?>()

    fun encode(page: IndexedPage): ByteArray {
        val spans = webSpans(page.text)
        val bytes = ByteBuffer.allocate(recordSize * spans.size)
        for (span in spans) {
            val vector = spectrum(page.text.substring(span.start, span.end), page.title) ?: continue
            bytes.putInt(span.start).putInt(span.end).putFloat(vector.inverseNorm).put(vector.values)
        }
        return bytes.array().copyOf(bytes.position())
    }

    fun query(text: String): QuantizedWebVector? = spectrum(text)

    fun best(blob: ByteArray, query: QuantizedWebVector): WebSimilarity? {
        if (blob.size % recordSize != 0 || blob.size / recordSize > MAX_WEB_SPANS || query.values.size != dim) return null
        val bytes = ByteBuffer.wrap(blob)
        var best: WebSimilarity? = null
        val sample = ByteArray(dim)
        while (bytes.hasRemaining()) {
            val start = bytes.int
            val end = bytes.int
            val norm = bytes.float
            bytes.get(sample)
            if (start < 0 || end <= start || !norm.isFinite() || norm <= 0f) continue
            val score = query.cosine(sample, norm)
            if (score >= MIN_SIMILARITY && (best == null || score > best.score)) best = WebSimilarity(WebSpan(start, end), score)
        }
        return best
    }

    private fun spectrum(text: String, title: String = ""): QuantizedWebVector? {
        fun terms(input: String): Set<String> = semanticTokens(TextFold.fold(input))
            .filter { it !in ignored }.map { word ->
                dictionary.analyze(word).firstOrNull()?.lemma ?: word
            }.toSet()
        val heading = terms(title)
        val body = terms(text)
        val sum = FloatArray(dim)
        var used = 0
        for (term in body + heading) {
            if (wordVectors.size >= 768) wordVectors.clear()
            val vector = wordVectors.getOrPut(term) { vectors.vector(term) } ?: continue
            val weight = if (term in heading) 2f else 1f
            for (i in sum.indices) sum[i] += weight * vector[i]
            used++
        }
        return if (used < 2) null else QuantizedWebVector.from(sum)
    }

    private companion object {
        // Объявленная отсечка первого прототипа. Близость тем не подтверждает факт из источника.
        const val MIN_SIMILARITY = 0.40f
        val ignored = setOf("его", "она", "они", "там", "это", "того", "этот", "этой", "есть", "будет", "было", "нужно")
    }
}

internal data class QuantizedWebVector(val values: ByteArray, val inverseNorm: Float) {
    fun cosine(other: ByteArray, otherNorm: Float): Float {
        var dot = 0
        for (i in values.indices) dot += values[i].toInt() * other[i].toInt()
        return dot * inverseNorm * otherNorm
    }

    companion object {
        fun from(values: FloatArray): QuantizedWebVector? {
            val peak = values.maxOfOrNull { kotlin.math.abs(it) } ?: return null
            if (!peak.isFinite() || peak <= 0f) return null
            val quantized = ByteArray(values.size) { (values[it] / peak * 127).roundToInt().toByte() }
            val norm = sqrt(quantized.sumOf { it.toInt() * it.toInt() }.toFloat())
            return QuantizedWebVector(quantized, 1f / norm)
        }
    }
}

internal const val MAX_WEB_SPANS = 4

/** Границы абзацев с жёстким пределом. У длинной страницы берём фрагменты по всей длине. */
internal fun webSpans(text: String): List<WebSpan> {
    val spans = mutableListOf<WebSpan>()
    var from = 0
    while (from < text.length) {
        val cap = minOf(text.length, from + 1_000)
        val newline = text.lastIndexOf('\n', cap - 1).takeIf { it >= from + 350 }
        val end = when {
            cap == text.length -> cap
            newline != null -> newline + 1
            text[cap - 1].isHighSurrogate() && text[cap].isLowSurrogate() -> cap - 1
            else -> cap
        }
        spans += WebSpan(from, end)
        from = end
    }
    if (spans.size <= MAX_WEB_SPANS) return spans
    return List(MAX_WEB_SPANS) { i -> spans[i * (spans.lastIndex) / (MAX_WEB_SPANS - 1)] }
}

/** Совпадение обеих выдач получает сумму обратных рангов. */
internal fun combineWebMatches(lexical: List<IndexedMatch>, semantic: List<IndexedMatch>): List<IndexedMatch> {
    val scores = mutableMapOf<String, Double>()
    for (ranking in listOf(lexical, semantic)) {
        for ((position, match) in ranking.withIndex()) {
            val url = match.page.url
            scores[url] = (scores[url] ?: 0.0) + 1.0 / (31 + position)
        }
    }
    val spans = semantic.associate { it.page.url to it.semanticSpan }
    return (lexical + semantic).distinctBy { it.page.url }.sortedByDescending { scores.getValue(it.page.url) }
        .map { it.copy(semanticSpan = spans[it.page.url]) }
}
