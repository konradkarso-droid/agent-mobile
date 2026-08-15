package com.uroboros.will

/**
 * Универсальный TOTE-цикл (Test-Operate-Test-Exit).
 * Не знает ничего о конкретном домене (кодинг, память и т.д.) — это решает вызывающий код
 * через StepTest/StepOperate. Использует EnergyBudget (item 7a) и защиту от зацикливания (item 8a).
 */

data class StepOutcome(
    val success: Boolean,
    val usefulProgress: Boolean,
    val signature: String,
    val detail: String = ""
)

fun interface StepTest<S> {
    suspend fun invoke(state: S): StepOutcome
}

fun interface StepOperate<S> {
    suspend fun invoke(state: S, outcome: StepOutcome): S
}

fun interface RepeatDetector {
    fun isSameFailure(a: String, b: String): Boolean
}

sealed class ToteResult<out S> {
    data class Success<S>(val finalState: S, val iterations: Int) : ToteResult<S>()
    data class Evacuated<S>(
        val lastState: S,
        val iterations: Int,
        val reason: String,
        val lastOutcome: StepOutcome?
    ) : ToteResult<S>()
    data class HardStopped<S>(
        val lastState: S,
        val iterations: Int,
        val lastOutcome: StepOutcome?
    ) : ToteResult<S>()
}

class ToteEngine<S>(
    private val test: StepTest<S>,
    private val operate: StepOperate<S>,
    private val energyBudget: EnergyBudget = EnergyBudget(),
    private val repeatDetector: RepeatDetector = RepeatDetector { a, b -> a == b },
    private val maxIterations: Int = 20,
    private val stuckThreshold: Int = 5
) {
    suspend fun run(initialState: S): ToteResult<S> {
        var state = initialState
        var lastSignature: String? = null
        var lastOutcome: StepOutcome? = null
        var consecutiveSimilar = 0
        var iteration = 0

        while (iteration < maxIterations) {
            iteration++
            val outcome = test.invoke(state)
            lastOutcome = outcome

            if (outcome.success) {
                return ToteResult.Success(state, iteration)
            }

            val isRepeat = lastSignature != null &&
                repeatDetector.isSameFailure(outcome.signature, lastSignature)
            consecutiveSimilar = if (isRepeat) consecutiveSimilar + 1 else 0
            lastSignature = outcome.signature

            val severity = when {
                isRepeat -> ErrorSeverity.SEVERE
                !outcome.usefulProgress -> ErrorSeverity.MEDIUM
                else -> ErrorSeverity.LIGHT
            }
            energyBudget.applyDamage(severity)

            if (consecutiveSimilar >= stuckThreshold) {
                return ToteResult.Evacuated(
                    state, iteration,
                    "застряли: $consecutiveSimilar похожих неудач подряд",
                    lastOutcome
                )
            }

            when (energyBudget.zone) {
                WillZone.EVACUATION -> return ToteResult.Evacuated(
                    state, iteration,
                    "энергия исчерпана (${energyBudget.energy.value}%)",
                    lastOutcome
                )
                WillZone.REFLECTION, WillZone.STORM -> {
                    state = operate.invoke(state, outcome)
                }
            }
        }
        return ToteResult.HardStopped(state, iteration, lastOutcome)
    }
}
