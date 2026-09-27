package com.uroboros.will

import com.uroboros.memory.RiskTrigger
import com.uroboros.util.ImperativeForm

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

    // Повелительное по первому слову — общее правило util.ImperativeForm (там
    // же — почему в списке одиночная «и» и чего правило не умеет).

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

        if (ImperativeForm.looksImperative(firstWord)) return GrammarSignal.HEAVY

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
