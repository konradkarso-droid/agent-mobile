package com.uroboros.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Юнит-тесты StructuralBoundary (item 8a, ось 2) — проверяют логику вердиктов
 * напрямую, без реального Termux/LLM. Запускаются через ./gradlew test (Android CI),
 * без Codespaces.
 */
class StructuralBoundaryTest {

    private val defaultThresholds = StructuralBoundary.GrayZoneThresholds(lowBound = 0.20, highBound = 0.50)

    @Test
    fun `whole function removal is CLEAN, not suspicious`() {
        val before = """
            fun helper(): Int {
                val x = 1
                val y = 2
                return x + y
            }
        """.trimIndent()
        val after = "" // функция целиком исчезла

        val results = StructuralBoundary.evaluateShrink(before, after)
        assertEquals(1, results.size)
        assertEquals(StructuralBoundary.ShrinkVerdict.CLEAN, results[0].verdict)
        assertEquals(0, results[0].afterLines)
    }

    @Test
    fun `small shrink under low bound is CLEAN`() {
        // 6 строк тела -> 5 строк тела: убрали один пустой/лишний коммент, ~17% сокращения
        val before = buildFunction(bodyLines = 6)
        val after = buildFunction(bodyLines = 5)

        val results = StructuralBoundary.evaluateShrink(before, after, defaultThresholds)
        assertEquals(1, results.size)
        assertEquals(StructuralBoundary.ShrinkVerdict.CLEAN, results[0].verdict)
    }

    @Test
    fun `mid-range shrink falls into NEEDS_CONFIRMATION gray zone`() {
        // 10 строк тела -> 6 строк тела = 40% сокращения, между 20% и 50%
        val before = buildFunction(bodyLines = 10)
        val after = buildFunction(bodyLines = 6)

        val results = StructuralBoundary.evaluateShrink(before, after, defaultThresholds)
        assertEquals(1, results.size)
        assertEquals(StructuralBoundary.ShrinkVerdict.NEEDS_CONFIRMATION, results[0].verdict)
    }

    @Test
    fun `heavy shrink above high bound is SUSPICIOUS`() {
        // 10 строк тела -> 3 строки тела = 70% сокращения, выше 50%
        val before = buildFunction(bodyLines = 10)
        val after = buildFunction(bodyLines = 3)

        val results = StructuralBoundary.evaluateShrink(before, after, defaultThresholds)
        assertEquals(1, results.size)
        assertEquals(StructuralBoundary.ShrinkVerdict.SUSPICIOUS, results[0].verdict)
    }

    @Test
    fun `braces inside string literals do not confuse boundary detection`() {
        val before = """
            fun greet(): String {
                val template = "{}"
                return template
            }
        """.trimIndent()
        val after = """
            fun greet(): String {
                return "{}"
            }
        """.trimIndent()

        val results = StructuralBoundary.evaluateShrink(before, after, defaultThresholds)
        assertEquals(1, results.size)
        // 3 строки тела -> 2 строки тела = ~33%, попадает в серую зону, но
        // главное здесь — что функция вообще правильно найдена целиком
        // (без ложного обрыва на "{}" внутри строки).
        assertEquals("greet", results[0].functionName)
    }

    @Test
    fun `recalibration respects hard ceiling regardless of data`() {
        // Специально "нечестные" данные, которые толкали бы highBound к 90%
        val skewedRatios = List(25) { 0.85 + it * 0.001 }

        StructuralBoundary.GrayZoneThresholds.recalibrateFromConfirmedCleanRatios(skewedRatios)

        val recalibrated = StructuralBoundary.GrayZoneThresholds.current
        assert(recalibrated.highBound <= StructuralBoundary.GrayZoneThresholds.HARD_CEILING) {
            "highBound (${recalibrated.highBound}) must never exceed HARD_CEILING " +
                "(${StructuralBoundary.GrayZoneThresholds.HARD_CEILING})"
        }
    }

    @Test
    fun `recalibration with too few samples is ignored`() {
        val before = StructuralBoundary.GrayZoneThresholds.current
        StructuralBoundary.GrayZoneThresholds.recalibrateFromConfirmedCleanRatios(listOf(0.1, 0.2, 0.3))
        val after = StructuralBoundary.GrayZoneThresholds.current
        assertEquals(before, after)
    }

    /** Строит функцию с заданным числом строк в теле, для контролируемого сравнения. */
    private fun buildFunction(bodyLines: Int): String {
        val body = (1..bodyLines).joinToString("\n") { "    val v$it = $it" }
        return "fun sample(): Int {\n$body\n    return 0\n}"
    }
}
