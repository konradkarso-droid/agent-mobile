package com.uroboros.will

import com.uroboros.memory.RiskTrigger

/**
 * Item 9: классификатор входящих запросов пользователя на швах TOTE-цикла.
 * Каскад (variant C, зафиксировано 2026-08-18): override → грамматика → Jaccard.
 * Ось 2 (срочность) применяется только внутри heavy.
 */
sealed class QueryDecision {
    /** Запрос не связан с текущей задачей — ответить сразу, цикл не трогаем. */
    object Light : QueryDecision()
    /** Связан с задачей и короткий/резкий — прервать текущий шаг немедленно. */
    object HeavyUrgent : QueryDecision()
    /** Связан с задачей, но развёрнутый — дождаться ближайшего шва, не рвать шаг. */
    object HeavyDeferred : QueryDecision()
}

object QueryUrgencyClassifier {

    // Узкий фиксированный аварийный клапан (НЕ общий классификатор) — форсирует
    // HeavyUrgent независимо от остальных сигналов. Осознанно жёсткая,
    // не эмерджентная часть — тот же принцип, что и жёсткий потолок в item 8a.
    private val OVERRIDE_WORDS = setOf("стоп", "срочно", "важно")

    // Черновая заглушка (2026-08-18) — НЕ словарь смысловых слов, а морфологический
    // паттерн: первое слово запроса заканчивается на характерное окончание
    // повелительного наклонения. Тот же стиль, что RiskTrigger.SUFFIXES: дешёвая
    // эвристика без реальной морфологии.
    //
    // Обновление (2026-08-18, после первого прогона тестов): добавлена одиночная
    // "и" (например, "останови") — сознательно расширяет риск ложных HEAVY
    // (существительные/прилагательные тоже часто оканчиваются на "и"), но это
    // соответствует уже принятому в проекте принципу "при неоднозначности — heavy":
    // ложный HEAVY стоит лишней паузы, ложный LIGHT стоит пропущенной реальной
    // команды (например, "стоп"/"останови"), которую по чистой лексике (Jaccard)
    // поймать нечем — короткая голая команда обычно не пересекается по словам
    // ни с ошибкой, ни с описанием задачи.
    //
    // Список/порог подлежит калибровке позже через consolidation pass (item 6),
    // как и gray-zone в item 8a.
    private val IMPERATIVE_SUFFIXES = listOf("айте", "яйте", "уйте", "ите", "ьте", "ай", "яй", "уй", "и")
    private const val IMPERATIVE_MIN_ROOT_LENGTH = 3

    // Заглушки-пороги (2026-08-18, не финальные) — та же логика, что 8a gray-zone:
    // начинаем с грубого placeholder, уточняем позже по накопленным данным.
    private const val JACCARD_HEAVY_THRESHOLD = 0.2
    private const val SHORT_QUERY_CHAR_THRESHOLD = 40

    fun classify(query: String, currentError: String?, taskDescription: String): QueryDecision {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return QueryDecision.Light

        if (hasOverride(trimmed)) return QueryDecision.HeavyUrgent

        val isHeavy = when (grammarSignal(trimmed)) {
            GrammarSignal.HEAVY -> true
            GrammarSignal.LIGHT -> false
            GrammarSignal.AMBIGUOUS -> jaccardVerdict(trimmed, currentError, taskDescription)
        }

        if (!isHeavy) return QueryDecision.Light

        return if (trimmed.length <= SHORT_QUERY_CHAR_THRESHOLD) {
            QueryDecision.HeavyUrgent
        } else {
            QueryDecision.HeavyDeferred
        }
    }

    private fun hasOverride(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return words.any { it in OVERRIDE_WORDS }
    }

    private enum class GrammarSignal { HEAVY, LIGHT, AMBIGUOUS }

    private fun grammarSignal(text: String): GrammarSignal {
        val firstWord = text.lowercase()
            .split(Regex("[^\\p{L}]+"))
            .firstOrNull { it.isNotBlank() }
            ?: return GrammarSignal.AMBIGUOUS

        val looksImperative = IMPERATIVE_SUFFIXES.any { suffix ->
            firstWord.length - suffix.length >= IMPERATIVE_MIN_ROOT_LENGTH && firstWord.endsWith(suffix)
        }
        if (looksImperative) return GrammarSignal.HEAVY

        if (text.trimEnd().endsWith("?")) return GrammarSignal.LIGHT

        return GrammarSignal.AMBIGUOUS
    }

    private fun jaccardVerdict(query: String, currentError: String?, taskDescription: String): Boolean {
        val reference = if (currentError.isNullOrBlank()) {
            taskDescription
        } else {
            "$currentError $taskDescription"
        }
        // Fail-closed: нечего сравнивать — считаем heavy (см. зафиксированный принцип
        // "решения с последствиями по умолчанию консервативны").
        if (reference.isBlank()) return true
        return RiskTrigger.textSimilarity(query, reference) >= JACCARD_HEAVY_THRESHOLD
    }
}
