package com.uroboros.memory.dream

import com.uroboros.llm.GenerationEnd
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.judge.EngineJudgeLlm
import com.uroboros.memory.judge.JudgeGenerationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Шаг ночи по кнопке «выводы»: выбрать сны, дающие давление любопытству,
 * спросить модель, что связывает записи каждого, проверить и сохранить строку
 * в таблицу выводов. Правило целиком — в [Conclusion]; здесь база, модель и
 * порядок.
 *
 * Зовёт только ночь по кнопке (AgentService.startNight), после зеркала.
 * Самостоятельный сон и самостоятельный судья выводов не делают.
 *
 * Не пишет в записи памяти, не зовёт посредника, не трогает касания,
 * вспоминание, подхват и основание строки о себе. Модели выводы не подаются.
 *
 * ВЫДАЧА ПОВТОРЯЕМАЯ ([LlmEngine.withDeterministicSampling]), как у строки о
 * себе. Поэтому сон, по которому строка уже есть — принятая или отброшенная,
 * — не пробуется снова: запрос тот же, ответ был бы тем же, а это ~30 с модели
 * впустую каждую ночь.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - сон, чей вывод отброшен, вывода не получит никогда — и сон, на котором
 *    модель не ответила или ответ оборвал сторож, тоже: такая попытка
 *    записывается строкой с причиной и считается пробой;
 *  - записи сна читаются в момент шага; если позже звено скроют, вывод
 *    останется в таблице, но на экране замолчит (см. [ConclusionView]).
 *
 * Отмена (уступил разговору, остановили) обрывает шаг; строки снов, уже
 * прошедших проверку, остаются, недоделанная — нет.
 */
object ConclusionStep {

    /**
     * Пройти шаг и вернуть одну строку итога для отчёта ночи и для
     * [DreamNight.conclusionsOutcome]. Исключений не выпускает, кроме отмены.
     *
     * @param whyNot условия модели прямо перед каждым вызовом; null — можно звать.
     */
    suspend fun run(
        db: MemoryDatabase,
        engine: LlmEngine,
        nightAt: Long,
        whyNot: () -> String?,
    ): String = try {
        attempt(db, engine, nightAt, whyNot)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        "${Conclusion.OUTCOME_HEAD}не вышло — ${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}"
    }

    private suspend fun attempt(
        db: MemoryDatabase,
        engine: LlmEngine,
        nightAt: Long,
        whyNot: () -> String?,
    ): String {
        val conclusions = db.conclusionDao()
        // Давление читается тем же путём, что прибор: сны с принятым выводом
        // в ряд уже не попадают, записи — по номеру и без отметки обращения.
        val pressure = CuriosityGauge(db).read()
        val candidates = when (val picked = Conclusion.pick(pressure.ranked, conclusions.triedKeys().toHashSet())) {
            is Conclusion.Pick.Silent -> return Conclusion.silentOutcome(picked.reason)
            is Conclusion.Pick.Dreams -> picked.dreams
        }

        var made = 0
        var calls = 0
        val dropped = mutableListOf<String>()
        var stoppedBy: String? = null
        for (candidate in candidates) {
            val why = whyNot()
            if (why != null) {
                stoppedBy = why
                break
            }
            calls++
            val texts = candidate.records.map { it.content }
            val (text, reason) = conclude(engine, texts)

            // Отмена, пришедшая за время ответа, не должна успеть оставить строку.
            currentCoroutineContext().ensureActive()
            conclusions.insert(
                ConclusionRow(
                    nightAt = nightAt,
                    dreamNightAt = candidate.dream.nightAt,
                    dreamRecordIds = candidate.dream.recordIds,
                    text = text,
                    accepted = reason == null,
                    reason = reason,
                )
            )
            if (reason == null) made++ else dropped += reason
        }
        if (calls == 0 && stoppedBy != null) return Conclusion.silentOutcome(stoppedBy)
        return Conclusion.doneOutcome(made, dropped, stoppedBy)
    }

    /**
     * Спросить модель о записях одного сна, разобрать и проверить. Вернуть
     * текст для строки и причину отказа (null — прошёл).
     */
    private suspend fun conclude(engine: LlmEngine, texts: List<String>): Pair<String, String?> {
        val answer = try {
            engine.withDeterministicSampling {
                EngineJudgeLlm(engine, Conclusion.ANSWER_TOKENS).answer(Conclusion.SYSTEM, Conclusion.request(texts))
            }
        } catch (broken: JudgeGenerationException) {
            return "" to "модель не ответила: ${broken.message ?: "без пояснения"}"
        }
        // Ответ, обрезанный сторожем, — не ответ (ARCHITECTURE.md §3.1).
        when (engine.lastGenerationEnd) {
            GenerationEnd.WATCHDOG_CRITICAL -> return "" to "оборван сторожем: опасная зона"
            GenerationEnd.WATCHDOG_TIMEOUT -> return "" to "оборван сторожем: потолок работы"
            else -> Unit
        }
        val text = when (val parsed = Conclusion.parse(answer)) {
            is Conclusion.Parsed.Refused -> return parsed.raw to parsed.reason
            is Conclusion.Parsed.Text -> parsed.text
        }
        return text to Conclusion.check(text, texts)
    }
}
