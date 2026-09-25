package com.uroboros.memory.dream

import com.uroboros.llm.ConversationJournal
import com.uroboros.llm.GenerationEnd
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.judge.EngineJudgeLlm
import com.uroboros.memory.judge.JudgeGenerationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Шаг ночи по кнопке «зеркало»: собрать запрос из конца ленты, спросить модель,
 * разобрать ответ в варианты и сохранить их в таблицу зеркала. Правило целиком
 * — в [Mirror]; здесь база, модель и порядок.
 *
 * Зовёт только ночь по кнопке (AgentService.startNight), после строки о себе.
 * Самостоятельный сон и самостоятельный судья зеркала не зовут.
 *
 * ВЫДАЧА СЛУЧАЙНАЯ — решение владельца. Вызов НЕ обёрнут в
 * [LlmEngine.withDeterministicSampling]: зеркало сочиняет, и три варианта на
 * повторяемой выдаче были бы одним и тем же ответом каждую ночь. Идёт на
 * разговорных настройках, которые движку вернул шаг перед ним.
 *
 * Отмена (уступил разговору, остановили) обрывает шаг, и ничего не
 * сохраняется. Ответ, оборванный сторожем, — не ответ: вариантов нет.
 */
object MirrorStep {

    /**
     * Потолок выдачи. Объявленное число, не подобранное: три коротких варианта
     * с запасом.
     */
    const val ANSWER_TOKENS = 150

    /**
     * Пройти шаг и вернуть одну строку итога для отчёта ночи и для
     * [DreamNight.mirrorOutcome]. Исключений не выпускает, кроме отмены.
     *
     * @param history лента разговора, как её прочитал зовущий.
     * @param whyNot условия модели прямо перед вызовом; null — можно звать.
     */
    suspend fun run(
        db: MemoryDatabase,
        engine: LlmEngine,
        nightAt: Long,
        history: List<ConversationJournal.Turn>,
        whyNot: () -> String?,
    ): String = try {
        attempt(db, engine, nightAt, history, whyNot)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        "${Mirror.OUTCOME_HEAD}не вышло — ${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}"
    }

    private suspend fun attempt(
        db: MemoryDatabase,
        engine: LlmEngine,
        nightAt: Long,
        history: List<ConversationJournal.Turn>,
        whyNot: () -> String?,
    ): String {
        val request = when (val built = Mirror.request(history)) {
            is Mirror.Request.Silent -> return Mirror.silentOutcome(built.reason)
            is Mirror.Request.Ready -> built
        }
        whyNot()?.let { return Mirror.silentOutcome(it) }

        fun failed(reason: String) = Mirror.lookedOutcome(request.fromTurn, request.toTurn, 0, reason)

        val answer = try {
            EngineJudgeLlm(engine, ANSWER_TOKENS).answer(Mirror.SYSTEM, request.text)
        } catch (broken: JudgeGenerationException) {
            return failed("модель не ответила: ${broken.message ?: "без пояснения"}")
        }
        // Ответ, обрезанный сторожем, — не ответ (ARCHITECTURE.md §3.1).
        when (engine.lastGenerationEnd) {
            GenerationEnd.WATCHDOG_CRITICAL -> return failed("ответ оборван сторожем: опасная зона")
            GenerationEnd.WATCHDOG_TIMEOUT -> return failed("ответ оборван сторожем: потолок работы")
            else -> Unit
        }

        val variants = Mirror.parse(answer)
        if (variants.isEmpty()) return Mirror.lookedOutcome(request.fromTurn, request.toTurn, 0)

        // Отмена, пришедшая за время ответа, не должна успеть оставить варианты.
        currentCoroutineContext().ensureActive()
        val excluded = Mirror.joinStems(request.excludedStems)
        db.mirrorDao().insertAll(
            variants.map {
                MirrorVariant(
                    nightAt = nightAt,
                    kind = Mirror.Kind.NEXT.name,
                    text = it,
                    fromTurn = request.fromTurn,
                    toTurn = request.toTurn,
                    excludedStems = excluded,
                )
            }
        )
        return Mirror.lookedOutcome(request.fromTurn, request.toTurn, variants.size)
    }
}
