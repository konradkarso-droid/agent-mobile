package com.uroboros.will

import com.uroboros.util.StructuralBoundary

/**
 * Item 8a stage B (2026-08-17): when stage C (StructuralBoundary, construct-signal
 * heuristic) lands a function's shrink in the gray zone (NEEDS_CONFIRMATION), this
 * stage compiles BOTH the before and after versions of that single function through
 * the already-existing TermuxKotlinCompiler channel and compares real bytecode size —
 * a signal that text-level reformatting (whitespace, comments, cosmetic renaming)
 * cannot fake, unlike stage C's text-based heuristic.
 *
 * Cost note (user confirmed 2026-08-17): this doubles the RUN_COMMAND/Termux cost
 * (two compiles instead of one) but ONLY for gray-zone cases from stage C, not every
 * step — consistent with the project's escalate-only-when-ambiguous principle.
 *
 * Fail-closed: if either compile can't be measured (syntax error in an isolated
 * function extract, Termux unavailable, action denied), this stage does NOT guess —
 * it leaves the verdict as NEEDS_CONFIRMATION rather than resolving it either way,
 * consistent with the project's fail-closed-to-review pattern.
 *
 * Stage A (full AST via kotlin-compiler-embeddable) remains deferred until stronger
 * hardware (item 7b) — this stage is a middle ground that reuses the existing
 * compiler channel instead of adding a new heavy dependency.
 */
class BytecodeShrinkEscalator(private val compiler: TermuxKotlinCompiler) {

    /**
     * Refines [result] using bytecode size comparison. Only acts on
     * NEEDS_CONFIRMATION results with both before/after source available;
     * any other verdict (CLEAN, SUSPICIOUS, or missing source) passes through
     * unchanged — stage B only ever resolves ambiguity, never overrides a
     * confident stage-C verdict.
     */
    suspend fun refine(result: StructuralBoundary.ShrinkResult): StructuralBoundary.ShrinkResult {
        if (result.verdict != StructuralBoundary.ShrinkVerdict.NEEDS_CONFIRMATION) return result
        val beforeSource = result.beforeSource
        val afterSource = result.afterSource
        if (beforeSource == null || afterSource == null) return result

        val beforeMeasure = compiler.measureBytecodeSize(beforeSource)
        val afterMeasure = compiler.measureBytecodeSize(afterSource)

        val beforeSize = (beforeMeasure as? BytecodeMeasurement.Success)?.jarSizeBytes
        val afterSize = (afterMeasure as? BytecodeMeasurement.Success)?.jarSizeBytes
        if (beforeSize == null || afterSize == null || beforeSize == 0L) {
            // Не удалось измерить одну или обе стороны — остаёмся в серой зоне,
            // а не гадаем (fail-closed-to-review).
            return result
        }

        val bytecodeRatio = 1.0 - (afterSize.toDouble() / beforeSize.toDouble())
        val textRatio = result.shrinkRatio

        val refinedVerdict = if (textRatio - bytecodeRatio >= COSMETIC_GAP_THRESHOLD) {
            // Байткод сократился заметно меньше, чем текст, — "урезание" в тексте
            // было в основном косметическим (пробелы/комментарии/переформулировка),
            // не настоящей логикой. Понижаем до CLEAN.
            StructuralBoundary.ShrinkVerdict.CLEAN
        } else {
            // Байткод сократился сопоставимо (или сильнее) текста — сокращение
            // было реальным. Подтверждаем подозрение stage C.
            StructuralBoundary.ShrinkVerdict.SUSPICIOUS
        }

        return result.copy(verdict = refinedVerdict)
    }

    companion object {
        // Насколько ниже должен быть bytecode-ratio относительно text-ratio, чтобы
        // считать разницу "чисто косметической". Плейсхолдер того же статуса, что и
        // границы серой зоны в StructuralBoundary — не выведен из внешней статистики,
        // открыт для калибровки позже.
        private const val COSMETIC_GAP_THRESHOLD = 0.20
    }
}
