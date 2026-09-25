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
 * Шаг ночи «выводы»: выбрать сны, дающие давление любопытству,
 * спросить модель, что связывает записи каждого, проверить и сохранить строку
 * в таблицу выводов. Правило целиком — в [Conclusion]; здесь база, модель и
 * порядок.
 *
 * Зовут ночь по кнопке (AgentService.startNight) и заход шагов ночи без
 * кнопки ([NightSteps]) — оба после зеркала. Самостоятельный судья выводов
 * не делает.
 *
 * Не пишет в записи памяти, не зовёт посредника, не трогает касания,
 * вспоминание, подхват и основание строки о себе. Модели выводы не подаются.
 *
 * ВЫДАЧА ПОВТОРЯЕМАЯ ([LlmEngine.withDeterministicSampling]), как у строки о
 * себе. Поэтому сон, по которому строка уже есть — принятая или отброшенная,
 * — не пробуется снова: запрос тот же, ответ был бы тем же, а это ~30 с модели
 * впустую каждую ночь.
 *
 * СБОЙ — НЕ ПРОБА. Если модель не ответила или ответ оборвал сторож, строка
 * в таблицу НЕ пишется, и шаг на этом кончается: довод «повтор даст то же»
 * держится только на ответе, который модель дала и который не прошёл
 * проверку. Сбой (нагрев, ошибка движка) в другую ночь может не случиться,
 * а строка с ним закрыла бы сон навсегда. Остальные сны этой ночи не
 * пробуются: после сбоя движка или сторожа звать модель снова — не то, что
 * стоит делать без человека. Причина сбоя идёт в итог ночи.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - сон, чей вывод отброшен, вывода не получит никогда;
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
        val tried = conclusions.triedKeys().toHashSet()
        val candidates = when (val picked = Conclusion.pick(pressure.ranked, tried, pressure.pressure)) {
            is Conclusion.Pick.Silent -> return Conclusion.silentOutcome(picked.reason)
            is Conclusion.Pick.Dreams -> picked.dreams
        }

        var made = 0
        // Сколько раз модель ответила: без ответов итог — «не делаю» с причиной.
        var answered = 0
        val dropped = mutableListOf<String>()
        var stoppedBy: String? = null
        for (candidate in candidates) {
            val why = whyNot()
            if (why != null) {
                stoppedBy = why
                break
            }
            val texts = candidate.records.map { it.content }
            val (text, reason) = when (val result = conclude(engine, texts)) {
                // Сбой — не проба: строки нет, шаг кончается (см. «СБОЙ — НЕ ПРОБА»).
                is Attempt.Failed -> {
                    stoppedBy = result.reason
                    break
                }
                is Attempt.Answered -> result.text to result.reason
            }
            answered++

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
        if (answered == 0 && stoppedBy != null) return Conclusion.silentOutcome(stoppedBy)
        return Conclusion.doneOutcome(made, dropped, stoppedBy)
    }

    /** Чем кончилась попытка по одному сну. */
    private sealed class Attempt {
        /** Модель ответила; [reason] — причина отказа, null — вывод прошёл. */
        data class Answered(val text: String, val reason: String?) : Attempt()

        /** Ответа нет — сбой, а не проба (см. «СБОЙ — НЕ ПРОБА»). */
        data class Failed(val reason: String) : Attempt()
    }

    /** Спросить модель о записях одного сна, разобрать и проверить. */
    private suspend fun conclude(engine: LlmEngine, texts: List<String>): Attempt {
        val answer = try {
            engine.withDeterministicSampling {
                EngineJudgeLlm(engine, Conclusion.ANSWER_TOKENS).answer(Conclusion.SYSTEM, Conclusion.request(texts))
            }
        } catch (broken: JudgeGenerationException) {
            return Attempt.Failed("модель не ответила: ${broken.message ?: "без пояснения"}")
        }
        // Ответ, обрезанный сторожем, — не ответ (ARCHITECTURE.md §3.1).
        when (engine.lastGenerationEnd) {
            GenerationEnd.WATCHDOG_CRITICAL -> return Attempt.Failed("ответ оборван сторожем: опасная зона")
            GenerationEnd.WATCHDOG_TIMEOUT -> return Attempt.Failed("ответ оборван сторожем: потолок работы")
            else -> Unit
        }
        val text = when (val parsed = Conclusion.parse(answer)) {
            is Conclusion.Parsed.Refused -> return Attempt.Answered(parsed.raw, parsed.reason)
            is Conclusion.Parsed.Text -> parsed.text
        }
        return Attempt.Answered(text, Conclusion.check(text, texts))
    }
}
