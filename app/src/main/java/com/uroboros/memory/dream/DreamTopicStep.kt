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
 * Шаг ночи «темы снов»: спрашивать модель тему снов ночи по порядку
 * [DreamTopic.newestFirst], пока не принято [DreamTopic.MAX_DREAMS] тем или не
 * спрошено [DreamTopic.MAX_ATTEMPTS] снов, и записать принятые в строку ночи
 * ([DreamNight.dreamTopics]). Правило целиком — в [DreamTopic]; здесь база,
 * модель и порядок.
 *
 * Зовут ночь по кнопке (AgentService.startNight) и заход шагов ночи без кнопки
 * ([NightSteps]) — оба после выводов.
 *
 * ВЫДАЧА ПОВТОРЯЕМАЯ ([LlmEngine.withDeterministicSampling]), как у выводов и
 * строки о себе: одна и та же ночь даёт одни и те же темы.
 *
 * Сон, где есть запись на проверке, отвергнутая или пропавшая, темы не
 * получает: тема ушла бы в разговор, а скрытое туда не идёт.
 *
 * СБОЙ — НЕ ПРОБА (как у [ConclusionStep]): модель не ответила или ответ оборвал
 * сторож — шаг кончается, принятое до сбоя сохраняется.
 *
 * Не пишет в записи памяти, не трогает касания, вспоминание и подхват.
 */
object DreamTopicStep {

    /**
     * Пройти шаг для ночи [nightAt]: записать темы в строку ночи и вернуть
     * строку итога для отчёта. Исключений не выпускает, кроме отмены.
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
        "${DreamTopic.OUTCOME_HEAD}не вышло — ${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}"
    }

    private suspend fun attempt(
        db: MemoryDatabase,
        engine: LlmEngine,
        nightAt: Long,
        whyNot: () -> String?,
    ): String {
        val stickers = db.stickerDao()
        val dreams = DreamTopic.newestFirst(db.dreamDao().ofNight(nightAt), { it.ids() }, { it.recordIds })
        if (dreams.isEmpty()) {
            db.dreamDao().setDreamTopics(nightAt, "")
            return DreamTopic.silentOutcome("снов этой ночи нет")
        }
        val usable = dreams.mapNotNull { dream ->
            val records = dream.ids().map { stickers.getById(it) }
            if (records.any { it == null || it.reviewPending || it.rejectedAt != null }) null
            else records.map { it!!.content }
        }.take(DreamTopic.MAX_ATTEMPTS)
        if (usable.isEmpty()) {
            db.dreamDao().setDreamTopics(nightAt, "")
            return DreamTopic.silentOutcome("во всех снах есть скрытые записи")
        }

        val accepted = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        var stoppedBy: String? = null
        for (texts in usable) {
            if (accepted.size >= DreamTopic.MAX_DREAMS) break
            val why = whyNot()
            if (why != null) {
                stoppedBy = why
                break
            }
            val answer = try {
                engine.withDeterministicSampling {
                    EngineJudgeLlm(engine, DreamTopic.ANSWER_TOKENS).answer(DreamTopic.SYSTEM, DreamTopic.request(texts))
                }
            } catch (broken: JudgeGenerationException) {
                stoppedBy = "модель не ответила: ${broken.message ?: "без пояснения"}"
                break
            }
            // Ответ, обрезанный сторожем, — не ответ (ARCHITECTURE.md §3.1).
            when (engine.lastGenerationEnd) {
                GenerationEnd.WATCHDOG_CRITICAL -> { stoppedBy = "ответ оборван сторожем: опасная зона"; break }
                GenerationEnd.WATCHDOG_TIMEOUT -> { stoppedBy = "ответ оборван сторожем: потолок работы"; break }
                else -> Unit
            }
            when (val parsed = DreamTopic.parse(answer)) {
                is DreamTopic.Parsed.Refused -> dropped += parsed.reason
                is DreamTopic.Parsed.Topic -> {
                    val reason = DreamTopic.check(parsed.text, texts)
                    if (reason == null && parsed.text !in accepted) accepted += parsed.text
                    else if (reason != null) dropped += reason
                }
            }
        }
        // Отмена, пришедшая за время ответа, не должна успеть оставить темы.
        currentCoroutineContext().ensureActive()
        if (accepted.isEmpty() && dropped.isEmpty() && stoppedBy != null) {
            return DreamTopic.silentOutcome(stoppedBy)
        }
        db.dreamDao().setDreamTopics(nightAt, DreamTopic.store(accepted))
        return DreamTopic.doneOutcome(accepted, dropped, stoppedBy)
    }
}
