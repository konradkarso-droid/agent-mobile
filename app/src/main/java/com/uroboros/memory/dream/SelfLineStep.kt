package com.uroboros.memory.dream

import com.uroboros.llm.GenerationEnd
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.SaveResult
import com.uroboros.memory.TrustedMediator
import com.uroboros.memory.judge.EngineJudgeLlm
import com.uroboros.memory.judge.JudgeGenerationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Шаг ночи «строка о себе»: прочитать, что известно, решить
 * ([SelfLine.decide]), спросить модель о теме, проверить и, если это
 * предложение, сохранить строку на проверку. Правило целиком — в [SelfLine];
 * здесь база, модель и порядок.
 *
 * Зовут ночь по кнопке (AgentService.startNight), после судьи, и заход шагов
 * ночи без кнопки — первым (когда и почему — [NightSteps]). Самостоятельный
 * судья строку не предлагает.
 *
 * Сохранение идёт только после проверки темы. Отмена (уступил разговору,
 * остановили) обрывает шаг раньше и ничего не оставляет.
 */
object SelfLineStep {

    /**
     * Системная инструкция вопроса о теме. Своя, не судьи: промпт и отпечаток
     * судьи не меняются.
     */
    const val SYSTEM =
        "Тебе дают запись из памяти. Назови её тему двумя–пятью словами, в предложном падеже, " +
            "как будто после слова «о». Бери слова из самой записи. Без слова «о», без точки, " +
            "без пояснений."

    /**
     * Потолок выдачи для темы. Объявленное число, не подобранное: несколько
     * слов с запасом на пробел и лишнее начало.
     */
    const val ANSWER_TOKENS = 24

    /**
     * Пройти шаг и вернуть одну строку итога для отчёта ночи и для
     * [DreamNight.selfLineOutcome]. Исключений не выпускает, кроме отмены.
     *
     * @param whyNot условия модели прямо перед вызовом (модель загружена, нет
     *   аварийного стопа, есть показания батареи, зона не критическая);
     *   null — можно звать.
     */
    suspend fun run(
        db: MemoryDatabase,
        mediator: TrustedMediator,
        engine: LlmEngine,
        whyNot: () -> String?,
    ): String = try {
        val stickers = db.stickerDao()
        val standing = UnpromptedLeader.standing(stickers.unpromptedTouches())
        val nights = db.dreamDao().lastUnpromptedLeaders(UnpromptedLeader.NIGHTS_WINDOW)
        val settled = standing.leaderId?.let { id ->
            val rows = stickers.settledIdentityBasedOn(id)
            when {
                rows.any { it.rejectedAt == null } -> SelfLine.Settled.ACCEPTED
                rows.isNotEmpty() -> SelfLine.Settled.REJECTED
                else -> null
            }
        }
        val decision = SelfLine.decide(
            SelfLine.Inputs(
                standing = standing,
                lastNights = nights,
                pendingId = stickers.pendingIdentity()?.id,
                acceptedCount = stickers.countAcceptedIdentity(),
                leaderSettled = settled,
            )
        )
        when (decision) {
            is SelfLine.Decision.Silent -> SelfLine.silentOutcome(decision.reason)
            is SelfLine.Decision.Probe -> attempt(decision.baseId, propose = false, db, mediator, engine, whyNot)
            is SelfLine.Decision.Propose -> attempt(decision.baseId, propose = true, db, mediator, engine, whyNot)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        "${SelfLine.OUTCOME_HEAD}не вышло — ${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}"
    }

    /**
     * Спросить модель о теме [baseId], проверить и — при [propose] — сохранить.
     *
     * ВЫДАЧА ПОВТОРЯЕМАЯ ([LlmEngine.withDeterministicSampling]), попытка одна
     * за ночь, без повторов внутри ночи. Одна и та же запись каждую ночь даёт
     * одну и ту же тему, поэтому подобрать ответ под проверку повторами нельзя.
     * Цена: запись, чей пересказ не прошёл проверку, строку не получит, пока
     * не сменится сама запись.
     */
    private suspend fun attempt(
        baseId: Long,
        propose: Boolean,
        db: MemoryDatabase,
        mediator: TrustedMediator,
        engine: LlmEngine,
        whyNot: () -> String?,
    ): String {
        fun failed(reason: String) =
            if (propose) SelfLine.droppedOutcome(reason) else SelfLine.probeOutcome(baseId, null, reason)

        whyNot()?.let { return SelfLine.notOfferedOutcome(it) }
        val base = db.stickerDao().getById(baseId)
            ?: return SelfLine.silentOutcome("№$baseId не найдена")

        val answer = try {
            engine.withDeterministicSampling {
                EngineJudgeLlm(engine, ANSWER_TOKENS).answer(SYSTEM, base.content)
            }
        } catch (broken: JudgeGenerationException) {
            return failed("модель не ответила: ${broken.message ?: "без пояснения"}")
        }
        // Ответ, обрезанный сторожем, — не ответ (ARCHITECTURE.md §3.1).
        when (engine.lastGenerationEnd) {
            GenerationEnd.WATCHDOG_CRITICAL -> return failed("ответ оборван сторожем: опасная зона")
            GenerationEnd.WATCHDOG_TIMEOUT -> return failed("ответ оборван сторожем: потолок работы")
            else -> Unit
        }

        val topic = when (val parsed = SelfLine.parseTopic(answer)) {
            is SelfLine.Parsed.Refused -> return failed(parsed.reason)
            is SelfLine.Parsed.Topic -> parsed.text
        }
        SelfLine.checkTopic(topic, baseId, base.content)?.let { return failed(it) }
        if (!propose) return SelfLine.probeOutcome(baseId, topic, null)

        // Отмена, пришедшая за время ответа, не должна успеть оставить строку.
        currentCoroutineContext().ensureActive()
        return when (val saved = mediator.proposeSelfLine(SelfLine.compose(topic), baseId)) {
            is SaveResult.Saved -> SelfLine.proposedOutcome(saved.id, baseId, topic, null)
            is SaveResult.SavedNearDuplicate ->
                SelfLine.proposedOutcome(saved.id, baseId, topic, saved.similarToContent)
            is SaveResult.Duplicate ->
                SelfLine.droppedOutcome("такая строка уже лежит — №${saved.existingId}")
        }
    }
}
