package com.uroboros.memory.dream

import com.uroboros.llm.GenerationEnd
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.judge.EngineJudgeLlm
import com.uroboros.memory.judge.JudgeGenerationException
import com.uroboros.memory.nav.FactAging
import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.memory.nav.PersonFacts
import com.uroboros.memory.nav.Portrait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Шаг ночи «устаревшее»: модель отвечает, отменяет ли новый факт собеседника
 * его старый факт на ту же тему ([FactAging]). Здесь база, модель и порядок;
 * правило — в [FactAging].
 *
 * Зовут ночь по кнопке и заход шагов ночи без кнопки (AgentService) — сразу
 * после разметки фактов ([FactMarkStep]): пары собираются только из строк,
 * отмеченных «факт».
 *
 * ВЫДАЧА ПОВТОРЯЕМАЯ, СБОЙ — НЕ ПРОБА — как у [FactMarkStep].
 *
 * Не пишет в записи памяти: «отменяет» убирает старую строку только из портрета.
 */
object FactAgingStep {

    /**
     * Сравнить пары; вернуть строку итога. [ownerKey] — ключ владельца в
     * телеграме; [dictionary] — словарь начальных форм (без него пар нет).
     * Исключений не выпускает, кроме отмены.
     */
    suspend fun run(
        db: MemoryDatabase,
        engine: LlmEngine,
        ownerKey: String?,
        dictionary: LemmaDictionary?,
        whyNot: () -> String?,
    ): String = try {
        if (dictionary == null) FactAging.outcome(0, emptyList(), 0, null, noDictionary = true)
        else attempt(db, engine, ownerKey, dictionary, whyNot)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        "${FactAging.OUTCOME_HEAD}не вышло — ${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}"
    }

    private suspend fun attempt(
        db: MemoryDatabase,
        engine: LlmEngine,
        ownerKey: String?,
        dictionary: LemmaDictionary,
        whyNot: () -> String?,
    ): String {
        val records = db.stickerDao().getAll()
        val read = FactsReader.read(db, records)
        val asked = db.factPairDao().all().mapTo(HashSet()) { pairOf(it) }
        val facts = PersonFacts.persons(records, ownerKey).map { person ->
            Portrait.of(records, person, ownerKey = ownerKey, marks = read.marks).passed.filter { it.fact == true }
        }
        val pending = FactAging.toAsk(facts, dictionary, asked, read.outdated)
        var count = 0
        val outdatedNow = ArrayList<FactAging.Pair>()
        var stoppedBy: String? = null
        for (pair in pending.next) {
            // Старая строка уже отменена этой ночью — с ней пар больше не спрашивается.
            if (outdatedNow.any { it.old == pair.old }) continue
            val why = whyNot()
            if (why != null) {
                stoppedBy = why
                break
            }
            val answer = try {
                engine.withDeterministicSampling {
                    EngineJudgeLlm(engine, FactAging.ANSWER_TOKENS)
                        .answer(FactAging.SYSTEM, FactAging.request(pair.old.sentence, pair.new.sentence))
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
            // Отмена, пришедшая за время ответа, не должна успеть оставить пару.
            currentCoroutineContext().ensureActive()
            val verdict = FactAging.parse(answer)
            db.factPairDao().put(
                FactPairRow(
                    pair.old.recordId, pair.old.sentence, pair.new.recordId, pair.new.sentence,
                    verdict.name, System.currentTimeMillis(),
                )
            )
            count++
            if (verdict == FactAging.Verdict.OUTDATES) outdatedNow += pair
        }
        return FactAging.outcome(count, outdatedNow, pending.total - count, stoppedBy)
    }

    private fun pairOf(row: FactPairRow) = FactAging.Pair(
        PersonFacts.Key(row.oldRecordId, row.oldSentence),
        PersonFacts.Key(row.newRecordId, row.newSentence),
    )
}
