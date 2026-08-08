package com.uroboros.memory

object RiskTrigger {

    private val NEGATION_MARKERS = setOf(
        "не", "нет", "никогда", "ни", "невозможно", "нельзя"
    )

    private val UNCERTAINTY_MARKERS = setOf(
        "наверное", "возможно", "кажется", "вроде", "не уверен",
        "не уверена", "может быть", "предположительно", "скорее всего"
    )

    private val WORD_NUMBERS = mapOf(
        "ноль" to "0", "один" to "1", "одна" to "1", "одно" to "1",
        "два" to "2", "две" to "2", "три" to "3", "четыре" to "4",
        "пять" to "5", "шесть" to "6", "семь" to "7", "восемь" to "8",
        "девять" to "9", "десять" to "10", "одиннадцать" to "11",
        "двенадцать" to "12", "тринадцать" to "13", "четырнадцать" to "14",
        "пятнадцать" to "15", "шестнадцать" to "16", "семнадцать" to "17",
        "восемнадцать" to "18", "девятнадцать" to "19", "двадцать" to "20",
        "тридцать" to "30", "сорок" to "40", "пятьдесят" to "50",
        "шестьдесят" to "60", "семьдесят" to "70", "восемьдесят" to "80",
        "девяносто" to "90", "сто" to "100"
    )

    private const val CONTRADICTION_JACCARD_THRESHOLD = 0.5
    private const val LONG_CONTENT_CHARS = 240

    data class Decision(
        val shouldReview: Boolean,
        val reasons: List<String>,
        val contradictionCandidateId: Long? = null
    )

    fun evaluate(candidate: Sticker, sameTagHotStickers: List<Sticker>): Decision {
        val reasons = mutableListOf<String>()

        val contradiction = findContradiction(candidate, sameTagHotStickers)
        if (contradiction != null) {
            reasons += "contradiction"
            return Decision(
                shouldReview = true,
                reasons = reasons,
                contradictionCandidateId = contradiction.id
            )
        }

        var lowWeightCount = 0

        if (Importance.valueOf(candidate.importance) == Importance.HIGH) {
            lowWeightCount++
            reasons += "high_importance"
        }

        if (hasUncertaintyMarker(candidate.content)) {
            lowWeightCount++
            reasons += "uncertainty_marker"
        }

        if (candidate.content.length >= LONG_CONTENT_CHARS) {
            lowWeightCount++
            reasons += "long_content"
        }

        val trigger = lowWeightCount >= 2
        return Decision(
            shouldReview = trigger,
            reasons = if (trigger) reasons else emptyList()
        )
    }

    private fun hasUncertaintyMarker(text: String): Boolean {
        val words = tokenize(text)
        return UNCERTAINTY_MARKERS.any { marker ->
            if (marker.contains(' ')) text.lowercase().contains(marker) else words.contains(marker)
        }
    }

    private fun findContradiction(candidate: Sticker, pool: List<Sticker>): Sticker? {
        val candidateWords = tokenize(candidate.content)
        val candidateHasNegation = candidateWords.any { it in NEGATION_MARKERS }
        val candidateNumbers = extractNumbers(candidate.content)

        for (existing in pool) {
            if (existing.id == candidate.id) continue
            val existingWords = tokenize(existing.content)
            val overlap = jaccard(candidateWords, existingWords)
            if (overlap < CONTRADICTION_JACCARD_THRESHOLD) continue

            val existingHasNegation = existingWords.any { it in NEGATION_MARKERS }
            val negationMismatch = candidateHasNegation != existingHasNegation

            val existingNumbers = extractNumbers(existing.content)
            val numberMismatch = candidateNumbers.isNotEmpty() &&
                existingNumbers.isNotEmpty() &&
                candidateNumbers != existingNumbers

            if (negationMismatch || numberMismatch) {
                return existing
            }
        }
        return null
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .toSet()

    private fun extractNumbers(text: String): Set<String> {
        val digitNumbers = Regex("\\d+").findAll(text).map { it.value }.toSet()
        val lowerText = text.lowercase()
        val wordNumbers = WORD_NUMBERS.entries
            .filter { (word, _) -> Regex("\\b${word}\\b").containsMatchIn(lowerText) }
            .map { it.value }
            .toSet()
        return digitNumbers + wordNumbers
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }
}
