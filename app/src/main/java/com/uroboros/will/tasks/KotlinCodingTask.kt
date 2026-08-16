package com.uroboros.will.tasks

import com.dark.gguf_lib.models.GenerationEvent
import com.uroboros.llm.LlmEngine
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
 * Фаза 1 (текущая): StepTest проверяет ТОЛЬКО компиляцию (Y/N).
 * Ось "полезный объём кода" (item 8a, ось 2) сюда пока не добавлена — usefulProgress
 * временно всегда true. Задача для первого прогона — захардкожена (не реальный ввод
 * пользователя).
 */

data class KotlinCodeState(
    val code: String,
    val lastError: String? = null
)

class KotlinCodingTask(
    private val termuxCompiler: TermuxKotlinCompiler,
    private val llmEngine: LlmEngine
) {

    private val test = StepTest<KotlinCodeState> { state ->
        when (val result = termuxCompiler.compile(state.code)) {
            is CompileResult.Success -> StepOutcome(
                success = true,
                usefulProgress = true,
                signature = "OK",
                detail = result.stdout
            )
            is CompileResult.CompileFailure -> StepOutcome(
                success = false,
                // TODO(item 8a, ось 2): заменить на реальную оценку "полезного объёма" —
                // пока считаем прогресс полезным по умолчанию, ось ещё не спроектирована.
                usefulProgress = true,
                signature = result.stderr.ifBlank { result.stdout },
                detail = result.stderr
            )
            is CompileResult.Unavailable -> StepOutcome(
                success = false,
                usefulProgress = false,
                signature = "UNAVAILABLE:${result.reason}",
                detail = result.reason
            )
            is CompileResult.Denied -> StepOutcome(
                success = false,
                usefulProgress = false,
                signature = "DENIED:${result.reason}",
                detail = result.reason
            )
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
        state.copy(code = cleanCode, lastError = outcome.detail)
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

    suspend fun run(): ToteResult<KotlinCodeState> {
        // Захардкоженная задача для первого сквозного теста цикла (не реальный ввод) —
        // намеренно неполный код, чтобы первый Test точно упал и запустил Operate (LLM).
        val initialState = KotlinCodeState(
            code = "fun add(a: Int, b: Int): Int {",
            lastError = null
        )
        val engine = ToteEngine(test = test, operate = operate)
        return engine.run(initialState)
    }
}
