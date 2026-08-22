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

    // Ordered longest-first so the longest matching suffix is stripped.
    // Deliberately crude (no dictionary, no morphology) — this is cheap
    // friction toward the reviewPending checkpoint, not a linguistic tool.
    // 2026-08-18: added "ия" alongside the existing "ию" — without it, the
    // accusative ("-ию") and nominative ("-ия") forms of the same noun
    // (e.g. "функцию"/"функция") stemmed to different roots, silently killing
    // Jaccard overlap between them (found via item 9's QueryUrgencyClassifier
    // using RiskTrigger.textSimilarity on task descriptions vs. queries).
    private val SUFFIXES = listOf(
        "иями",
        "ями", "ами", "ого", "его", "ому", "ему", "ыми", "ими",
        "ев", "ов", "ам", "ям", "ах", "ях", "ом", "ем", "ей",
        "юю", "ая", "яя", "ое", "ее", "ых", "их", "ию", "ия", "ья", "ье", "ий", "ый",
        "ы", "и", "а", "я", "о", "е", "у", "ю", "й", "ь"
    )

    private const val MIN_ROOT_LENGTH = 3
    private const val CONTRADICTION_JACCARD_THRESHOLD = 0.5
    private const val LONG_CONTENT_CHARS = 240

    // Порог схожести для распознавания "той же самой" ошибки компилятора
    // (item 7a: лавинообразный расход энергии при зацикливании).
    private const val REPEATED_ERROR_JACCARD_THRESHOLD = 0.7

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

    /**
     * Считает две ошибки компилятора "той же самой" по текстовому сходству
     * (переиспользует ту же стемминг+Jaccard инфраструктуру, что и поиск
     * противоречий, только в обратную сторону: не различие, а повтор).
     * Используется item 7a для лавинообразного расхода энергии при зацикливании.
     */
    fun isRepeatedError(currentError: String, previousError: String): Boolean {
        val a = stemmedTokenize(currentError)
        val b = stemmedTokenize(previousError)
        return jaccard(a, b) >= REPEATED_ERROR_JACCARD_THRESHOLD
    }

    /**
     * Общего назначения коэффициент текстовой схожести (0.0–1.0), без встроенного
     * порога — вызывающий код сам решает, что считать "похоже". Переиспользует ту же
     * стемминг+Jaccard инфраструктуру, что isRepeatedError/findContradiction.
     *
     * Item 9 (2026-08-18): используется классификатором light/heavy для сравнения
     * входящего запроса пользователя с (текущей ошибкой + описанием подзадачи) на
     * швах TOTE-цикла — сюда сознательно НЕ зашит собственный порог, чтобы
     * RiskTrigger оставался общей утилитой, не завязанной на конкретный домен.
     */
    fun textSimilarity(a: String, b: String): Double =
        jaccard(stemmedTokenize(a), stemmedTokenize(b))

    private fun hasUncertaintyMarker(text: String): Boolean {
        val words = tokenize(text)
        return UNCERTAINTY_MARKERS.any { marker ->
            if (marker.contains(' ')) text.lowercase().contains(marker) else words.contains(marker)
        }
    }

    private fun findContradiction(candidate: Sticker, pool: List<Sticker>): Sticker? {
        val candidateWords = stemmedTokenize(candidate.content)
        val candidateHasNegation = tokenize(candidate.content).any { it in NEGATION_MARKERS }
        val candidateNumbers = extractNumbers(candidate.content)

        for (existing in pool) {
            if (existing.id == candidate.id) continue
            val existingWords = stemmedTokenize(existing.content)
            val overlap = jaccard(candidateWords, existingWords)
            if (overlap < CONTRADICTION_JACCARD_THRESHOLD) continue

            val existingHasNegation = tokenize(existing.content).any { it in NEGATION_MARKERS }
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

    // Used only for Jaccard overlap in findContradiction, so Russian case/number
    // endings ("кота" vs "котов") don't artificially suppress the overlap score
    // before negation/number comparison even runs.
    private fun stemmedTokenize(text: String): Set<String> =
        tokenize(text).map { stem(it) }.toSet()

    private fun stem(word: String): String {
        for (suffix in SUFFIXES) {
            if (word.length - suffix.length >= MIN_ROOT_LENGTH && word.endsWith(suffix)) {
                return word.substring(0, word.length - suffix.length)
            }
        }
        return word
    }

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
