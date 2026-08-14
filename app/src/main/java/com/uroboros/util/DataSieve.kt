package com.uroboros.util

/**
 * Универсальный механизм ограничения объёма данных ("молекулярное сито") —
 * используется везде, где нужно гарантированно ограничить размер текста
 * перед тем, как передать его дальше (модели, в лог, в UI и т.д.).
 * Не выбрасывает исключений — всегда возвращает валидный результат.
 */
object DataSieve {

    /**
     * Обрезает текст так, чтобы его UTF-8 представление не превышало maxBytes.
     * Не разрывает многобайтовый символ посередине.
     * Если текст был обрезан — дописывает понятную пометку в конце.
     */
    fun capBytes(text: String, maxBytes: Int, marker: String = "…[обрезано]"): String {
        val fullBytes = text.toByteArray(Charsets.UTF_8)
        if (fullBytes.size <= maxBytes) return text

        val markerBytes = marker.toByteArray(Charsets.UTF_8)
        val budget = (maxBytes - markerBytes.size).coerceAtLeast(0)

        var cut = budget.coerceAtMost(fullBytes.size)
        // не резать посередине UTF-8 символа: пятимся, пока не встанем
        // на начало символа (байт вида 10xxxxxx — это продолжение символа)
        while (cut > 0 && (fullBytes[cut].toInt() and 0xC0) == 0x80) {
            cut--
        }

        val truncated = String(fullBytes, 0, cut, Charsets.UTF_8)
        return truncated + marker
    }

    /** То же самое, но ограничение по количеству строк, а не байтам. */
    fun capLines(text: String, maxLines: Int, marker: String = "…[обрезано]"): String {
        val lines = text.lines()
        if (lines.size <= maxLines) return text
        return lines.take(maxLines).joinToString("\n") + "\n" + marker
    }
}
