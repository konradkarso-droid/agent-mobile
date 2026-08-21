package com.uroboros.util

/**
 * Оценка "уместится ли текст в промпт" — родственно DataSieve.capBytes, но
 * НЕ режет текст, а только проверяет и возвращает вердикт (item 5b(c),
 * решение 2026-08-21): тихая обрезка запрещена там, где обрезанная часть
 * должна была бы реально компилироваться/выполняться — вместо этого
 * fail-closed (hard-fail-is-signal-not-noise), решение отдаётся вызывающей
 * задаче через PromptBudgetGate в ToteEngine.
 *
 * Оценка по байтам UTF-8, не по токенам: точного токенайзера движка
 * (gguf_lib) отсюда не видно, грубая оценка "байты как прокси токенов" уже
 * используется DataSieve для того же класса задач.
 *
 * DEFAULT_MAX_COMPONENT_BYTES — ПЛЕЙСХОЛДЕР, не откалиброван по реальному
 * окну модели (1.5-3B, заявлено 4k-8k токенов; system-prompt/BibleSoftWall
 * уже занимает ~1450 символов). Подлежит пересчёту тем же путём, что и
 * 8a-пороги (обратная эмерджентность, item 6), когда появятся реальные
 * данные о том, где модель реально начинает деградировать.
 */
object PromptBudget {

    const val DEFAULT_MAX_COMPONENT_BYTES = 6_000

    fun fits(text: String, maxBytes: Int = DEFAULT_MAX_COMPONENT_BYTES): Boolean =
        text.toByteArray(Charsets.UTF_8).size <= maxBytes

    fun sizeBytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size
}
