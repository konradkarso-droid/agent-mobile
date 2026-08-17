package com.uroboros.will.tasks

import com.dark.gguf_lib.models.GenerationEvent
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.TrustedMediator
import com.uroboros.safety.DeviceSafetyWatchdog
import com.uroboros.util.StructuralBoundary
import com.uroboros.will.BytecodeShrinkEscalator
import com.uroboros.will.CompileResult
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
 * Фаза 4 (2026-08-17): структурная проверка (item 8a, ось 2, stage C→B) теперь
 * вызывается ТОЛЬКО на ветке CompileResult.Success, не на CompileFailure. Причина
 * (найдено живым тестом на устройстве, лог показал "сравнивать не с чем" на всех
 * промежуточных сломанных итерациях): findFunctionSpans() требует парную
 * открывающую/закрывающую скобку — на синтаксически неполном коде (типичное
 * промежуточное состояние между попытками починки) функция физически не
 * распознаётся, и это неотличимо от "функцию удалили" на уровне возвращаемого
 * результата. Реальная защита от reward-hacking (ради которой ось и строилась)
 * нужна только там, где компиляция УЖЕ прошла — то есть где формальный "успех"
 * мог быть получен ценой урезанной логики; там же обе стороны сравнения по
 * определению синтаксически валидны (раз скомпилировались), так что и сама
 * эвристика C наконец-то может содержательно сработать. На CompileFailure
 * success уже false и так не даёт ложного успеха — доп. проверка там не нужна.
 *
 * Диагностика (2026-08-17): каждая итерация test() дописывает строку в debugLog —
 * что вернула компиляция, вердикт C, результат эскалации B, если была. Доступ через
 * getDebugLog() — вызывающий код (MainActivity) вешает это на долгое нажатие.
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

class KotlinCodingTask(
    private val termuxCompiler: TermuxKotlinCompiler,
    private val llmEngine: LlmEngine,
    private val mediator: TrustedMediator,
    private val watchdog: DeviceSafetyWatchdog,
    private val bytecodeEscalator: BytecodeShrinkEscalator = BytecodeShrinkEscalator(termuxCompiler)
) {

    private val debugLog = mutableListOf<String>()

    /** Детальный лог по каждой итерации TOTE-цикла — компиляция + вердикты C/B. */
    fun getDebugLog(): String =
        if (debugLog.isEmpty()) "Лог пуст (цикл ещё не запускался)" else debugLog.joinToString("\n\n---\n\n")

    private val test = StepTest<KotlinCodeState> { state ->
        val iterationNum = debugLog.size + 1
        when (val result = termuxCompiler.compile(state.code)) {
            is CompileResult.Success -> {
                val structural = resolveStructuralVerdict(state)
                logIteration(iterationNum, "компиляция: УСПЕХ", structural)
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
                    else -> StepOutcome(
                        success = true,
                        usefulProgress = true,
                        signature = "OK",
                        detail = result.stdout
                    )
                }
            }
            is CompileResult.CompileFailure -> {
                // Структурная проверка сюда намеренно НЕ вызывается (см. комментарий
                // класса, фаза 4) — на сломанном промежуточном коде она не может дать
                // содержательный результат, а success уже false и так блокирует
                // ложный успех.
                logIteration(iterationNum, "компиляция: ОШИБКА (${result.stderr.take(150)})", null)
                StepOutcome(
                    success = false,
                    usefulProgress = true,
                    signature = result.stderr.ifBlank { result.stdout },
                    detail = result.stderr
                )
            }
            is CompileResult.Unavailable -> {
                logIteration(iterationNum, "компиляция: НЕДОСТУПНА (${result.reason})", null)
                StepOutcome(
                    success = false,
                    usefulProgress = false,
                    signature = "UNAVAILABLE:${result.reason}",
                    detail = result.reason
                )
            }
            is CompileResult.Denied -> {
                logIteration(iterationNum, "компиляция: ОТКЛОНЕНА (${result.reason})", null)
                StepOutcome(
                    success = false,
                    usefulProgress = false,
                    signature = "DENIED:${result.reason}",
                    detail = result.reason
                )
            }
        }
    }

    private fun logIteration(iterationNum: Int, compileSummary: String, structural: StructuralBoundary.ShrinkResult?) {
        val structuralSummary = if (structural == null) {
            "структурная проверка: не выполнялась или сравнивать не с чем"
        } else {
            "структурная проверка: функция '${structural.functionName}', " +
                "сокращение ${(structural.shrinkRatio * 100).toInt()}%, вердикт ${structural.verdict}"
        }
        debugLog.add("Итерация $iterationNum:\n$compileSummary\n$structuralSummary")
    }

    /**
     * Stage C → (если нужно) Stage B. Вызывается ТОЛЬКО из ветки CompileResult.Success
     * (см. комментарий класса, фаза 4). Находит "худший" вердикт среди функций,
     * изменившихся между previousCode и code через StructuralBoundary (stage C); если
     * результат — NEEDS_CONFIRMATION, уточняет его через BytecodeShrinkEscalator
     * (stage B). Возвращает null, если сравнивать не с чем (первый шаг) или ничего
     * не изменилось.
     */
    private suspend fun resolveStructuralVerdict(state: KotlinCodeState): StructuralBoundary.ShrinkResult? {
        val previous = state.previousCode ?: return null
        val results = StructuralBoundary.evaluateShrink(previous, state.code)
        val worst = results
            .filter { it.verdict != StructuralBoundary.ShrinkVerdict.CLEAN }
            .maxByOrNull { it.shrinkRatio } ?: return null

        return if (worst.verdict == StructuralBoundary.ShrinkVerdict.NEEDS_CONFIRMATION) {
            bytecodeEscalator.refine(worst)
        } else {
            worst
        }
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

    /** "Вакцина-строка" — сохраняет извлечённый урок в Sticker-память. */
    private suspend fun saveVaccineLine(result: ToteResult<KotlinCodeState>) {
        val text = when (result) {
            is ToteResult.Success ->
                "[TOTE] Успех за ${result.iterations} итераций. Итоговый код:\n${result.finalState.code}"
            is ToteResult.Evacuated ->
                "[TOTE] Эвакуация после ${result.iterations} итераций (${result.reason}). " +
                    "Последняя ошибка:\n${result.lastOutcome?.detail ?: "(нет данных)"}"
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
        val engine = ToteEngine(test = test, operate = operate, watchdog = watchdog)
        val result = engine.run(initialState)
        saveVaccineLine(result)
        return result
    }
}
