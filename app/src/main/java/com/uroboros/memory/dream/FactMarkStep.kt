package com.uroboros.memory.dream

import com.uroboros.llm.GenerationEnd
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.judge.EngineJudgeLlm
import com.uroboros.memory.judge.JudgeGenerationException
import com.uroboros.memory.nav.PersonFacts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Шаг ночи «факты»: модель отмечает неразмеченные строки портретов —
 * «факт» или «момент» ([PersonFacts]). Здесь база, модель и порядок; правило —
 * в [PersonFacts].
 *
 * Зовут ночь по кнопке и заход шагов ночи без кнопки (AgentService) — после
 * тем снов. Шаг не привязан к ночи: размечает то, что ещё не размечено, на
 * любом заходе.
 *
 * ВЫДАЧА ПОВТОРЯЕМАЯ ([LlmEngine.withDeterministicSampling]), как у тем снов:
 * одна фраза — одна отметка.
 *
 * СБОЙ — НЕ ПРОБА (как у DreamTopicStep): модель не ответила или ответ
 * оборвал сторож — шаг кончается, поставленные до сбоя отметки остаются.
 *
 * Не пишет в записи памяти, не трогает касания и слои.
 */
object FactMarkStep {

    /**
     * Разметить; вернуть строку итога. [ownerKey] — ключ владельца в
     * телеграме (чьи записи из канала — его). Исключений не выпускает, кроме отмены.
     */
    suspend fun run(
        db: MemoryDatabase,
        engine: LlmEngine,
        ownerKey: String?,
        whyNot: () -> String?,
    ): String = try {
        attempt(db, engine, ownerKey, whyNot)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        "${PersonFacts.OUTCOME_HEAD}не вышло — ${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}"
    }

    private suspend fun attempt(db: MemoryDatabase, engine: LlmEngine, ownerKey: String?, whyNot: () -> String?): String {
        val dao = db.factMarkDao()
        val marked = dao.all().mapTo(HashSet()) { PersonFacts.Key(it.recordId, it.sentence) }
        val pending = PersonFacts.toMark(db.stickerDao().getAll(), ownerKey, marked)
        var facts = 0
        var moments = 0
        var unclear = 0
        var stoppedBy: String? = null
        for (key in pending.next) {
            val why = whyNot()
            if (why != null) {
                stoppedBy = why
                break
            }
            val answer = try {
                engine.withDeterministicSampling {
                    EngineJudgeLlm(engine, PersonFacts.ANSWER_TOKENS).answer(PersonFacts.SYSTEM, PersonFacts.request(key.sentence))
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
            // Отмена, пришедшая за время ответа, не должна успеть оставить отметку.
            currentCoroutineContext().ensureActive()
            val verdict = PersonFacts.parse(answer)
            dao.put(FactMarkRow(key.recordId, key.sentence, verdict.name, System.currentTimeMillis()))
            when (verdict) {
                PersonFacts.Verdict.FACT -> facts++
                PersonFacts.Verdict.MOMENT -> moments++
                PersonFacts.Verdict.UNCLEAR -> unclear++
            }
        }
        return PersonFacts.outcome(facts, moments, unclear, pending.total - facts - moments - unclear, stoppedBy)
    }
}
