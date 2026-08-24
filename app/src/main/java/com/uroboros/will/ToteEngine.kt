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
 * Калибровка 2026-08-23 (первый живой прогон на Mi 10T, item 8a ось 3).
 * Прогон встал по энергии на 6-й итерации, а не по застреванию. Разбор показал, что
 * stuckThreshold=5 был НЕДОСТИЖИМ: плоский SEVERE=30 за каждый повтор сжигал бюджет
 * за три совпадения, поэтому consecutiveSimilar физически не мог дорасти до 5.
 * Что изменено:
 *   - урон за повтор теперь нарастающий (EnergyBudget.applyRepeatDamage), а не плоский;
 *   - stuckThreshold 5 -> 3: три одинаковых провала подряд — это уже честное залипание;
 *   - maxIterations 20 -> 10: при maxTokens=512 каждая итерация это генерация плюс
 *     запуск kotlinc, то есть минуты и нагрев; на 1.5B модели попытки сверх десятка
 *     дают не сходимость, а блуждание — сходимость упирается в качество обратной
 *     связи в промпте, а не в число попыток.
 * Порядок проверок (сначала застревание, потом энергия) НЕ менялся — он и так был верным:
 * жёсткий барьер обязан срабатывать раньше мягкой деградации.
 *
 * Item 9 (2026-08-18, первый проход): опциональный pendingQuerySource опрашивается
 * на каждом шве между test() и operate() — том же естественном месте, что и снимок
 * item 6b/8. При наличии запроса QueryUrgencyClassifier классифицирует его и
 * результат передаётся в queryHandler (если задан). ВАЖНО: сам цикл на этом этапе
 * НЕ прерывает operate() и не меняет своё поведение по решению классификатора —
 * это сознательно отложено (нужно отдельно спроектировать, как безопасно
 * останавливать уже идущий suspend-вызов operate() и как встраивать это в
 * ToteResult). Этот проход даёт только обнаружение+классификацию+точку расширения.
 *
 * Item 5b(c) (2026-08-21): опциональный promptBudgetGate — общий (не только для
 * кодинга) барьер на том же шве test()/operate(), ДО вызова operate(). Движок
 * по-прежнему ничего не знает про промпты/токены/LLM — только получает вердикт
 * Allow/Reject от вызывающей задачи. Решение (не мой домен): при превышении
 * бюджета — fail-closed немедленной эвакуацией, БЕЗ попытки операции и БЕЗ
 * ожидания stuckThreshold — тихая обрезка запрещена (hard-fail-is-signal-not-noise):
 * если задача не помещается в бюджет промпта целиком, попытка правки вслепую
 * (правка того, чего модель не видит) опаснее отказа.
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

/** Item 5b(c): вердикт бюджет-гейта — движок не интерпретирует reason, только ветвится по типу. */
sealed class BudgetVerdict {
    object Allow : BudgetVerdict()
    data class Reject(val reason: String) : BudgetVerdict()
}

/** Item 5b(c): domain-specific проверка "поместится ли это в промпт" — движок общий, гейт — нет. */
fun interface PromptBudgetGate<S> {
    fun evaluate(state: S, outcome: StepOutcome): BudgetVerdict
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
    private val maxIterations: Int = 10,
    private val stuckThreshold: Int = 3,
    private val pendingQuerySource: PendingQuerySource? = null,
    private val queryHandler: QueryHandler<S>? = null,
    private val promptBudgetGate: PromptBudgetGate<S>? = null
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

            // Повтор считается отдельно от разовой неудачи: его цена растёт с числом
            // повторов подряд, а не бьёт плоской суммой с первого же совпадения.
            if (isRepeat) {
                energyBudget.applyRepeatDamage(consecutiveSimilar)
            } else {
                energyBudget.applyDamage(
                    if (!outcome.usefulProgress) ErrorSeverity.MEDIUM else ErrorSeverity.LIGHT
                )
            }

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

                    // Item 5b(c): бюджет промпта — ДО operate(), не дожидаясь
                    // stuckThreshold. Отказ мгновенный и однократный: если задача
                    // не помещается в промпт, это ясно уже на этом шаге, гонять
                    // до порога повторов ради того же вывода — трата ресурсов
                    // (Termux-вызовов/генераций), которых и так не хватает.
                    val budgetVerdict = promptBudgetGate?.evaluate(state, outcome)
                    if (budgetVerdict is BudgetVerdict.Reject) {
                        return ToteResult.Evacuated(
                            state, iteration,
                            "бюджет промпта: ${budgetVerdict.reason}",
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
