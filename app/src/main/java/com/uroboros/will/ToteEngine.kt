package com.uroboros.will

import com.uroboros.safety.SafetyZoneSource
import com.uroboros.safety.SafetyZone

/**
 * Универсальный TOTE-цикл (Test-Operate-Test-Exit).
 * Не знает ничего о конкретном домене (кодинг, память и т.д.) — это решает вызывающий код
 * через StepTest/StepOperate. Использует EnergyBudget (item 7a) и защиту от зацикливания (item 8a).
 *
 * Мост с физическим предохранителем (item 8, продолжение): перед каждым вызовом
 * Operate (обычно — обращение к LLM) цикл проверяет физическую зону. Это НЕ заменяет
 * независимый жёсткий обрыв внутри LlmEngine (defense in depth) — просто позволяет
 * циклу уйти в честную эвакуацию заранее, а не тратить попытку на заведомо
 * оборванный вызов модели.
 *
 * Зависимость объявлена как SafetyZoneSource, а не как DeviceSafetyWatchdog.
 * Причина техническая: настоящий watchdog в конструкторе требует android.Context и
 * сразу дёргает getSystemService(POWER_SERVICE), поэтому в JVM-юнит-тесте его не
 * создать, а без этого сам цикл непроверяем. Параметр намеренно оставлен
 * ОБЯЗАТЕЛЬНЫМ (не nullable с дефолтом): подменить источник зоны можно только
 * осознанно, "забыть" физическую проверку нельзя. В бою сюда передаётся
 * DeviceSafetyWatchdog.
 *
 * Условие успеха: success одного compile Y/N уже недостаточно — item 8a ось 2
 * (структурная проверка "полезного объёма") может пометить формально успешный шаг
 * как usefulProgress=false (например, "успех" получен вырезанием рабочей логики).
 * Такой шаг НЕ завершает цикл — он идёт по обычному пути обработки неудачи
 * (повторы / урон энергии / порог застревания), что и является прямой защитой от
 * reward-hacking на этой метрике.
 *
 * === Связка шкалы урона с порогом застревания ===
 *
 * Это одна настройка, а не два независимых числа: порознь они не калибруются.
 * Порог застревания достижим лишь тогда, когда урон за повторы копится медленнее,
 * чем тратится бюджет энергии. При ПЛОСКОМ уроне за повтор порог может оказаться
 * арифметически недостижимым: энергия кончится раньше, чем счётчик до него дойдёт,
 * цикл будет всегда уходить по энергии, а защита от залипания не сработает ни разу —
 * оставаясь при этом с виду исправной. Поэтому урон за повтор нарастающий
 * (EnergyBudget.applyRepeatDamage). Менять одно из двух чисел, не пересчитав второе,
 * нельзя.
 *
 * Что гарантируется — неравенство, а не проценты: при нынешней шкале барьер
 * застревания срабатывает раньше, чем исчерпывается энергия, в обеих ветках — и
 * когда первая неудача стоила MEDIUM, и когда она стоила ноль. Держаться надо этого
 * неравенства. Конкретные остатки энергии закреплены ToteEngineCalibrationTest, и
 * при перекалибровке сверяться надо с ним, а не с числом в чьём-нибудь тексте.
 *
 * Нынешние значения и их область:
 *  - stuckThreshold = 3 — это три ПОВТОРА, то есть четыре одинаковых неудачи подряд:
 *    первая неудача задаёт подпись и повтором не считается (см. consecutiveSimilar в
 *    run()). Достижимость порога закреплена ToteEngineCalibrationTest.
 *  - maxIterations = 10 — потолок, назначенный по наблюдениям на модели размера 1.5B:
 *    попытки сверх десятка давали не сходимость, а блуждание, потому что упирается
 *    это в качество обратной связи в промпте, а не в число попыток. Цена одной
 *    итерации — генерация плюс запуск kotlinc, то есть минуты работы и нагрев. На
 *    модели другого размера значение не перепроверялось. Признак, что потолок мал:
 *    цикл регулярно доходит до HardStopped. Признак, что велик: успех стабильно
 *    наступает за две-три итерации.
 *
 * Порядок проверок в run() — сначала застревание, потом энергия — не переставляется:
 * жёсткий барьер обязан срабатывать раньше мягкой деградации.
 *
 * Чего защита от залипания НЕ умеет: repeatDetector сравнивает подпись только с
 * НЕПОСРЕДСТВЕННО предыдущей. Чередование двух ошибок (A, B, A, B) повтором не
 * считается ни разу — consecutiveSimilar сбрасывается в ноль на каждом шаге,
 * нарастающий урон не начисляется, и из трёх барьеров остаётся один, maxIterations.
 * Ограничение известное; чем его закрывать, не решено.
 *
 * Item 9: опциональный pendingQuerySource опрашивается на шве между test() и
 * operate() — том же естественном месте, что и снимок item 6b/8. При наличии запроса
 * QueryUrgencyClassifier классифицирует его, результат уходит в queryHandler.
 * Границ у этого механизма две. Первая: цикл НЕ прерывает operate() и не меняет
 * своё поведение по решению классификатора — есть только обнаружение, классификация
 * и точка расширения. Вторая: шов пропускается целиком на той итерации, где test()
 * вернул успех, — вопрос, заданный во время последней (успешной) итерации, до
 * классификатора не дойдёт вовсе.
 *
 * Item 5b(c): опциональный promptBudgetGate — общий (не только для кодинга) барьер
 * на том же шве, ДО вызова operate(). Движок по-прежнему ничего не знает про
 * промпты/токены/LLM — только получает вердикт Allow/Reject от вызывающей задачи.
 * Решение (не домен движка): при превышении бюджета — fail-closed немедленной
 * эвакуацией, БЕЗ попытки операции и БЕЗ ожидания порога застревания. Тихая обрезка
 * запрещена (hard-fail-is-signal-not-noise): если задача не помещается в бюджет
 * промпта целиком, правка вслепую — правка того, чего модель не видит — опаснее
 * отказа.
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
    private val watchdog: SafetyZoneSource,
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

            // Повтор и разовая неудача считаются по разным шкалам: цена повтора растёт
            // с числом повторов подряд. Почему эта шкала неотделима от порога
            // застревания — см. комментарий класса.
            if (isRepeat) {
                energyBudget.applyRepeatDamage(consecutiveSimilar)
            } else {
                energyBudget.applyDamage(
                    if (!outcome.usefulProgress) ErrorSeverity.MEDIUM else ErrorSeverity.LIGHT
                )
            }

            if (consecutiveSimilar >= stuckThreshold) {
                // На экран идёт число ОДИНАКОВЫХ НЕУДАЧ, а не повторов, поэтому +1:
                // первая неудача задаёт подпись и повтором не считается, так что при
                // stuckThreshold=3 их случилось четыре. Без прибавки строка сообщала
                // пользователю на единицу меньше, чем произошло на самом деле.
                //
                // Форма "×N" выбрана, чтобы обойтись без склонения: при перекалибровке
                // порога число меняется, а "4 неудачи" и "5 неудач" требуют разных
                // окончаний, и правильная сегодня строка стала бы неграмотной завтра.
                return ToteResult.Evacuated(
                    state, iteration,
                    "застряли: одна и та же неудача подряд ×${consecutiveSimilar + 1}",
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

                    // Item 5b(c): бюджет промпта проверяется ДО operate(), и отказ
                    // мгновенный — без ожидания порога повторов: если задача не
                    // помещается в промпт, это ясно уже на этом шаге, а лишние круги
                    // стоят генераций и запусков kotlinc. Почему именно fail-closed —
                    // см. комментарий класса.
                    val budgetVerdict = promptBudgetGate?.evaluate(state, outcome)
                    if (budgetVerdict is BudgetVerdict.Reject) {
                        return ToteResult.Evacuated(
                            state, iteration,
                            "бюджет промпта: ${budgetVerdict.reason}",
                            lastOutcome
                        )
                    }

                    // Item 9: шов между test() и operate() — точка проверки внешнего
                    // запроса. Только обнаружение и классификация; прерывания цикла
                    // здесь нет, границы механизма — в комментарии класса.
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
