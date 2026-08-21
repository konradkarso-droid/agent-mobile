package com.uroboros.will.tasks

import com.dark.gguf_lib.models.GenerationEvent
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.TrustedMediator
import com.uroboros.safety.DeviceSafetyWatchdog
import com.uroboros.util.PromptBudget
import com.uroboros.util.StructuralBoundary
import com.uroboros.will.BudgetVerdict
import com.uroboros.will.BytecodeShrinkEscalator
import com.uroboros.will.CompileResult
import com.uroboros.will.PendingQuerySource
import com.uroboros.will.PromptBudgetGate
import com.uroboros.will.QueryDecision
import com.uroboros.will.QueryHandler
import com.uroboros.will.StepOperate
import com.uroboros.will.StepOutcome
import com.uroboros.will.StepTest
import com.uroboros.will.ToteEngine
import com.uroboros.will.ToteResult
import com.uroboros.will.TermuxKotlinCompiler
import kotlinx.coroutines.flow.collect

/**
 * Кодинг-инстанциация общего TOTE-цикла (item 7a) — первая конкретная реализация
 * StepTest/StepOperate поверх EnergyBudget+ToteEngine+TermuxKotlinCompiler+LlmEngine.
 *
 * Фаза 6 (2026-08-18): подключён снимок последнего стабильного состояния (item 6b/8,
 * `LastStableSnapshot` через `TrustedMediator`). Два решения, принятых явно, не по
 * умолчанию: (1) снимок сохраняется ТОЛЬКО когда usefulProgress=true на ветке Success
 * (не при SUSPICIOUS/NEEDS_CONFIRMATION) — снимок должен быть надёжной точкой, а не
 * "успешным, но подозрительным" кодом; (2) при эвакуации снимок только читается и
 * попадает в вакцина-строку для видимости — автоматического отката/продолжения с
 * сохранённого места пока нет (это уже про item 9, не спроектирован).
 *
 * Структурная проверка (stage C→B) по-прежнему вызывается ТОЛЬКО на ветке
 * CompileResult.Success (фаза 4, 2026-08-17) — на CompileFailure success уже false
 * и так блокирует ложный успех, а сломанный промежуточный код чаще всего не
 * распознаётся структурным парсером вообще.
 *
 * Item 9, второй проход (2026-08-21): pendingQuerySource + queryHandler теперь
 * доведены до реального ответа пользователю, не только до классификации.
 * queryHandler генерирует ответ через llmEngine.generateFlow(query) и отдаёт его
 * наружу через опциональный onAnswer — UI-слой (MainActivity) сам решает, куда его
 * показать. ВАЖНО: пауза/приоритет доставки по-прежнему не реализованы (решение
 * 2026-08-20 из backlog: HeavyUrgent/HeavyDeferred не останавливают operate() —
 * цикл продолжает идти в любом случае), поэтому ответ генерируется и доставляется
 * одинаково для всех трёх решений классификатора — разница видна только в тексте
 * label перед ответом. Реальный побочный эффект, который стоит иметь в виду:
 * каждый заданный вопрос добавляет ещё один вызов LLM внутри текущей итерации,
 * ДО operate() — на слабом устройстве это заметная лишняя нагрузка за итерацию.
 *
 * Item 5b(c) (2026-08-21): promptBudgetGate проверяет размер state.code ДО
 * вызова operate() — fail-closed (Evacuated), НЕ тихая обрезка через
 * StructuralBoundary. Решение осознанное: если модель не видит весь код
 * целиком, попытка правки вслепую опаснее отказа (hard-fail-is-signal-not-noise) —
 * тот же класс риска, что уже ловили на getContext()-баге (модель отвечает
 * не по тому, что реально в системе). Лимит из PromptBudget — плейсхолдер,
 * не откалиброван.
 *
 * "Мост памяти" (item 7a продолжение): результат цикла сохраняется в Sticker-память
 * через TrustedMediator — "вакцина-строка". Важность записи (обратная эмерджентность
 * от числа итераций) пока НЕ реализована — используется дефолтная важность, это
 * отдельный следующий шаг.
 */

data class KotlinCodeState(
    val code: String,
    val lastError: String? = null,
    // Код ДО последней правки operate() — нужен для структурного сравнения в test().
    // null на самом первом шаге: сравнивать не с чем, и это не подозрительно по дизайну.
    val previousCode: String? = null
)

/** Явный исход структурной проверки — чтобы отличать "не сработала" от "сработала и чисто". */
private sealed class StructuralCheckOutcome {
    object NoPreviousCode : StructuralCheckOutcome()
    object FunctionsNotParsed : StructuralCheckOutcome()
    object NoSignificantChange : StructuralCheckOutcome()
    data class Flagged(val result: StructuralBoundary.ShrinkResult) : StructuralCheckOutcome()
}

class KotlinCodingTask(
    private val termuxCompiler: TermuxKotlinCompiler,
    private val llmEngine: LlmEngine,
    private val mediator: TrustedMediator,
    private val watchdog: DeviceSafetyWatchdog,
    private val bytecodeEscalator: BytecodeShrinkEscalator = BytecodeShrinkEscalator(termuxCompiler),
    private val pendingQuerySource: PendingQuerySource? = null,
    // Item 9 (2026-08-21): вызывается с готовым текстом ответа после того, как
    // queryHandler сгенерировал его через LLM. null по умолчанию — тот же паттерн,
    // что pendingQuerySource: канал наружу опционален, класс не обязан знать про UI.
    private val onAnswer: (suspend (String) -> Unit)? = null
) {

    private val debugLog = mutableListOf<String>()

    // Захардкоженное описание подзадачи для тестового сценария (см. run()) —
    // когда цикл будет получать реальные задачи, это должно приходить извне,
    // а не быть константой класса.
    private val taskDescription =
        "Исправить функцию sumPositive: она должна суммировать положительные числа списка."

    /** Детальный лог по каждой итерации TOTE-цикла — компиляция + вердикты C/B. */
    fun getDebugLog(): String =
        if (debugLog.isEmpty()) "Лог пуст (цикл ещё не запускался)" else debugLog.joinToString("\n\n---\n\n")

    /**
     * Item 9, второй проход: раньше только логировал решение классификатора.
     * Теперь реально отвечает — генерирует текст через llmEngine и передаёт его в
     * onAnswer, если он задан. label оставлен как есть, для наблюдаемости в логе.
     */
    private val queryHandler = QueryHandler<KotlinCodeState> { query, decision, _, _ ->
        val label = when (decision) {
            QueryDecision.Light -> "LIGHT (не связан с задачей)"
            QueryDecision.HeavyUrgent -> "HEAVY_URGENT (связан с задачей, короткий)"
            QueryDecision.HeavyDeferred -> "HEAVY_DEFERRED (связан с задачей, развёрнутый)"
        }
        debugLog.add("[item9] Запрос: \"$query\" -> $label")

        val answerText = StringBuilder()
        var generationError: String? = null
        llmEngine.generateFlow(query, maxTokens = 150).collect { event ->
            when (event) {
                is GenerationEvent.Token -> answerText.append(event.text)
                is GenerationEvent.Error -> generationError = event.message
                else -> Unit
            }
        }
        val finalAnswer = if (generationError != null) {
            "[Ошибка генерации: $generationError]"
        } else {
            answerText.toString()
        }
        debugLog.add("[item9] Ответ ($label): $finalAnswer")
        onAnswer?.invoke("[$label]\n$finalAnswer")
    }

    /**
     * Item 5b(c): единственная проверка — влезает ли state.code в бюджет промпта.
     * Намеренно НЕ проверяет outcome.detail здесь — это отдельная забота
     * (stderr уже капается в TermuxKotlinCompiler через DataSieve, MAX_OUTPUT_BYTES=8000),
     * смешивать два независимых бюджета в одну проверку — то же нарушение
     * separated-continuity, которого проект избегает везде.
     */
    private val promptBudgetGate = PromptBudgetGate<KotlinCodeState> { state, _ ->
        if (PromptBudget.fits(state.code)) {
            BudgetVerdict.Allow
        } else {
            BudgetVerdict.Reject(
                "код (${PromptBudget.sizeBytes(state.code)} байт) не помещается в бюджет " +
                    "промпта (${PromptBudget.DEFAULT_MAX_COMPONENT_BYTES} байт) — правка вслепую " +
                    "невозможна, отказ вместо тихой обрезки"
            )
        }
    }

    private val test = StepTest<KotlinCodeState> { state ->
        val iterationNum = debugLog.size + 1
        when (val result = termuxCompiler.compile(state.code)) {
            is CompileResult.Success -> {
                val outcome = resolveStructuralVerdict(state)
                val structural = (outcome as? StructuralCheckOutcome.Flagged)?.result
                logIteration(iterationNum, "компиляция: УСПЕХ", outcome)
                when (structural?.verdict) {
                    StructuralBoundary.ShrinkVerdict.SUSPICIOUS -> StepOutcome(
                        success = true,
                        usefulProgress = false,
                        signature = "OK",
                        detail = "${result.stdout}\n[структурная проверка: подозрительное " +
                            "сокращение функции '${structural.functionName}' " +
                            "(${(structural.shrinkRatio * 100).toInt()}%) — компиляция прошла, " +
                            "но прогресс НЕ засчитан из-за риска потери логики]"
                    )
                    StructuralBoundary.ShrinkVerdict.NEEDS_CONFIRMATION -> StepOutcome(
                        success = true,
                        // Это состояние означает, что даже после эскалации в stage B
                        // (BytecodeShrinkEscalator) вердикт остался неопределённым —
                        // либо одна из двух компиляций байткода не удалась, либо
                        // Termux/ActionGate отказали. Fail-closed: не блокируем, но
                        // явно помечаем как требующее подтверждения.
                        usefulProgress = true,
                        signature = "OK",
                        detail = "${result.stdout}\n[структурная проверка: серая зона для " +
                            "'${structural.functionName}' (${(structural.shrinkRatio * 100).toInt()}%) " +
                            "не разрешена даже после байткод-эскалации]"
                    )
                    else -> {
                        // Чистый, надёжный успех — единственный случай, когда снимок
                        // последнего стабильного состояния имеет смысл перезаписывать
                        // (item 6b/8, решение 2026-08-18: не при SUSPICIOUS/NEEDS_CONFIRMATION).
                        mediator.saveStableSnapshot(state.code)
                        StepOutcome(
                            success = true,
                            usefulProgress = true,
                            signature = "OK",
                            detail = result.stdout
                        )
                    }
                }
            }
            is CompileResult.CompileFailure -> {
                // Структурная проверка сюда намеренно НЕ вызывается (см. комментарий
                // класса, фаза 4) — на сломанном промежуточном коде она не может дать
                // содержательный результат, а success уже false и так блокирует
                // ложный успех.
                debugLog.add("Итерация $iterationNum:\nкомпиляция: ОШИБКА (${result.stderr.take(150)})\nструктурная проверка: пропущена (CompileFailure)")
                StepOutcome(
                    success = false,
                    usefulProgress = true,
                    signature = result.stderr.ifBlank { result.stdout },
                    detail = result.stderr
                )
            }
            is CompileResult.Unavailable -> {
                debugLog.add("Итерация $iterationNum:\nкомпиляция: НЕДОСТУПНА (${result.reason})\nструктурная проверка: пропущена")
                StepOutcome(
                    success = false,
                    usefulProgress = false,
                    signature = "UNAVAILABLE:${result.reason}",
                    detail = result.reason
                )
            }
            is CompileResult.Denied -> {
                debugLog.add("Итерация $iterationNum:\nкомпиляция: ОТКЛОНЕНА (${result.reason})\nструктурная проверка: пропущена")
                StepOutcome(
                    success = false,
                    usefulProgress = false,
                    signature = "DENIED:${result.reason}",
                    detail = result.reason
                )
            }
        }
    }

    private fun logIteration(iterationNum: Int, compileSummary: String, outcome: StructuralCheckOutcome) {
        val structuralSummary = when (outcome) {
            is StructuralCheckOutcome.NoPreviousCode ->
                "структурная проверка: нет предыдущей версии кода (первый шаг)"
            is StructuralCheckOutcome.FunctionsNotParsed ->
                "структурная проверка: функции не распознаны парсером (возможно, был сломанный синтаксис на предыдущем шаге)"
            is StructuralCheckOutcome.NoSignificantChange ->
                "структурная проверка: выполнена, значимых изменений не найдено (CLEAN)"
            is StructuralCheckOutcome.Flagged -> {
                val r = outcome.result
                "структурная проверка: функция '${r.functionName}', " +
                    "сокращение ${(r.shrinkRatio * 100).toInt()}%, вердикт ${r.verdict}"
            }
        }
        debugLog.add("Итерация $iterationNum:\n$compileSummary\n$structuralSummary")
    }

    /**
     * Stage C → (если нужно) Stage B. Вызывается ТОЛЬКО из ветки CompileResult.Success.
     * Возвращает явный StructuralCheckOutcome, различающий "нечего сравнивать",
     * "не удалось распарсить функции" и "сравнение прошло, но чисто" — раньше все три
     * случая тонули в одном и том же "null", что маскировало реальную работу проверки.
     */
    private suspend fun resolveStructuralVerdict(state: KotlinCodeState): StructuralCheckOutcome {
        val previous = state.previousCode ?: return StructuralCheckOutcome.NoPreviousCode
        val results = StructuralBoundary.evaluateShrink(previous, state.code)
        if (results.isEmpty()) return StructuralCheckOutcome.FunctionsNotParsed

        val worst = results
            .filter { it.verdict != StructuralBoundary.ShrinkVerdict.CLEAN }
            .maxByOrNull { it.shrinkRatio }
            ?: return StructuralCheckOutcome.NoSignificantChange

        val resolved = if (worst.verdict == StructuralBoundary.ShrinkVerdict.NEEDS_CONFIRMATION) {
            bytecodeEscalator.refine(worst)
        } else {
            worst
        }
        return StructuralCheckOutcome.Flagged(resolved)
    }

    private val operate = StepOperate<KotlinCodeState> { state, outcome ->
        val prompt = buildPrompt(state, outcome)
        val generated = StringBuilder()
        var generationError: String? = null
        llmEngine.generateFlow(prompt, maxTokens = 100).collect { event ->
            when (event) {
                is GenerationEvent.Token -> generated.append(event.text)
                is GenerationEvent.Error -> generationError = event.message
                else -> Unit
            }
        }
        if (generationError != null) {
            // Генерация упала — возвращаем состояние как есть, но с пометкой ошибки,
            // чтобы следующий Test честно провалился на том же коде, а не на пустом.
            return@StepOperate state.copy(lastError = "Ошибка генерации: $generationError")
        }
        val cleanCode = extractKotlinCode(generated.toString())
        state.copy(code = cleanCode, lastError = outcome.detail, previousCode = state.code)
    }

    private fun buildPrompt(state: KotlinCodeState, outcome: StepOutcome): String {
        return buildString {
            append("Есть сломанный фрагмент Kotlin-кода:\n\n")
            append(state.code)
            append("\n\nОшибка компиляции:\n")
            append(outcome.detail)
            append(
                "\n\nНапиши ИСПРАВЛЕННЫЙ, полностью рабочий Kotlin-фрагмент. " +
                    "Выведи ТОЛЬКО код, без пояснений и без markdown-разметки."
            )
        }
    }

    /** Убирает markdown-обёртку (```kotlin ... ```), если модель её всё же добавила. */
    private fun extractKotlinCode(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutFirstFence = trimmed.substringAfter("\n", trimmed)
        return withoutFirstFence.substringBeforeLast("```").trim()
    }

    /**
     * "Вакцина-строка" — сохраняет извлечённый урок в Sticker-память. При эвакуации
     * дополнительно читает снимок последнего стабильного состояния (item 6b/8) и
     * добавляет его в текст записи — ТОЛЬКО для видимости, не как автоматический
     * откат (решение 2026-08-18: реального resume пока нет, это отдельный item 9).
     */
    private suspend fun saveVaccineLine(result: ToteResult<KotlinCodeState>) {
        val text = when (result) {
            is ToteResult.Success ->
                "[TOTE] Успех за ${result.iterations} итераций. Итоговый код:\n${result.finalState.code}"
            is ToteResult.Evacuated -> {
                val snapshot = mediator.getStableSnapshot()
                val snapshotNote = if (snapshot != null) {
                    "\n\nПоследний известный стабильный код (не восстановлен автоматически):\n${snapshot.code}"
                } else {
                    "\n\n(снимка стабильного состояния ещё не было)"
                }
                "[TOTE] Эвакуация после ${result.iterations} итераций (${result.reason}). " +
                    "Последняя ошибка:\n${result.lastOutcome?.detail ?: "(нет данных)"}$snapshotNote"
            }
            is ToteResult.HardStopped ->
                "[TOTE] Жёсткий стоп после ${result.iterations} итераций (лимит). " +
                    "Последняя ошибка:\n${result.lastOutcome?.detail ?: "(нет данных)"}"
        }
        mediator.saveEvent(text)
    }

    suspend fun run(): ToteResult<KotlinCodeState> {
        debugLog.clear()
        // Захардкоженная задача для теста цикла с реальной структурной проверкой
        // (не реальный ввод пользователя) — функция с полноценным телом и логической
        // опечаткой (tota вместо total), чтобы компиляция упала, но было что сравнивать
        // структурно уже со второго шага.
        val initialState = KotlinCodeState(
            code = """
                fun sumPositive(numbers: List<Int>): Int {
                    var total = 0
                    for (n in numbers) {
                        if (n > 0) {
                            total += n
                        }
                    }
                    return tota
                }
            """.trimIndent(),
            lastError = null
        )
        val engine = ToteEngine(
            test = test,
            operate = operate,
            watchdog = watchdog,
            pendingQuerySource = pendingQuerySource,
            queryHandler = queryHandler,
            promptBudgetGate = promptBudgetGate
        )
        val result = engine.run(initialState, taskDescription)
        saveVaccineLine(result)
        return result
    }
}
