package com.uroboros.will

import com.uroboros.safety.DeviceSafetyWatchdog
import com.uroboros.safety.SafetyZone

/**
 * Универсальный TOTE-цикл (Test-Operate-Test-Exit).
 * Не знает ничего о конкретном домене (кодинг, память и т.д.) — это решает вызывающий код
 * через StepTest/StepOperate. Использует EnergyBudget (item 7a) и защиту от зацикливания (item 8a).
 *
 * Мост с DeviceSafetyWatchdog (item 8, продолжение): перед каждым вызовом Operate
 * (обычно — обращение к LLM) цикл проверяет физическую зону. Это НЕ заменяет
 * независимый жёсткий обрыв внутри LlmEngine (defense in depth) — просто позволяет
 * циклу уйти в честную эвакуацию заранее, а не тратить попытку на заведомо
 * оборванный вызов модели.
 *
 * Условие успеха (2026-08-16): success ОДНОГО compile Y/N уже недостаточно —
 * item 8a ось 2 (структурная проверка "полезного объёма") может пометить формально
 * успешный шаг как usefulProgress=false (например, "успех" получен вырезанием рабочей
 * логики). Такой шаг НЕ завершает цикл — он идёт по обычному пути failure-обработки
 * (repeat-detection/energy damage/stuck-threshold), что и является прямой защитой
 * от reward-hacking на этой метрике.
 *
 * Item 9 (2026-08-18, первый проход): опциональный pendingQuerySource опрашивается
 * на каждом шве между test() и operate() — том же естественном месте, что и снимок
 * item 6b/8. При наличии запроса QueryUrgencyClassifier классифицирует его и
 * результат передаётся в queryHandler (если задан). ВАЖНО: сам цикл на этом этапе
 * НЕ прерывает operate() и не меняет своё поведение по решению классификатора —
 * это сознательно отложено (нужно отдельно спроектировать, как безопасно
 * останавливать уже идущий suspend-вызов operate() и как встраивать это в
 * ToteResult). Этот проход даёт только обнаружение+классификацию+точку расширения.
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

/** Item 9: хук, вызываемый при обнаружении запроса на шве test()/operate(). */
fun interface QueryHandler<S> {
    suspend fun onQuery(query: String, decision: QueryDecision, state: S, outcome: StepOutcome)
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
    private val watchdog: DeviceSafetyWatchdog,
    private val energyBudget: EnergyBudget = EnergyBudget(),
    private val repeatDetector: RepeatDetector = RepeatDetector { a, b -> a == b },
    private val maxIterations: Int = 20,
    private val stuckThreshold: Int = 5,
    private val pendingQuerySource: PendingQuerySource? = null,
    private val queryHandler: QueryHandler<S>? = null
) {
    suspend fun run(initialState: S, taskDescription: String = ""): ToteResult<S> {
        var state = initialState
        var lastSignature: String? = null
        var lastOutcome: StepOutcome? = null
        var consecutiveSimilar = 0
        var iteration = 0

        while (iteration < maxIterations) {
            iteration++
            val outcome = test.invoke(state)
            lastOutcome = outcome

            if (outcome.success && outcome.usefulProgress) {
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
                    val physicalZone = watchdog.zone.value
                    if (physicalZone == SafetyZone.CRITICAL) {
                        return ToteResult.Evacuated(
                            state, iteration,
                            "физическая защита: устройство в критической зоне ($physicalZone)",
                            lastOutcome
                        )
                    }

                    // Item 9: шов между test() и operate() — естественная точка для
                    // проверки внешнего запроса. Пока только обнаружение+классификация;
                    // реального прерывания/паузы цикла здесь ещё нет (см. комментарий класса).
                    if (pendingQuerySource != null) {
                        val query = pendingQuerySource.poll()
                        if (query != null) {
                            val decision = QueryUrgencyClassifier.classify(
                                query = query,
                                currentError = outcome.detail,
                                taskDescription = taskDescription
                            )
                            queryHandler?.onQuery(query, decision, state, outcome)
                        }
                    }

                    state = operate.invoke(state, outcome)
                }
            }
        }
        return ToteResult.HardStopped(state, iteration, lastOutcome)
    }
}
