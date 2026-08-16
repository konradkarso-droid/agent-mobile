package com.uroboros.util

/**
 * Structural boundary detection for Kotlin source: finds function-level
 * start/end boundaries via a cheap on-device heuristic, and classifies
 * how much a surviving function shrank between two versions.
 *
 * Shared module — consumers: item 8a (step-success "useful volume" axis),
 * item 5b(c) (rolling context window must not truncate mid-function),
 * item 6b (extracting clean whole-function templates for the local
 * solved-problem store).
 *
 * Heuristic-only pass. Ambiguous cases are meant to escalate to a full
 * AST parse via kotlin-compiler-embeddable (through the existing Termux
 * RUN_COMMAND channel, TermuxKotlinCompiler) — that escalation path is
 * NOT implemented here yet (see AstEscalator stub below).
 */
object StructuralBoundary {

    data class FunctionSpan(
        val name: String,
        val startLine: Int, // inclusive, 0-based
        val endLine: Int,   // inclusive, 0-based
        val bodyLineCount: Int
    )

    // Matches "fun name(" possibly preceded by modifiers (private/suspend/etc),
    // possibly with a receiver type (extension functions), e.g.
    //   private suspend fun String.doThing(x: Int): Boolean {
    private val FUN_SIGNATURE = Regex("""\bfun\s+(?:[A-Za-z_][A-Za-z0-9_<>,.\s]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

    /**
     * Finds top-level and class-member named functions in [source].
     * Deliberately does NOT descend into lambdas/anonymous functions —
     * those are left to the AST escalation path, not this heuristic.
     *
     * Brace balance ignores braces inside string literals, char literals,
     * line comments, and block comments, to avoid the classic false-match
     * cases ("{}" in a string, "// {" in a comment, etc).
     */
    fun findFunctionSpans(source: String): List<FunctionSpan> {
        val lines = source.lines()
        val spans = mutableListOf<FunctionSpan>()

        var i = 0
        while (i < lines.size) {
            val match = FUN_SIGNATURE.find(lines[i])
            if (match != null) {
                val name = match.groupValues[1]
                val bodyStartSearchLine = i
                val openLine = findOpeningBraceLine(lines, bodyStartSearchLine)
                if (openLine != null) {
                    val closeLine = findMatchingCloseBraceLine(lines, openLine)
                    if (closeLine != null) {
                        spans.add(
                            FunctionSpan(
                                name = name,
                                startLine = i,
                                endLine = closeLine,
                                bodyLineCount = closeLine - openLine + 1
                            )
                        )
                        i = closeLine + 1
                        continue
                    }
                }
                // expression-body function (fun x() = ...) or unmatched brace
                // (single-expression syntax, no {} body) — no shrink-tracking
                // for these via this heuristic; skip, leave to AST path.
            }
            i++
        }
        return spans
    }

    /** Scans forward from [fromLine] for the first un-ignored '{'. */
    private fun findOpeningBraceLine(lines: List<String>, fromLine: Int): Int? {
        for (li in fromLine until lines.size) {
            if (stripNonCode(lines[li]).contains('{')) return li
        }
        return null
    }

    /** Scans forward from [openLine] tracking brace depth to find the matching close. */
    private fun findMatchingCloseBraceLine(lines: List<String>, openLine: Int): Int? {
        var depth = 0
        var started = false
        var inBlockComment = false
        for (li in openLine until lines.size) {
            val (clean, stillInBlockComment) = stripNonCodeTracked(lines[li], inBlockComment)
            inBlockComment = stillInBlockComment
            for (ch in clean) {
                when (ch) {
                    '{' -> { depth++; started = true }
                    '}' -> {
                        depth--
                        if (started && depth == 0) return li
                    }
                }
            }
        }
        return null
    }

    /** Single-line version (no cross-line block-comment state) for the opening-brace scan. */
    private fun stripNonCode(line: String): String = stripNonCodeTracked(line, false).first

    /**
     * Removes string/char literal contents and comments from [line] so brace
     * counting can't be fooled by braces inside them. Returns the cleaned
     * line plus whether a block comment is still open at end-of-line.
     * Deliberately simple — no raw-string ("""...""") or template-expression
     * ("${...}") handling; those stay ambiguous by design and are meant to
     * push the caller toward AST escalation rather than a wrong heuristic answer.
     */
    private fun stripNonCodeTracked(line: String, startInBlockComment: Boolean): Pair<String, Boolean> {
        val out = StringBuilder()
        var inBlockComment = startInBlockComment
        var inString = false
        var inChar = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            val next = if (i + 1 < line.length) line[i + 1] else null
            when {
                inBlockComment -> {
                    if (c == '*' && next == '/') { inBlockComment = false; i++ }
                }
                inString -> {
                    if (c == '\\') { i++ } // skip escaped char
                    else if (c == '"') { inString = false }
                }
                inChar -> {
                    if (c == '\\') { i++ }
                    else if (c == '\'') { inChar = false }
                }
                c == '/' && next == '/' -> return out.toString() to false // line comment: stop
                c == '/' && next == '*' -> { inBlockComment = true; i++ }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                else -> out.append(c)
            }
            i++
        }
        return out.toString() to inBlockComment
    }

    // --- Shrink verdict (item 8a's "useful volume" axis) ---

    enum class ShrinkVerdict { CLEAN, NEEDS_CONFIRMATION, SUSPICIOUS }

    data class ShrinkResult(
        val functionName: String,
        val beforeLines: Int,
        val afterLines: Int, // 0 if the function no longer exists
        val shrinkRatio: Double, // 0.0..1.0, meaningless if afterLines == 0
        val verdict: ShrinkVerdict
    )

    /**
     * Compares functions present in [before] against [after] by name.
     * A function that disappears entirely is NOT suspicious (clean removal).
     * A function that survives but shrank is classified via the gray zone.
     */
    fun evaluateShrink(before: String, after: String, thresholds: GrayZoneThresholds = GrayZoneThresholds.current): List<ShrinkResult> {
        val beforeSpans = findFunctionSpans(before).associateBy { it.name }
        val afterSpans = findFunctionSpans(after).associateBy { it.name }

        return beforeSpans.map { (name, beforeSpan) ->
            val afterSpan = afterSpans[name]
            if (afterSpan == null) {
                // whole function removed — clean by design, not scored further
                ShrinkResult(name, beforeSpan.bodyLineCount, 0, 0.0, ShrinkVerdict.CLEAN)
            } else {
                val ratio = if (beforeSpan.bodyLineCount == 0) 0.0
                    else 1.0 - (afterSpan.bodyLineCount.toDouble() / beforeSpan.bodyLineCount.toDouble())
                val verdict = when {
                    ratio <= thresholds.lowBound -> ShrinkVerdict.CLEAN
                    ratio >= thresholds.highBound -> ShrinkVerdict.SUSPICIOUS
                    else -> ShrinkVerdict.NEEDS_CONFIRMATION
                }
                ShrinkResult(name, beforeSpan.bodyLineCount, afterSpan.bodyLineCount, ratio.coerceIn(0.0, 1.0), verdict)
            }
        }
    }

    // --- Gray-zone thresholds: emergent, recalibrated from confirmed-clean data only ---

    data class GrayZoneThresholds(val lowBound: Double, val highBound: Double) {
        companion object {
            // Placeholder starting values — not derived from any external
            // statistic (none exists for this specific question). Meant to
            // be superseded by recalibrateFromConfirmedCleanRatios() once
            // enough AST-confirmed data exists (item 6 consolidation pass).
            private const val INITIAL_LOW = 0.20
            private const val INITIAL_HIGH = 0.50

            // Hard, non-recalibratable ceiling — bible principle #2:
            // a soft/cumulative signal (this recalibration) must never
            // outweigh a hard limit, only move within it.
            const val HARD_CEILING = 0.60

            var current: GrayZoneThresholds = GrayZoneThresholds(INITIAL_LOW, INITIAL_HIGH)
                private set

            /**
             * Recalibrates the gray-zone bounds from a set of shrink ratios
             * that have ALREADY been AST-confirmed as clean (not reviewPending).
             * Caller is responsible for that filtering — this function does
             * pure arithmetic only, no judgment call, so the model is
             * structurally outside this decision loop.
             *
             * highBound is clamped to HARD_CEILING no matter what the data says.
             */
            fun recalibrateFromConfirmedCleanRatios(confirmedCleanRatios: List<Double>) {
                if (confirmedCleanRatios.size < MIN_SAMPLES_FOR_RECALIBRATION) return
                val sorted = confirmedCleanRatios.sorted()
                val low = percentile(sorted, 0.70)  // most confirmed-clean shrinks fall below this
                val high = percentile(sorted, 0.95).coerceAtMost(HARD_CEILING)
                if (high > low) {
                    current = GrayZoneThresholds(low, high)
                }
                // if high <= low after clamping, keep previous thresholds —
                // don't install a degenerate/inverted gray zone
            }

            private const val MIN_SAMPLES_FOR_RECALIBRATION = 20

            private fun percentile(sorted: List<Double>, p: Double): Double {
                if (sorted.isEmpty()) return 0.0
                val idx = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
                return sorted[idx]
            }
        }
    }

    // --- AST escalation: NOT implemented yet, stub only ---

    /**
     * To be implemented: routes a NEEDS_CONFIRMATION/SUSPICIOUS ShrinkResult
     * to a full AST parse via kotlin-compiler-embeddable, run through the
     * existing Termux RUN_COMMAND channel (TermuxKotlinCompiler). Returns
     * true if the AST parse confirms the shrink was dead-code-only.
     */
    interface AstEscalator {
        suspend fun confirmClean(functionName: String, before: String, after: String): Boolean
    }
}
