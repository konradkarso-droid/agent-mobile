package com.uroboros.memory.dream

import com.uroboros.llm.LlmEngine
import com.uroboros.llm.RecordChunkStore
import com.uroboros.llm.RecordSplit
import com.uroboros.memory.RecordChunks
import kotlinx.coroutines.CancellationException

/**
 * Шаг ночи «куски записей»: обсчитать заранее строки записей, которые чаще
 * всего уходят модели, с подписями наступающего дня (что и почему — у
 * [RecordChunks] и `record_splice.h`).
 *
 * Идёт ПОСЛЕ возврата дневной загрузки (AgentService.backToDay): кусок верен
 * только для той загрузки, на которой будет разговор, а ночь грузит модель со
 * своей накладкой.
 *
 * Только при включённой склейке ([RecordChunkStore.on]): выключенная склейка
 * кусков не берёт, и греть телефон ради них незачем.
 *
 * Порциями по [RecordChunks.BATCH] строк; перед каждой порцией — сверка со
 * сторожем ([whyNot]), внутри порции движок останавливается при опасной зоне
 * (LlmEngine.buildRecordChunks). Итог ложится строкой «Куски ночи:» и
 * возвращается для отчёта ночи.
 *
 * Не пишет в записи памяти и ничего в них не меняет: кусок — кэш.
 */
object RecordChunkStep {

    suspend fun run(store: RecordChunkStore, engine: LlmEngine, now: Long, whyNot: () -> String?): String {
        val line = try {
            attempt(store, engine, now, whyNot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            "Куски ночи: не вышло — ${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}"
        }
        store.nightLine = line
        return line
    }

    private suspend fun attempt(store: RecordChunkStore, engine: LlmEngine, now: Long, whyNot: () -> String?): String {
        if (!store.on) return RecordSplit.nightLine(0, null, "склейка выключена")
        whyNot()?.let { return RecordSplit.nightLine(0, null, it) }
        val lines = RecordChunks.linesFor(store.candidates(), now)
        if (lines.isEmpty()) return RecordSplit.nightLine(0, null, "строк записей старше сегодняшнего дня ещё не запомнено")
        val batches = lines.chunked(RecordChunks.BATCH)
        val parts = ArrayList<RecordSplit.Build>()
        var finished = false
        var interrupted = false
        for ((i, batch) in batches.withIndex()) {
            if (whyNot() != null) {
                interrupted = true
                break
            }
            val last = i == batches.lastIndex
            val part = engine.buildRecordChunks(batch, finish = last)
            parts += part
            // Движок сам убирает стену и подрезает папку на последней порции
            // и на остановке.
            if (last || part.stopped) finished = true
            if (part.why != null || part.stopped) break
        }
        // Оборвали между порциями — стена убирается и папка подрезается пустой порцией.
        if (!finished && parts.none { it.why != null }) parts += engine.buildRecordChunks(emptyList(), finish = true)
        val sum = RecordSplit.sum(parts).let { if (interrupted) it.copy(stopped = true) else it }
        return RecordSplit.nightLine(lines.size, sum, null)
    }
}
