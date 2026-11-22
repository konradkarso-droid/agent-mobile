package com.uroboros.memory.judge

import com.uroboros.llm.GenerationEnd
import com.uroboros.memory.Sticker
import com.uroboros.memory.StickerDao
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgeRunTimeoutTest {
    private val records = listOf(
        Sticker(id = 1, content = "Корабль стоит в порту у причала.", createdAt = 100),
        Sticker(id = 2, content = "Корабль вышел из порта утром.", createdAt = 200),
        Sticker(id = 3, content = "Корабль вернулся в порт вечером.", createdAt = 300),
    )
    private val saved = mutableListOf<JudgeVerdict>()
    private val timeouts = MemoryTimeouts()
    private val stickers = proxy(StickerDao::class.java) { method, _ ->
        when (method.name) {
            "getExpired" -> emptyList<Sticker>()
            "getAll" -> records
            else -> error("Unexpected sticker call ${method.name}")
        }
    }
    private val verdicts = proxy(JudgeVerdictDao::class.java) { method, args ->
        when (method.name) {
            "forgetVerdictsOfDeletedStickers", "forgetOtherJudges" -> 0
            "judged" -> saved.count { it.firstId == args[0] && it.secondId == args[1] && it.loadFingerprint == args[2] }
            "put" -> { saved += args[0] as JudgeVerdict }
            else -> error("Unexpected verdict call ${method.name}")
        }
    }

    @Test
    fun timeoutInFirstAnswerDefersThePairAndLeavesOtherPairsRunnable() = runBlocking {
        var calls = 0
        val report = judge {
            if (++calls == 1) throw JudgeGenerationException("лимит", GenerationEnd.WATCHDOG_TIMEOUT)
            "2"
        }.run("test", 60_000)

        assertEquals(RunOutcome.COMPLETED, report.outcome)
        assertEquals(5, calls)
        assertEquals(2, report.judged)
        assertEquals(1, report.remaining)
        val (first, second) = report.deferredByTimeout.single()
        assertTrue(saved.none { it.firstId == first && it.secondId == second })
        assertEquals(1, judge { "2" }.run("test", 60_000).judged)
        assertEquals(3, saved.size)
        assertTrue(timeouts.all().isEmpty())
    }

    @Test
    fun timeoutInDirectReadStillFiltersLaterPairs() = runBlocking {
        var reads = 0
        val dropped = mutableSetOf<Pair<Long, Long>>()
        val sieve = object : PairSieve {
            override suspend fun begin() = Unit
            override suspend fun dropped(firstId: Long, secondId: Long) = (firstId to secondId) in dropped
            override suspend fun decide(first: Sticker, second: Sticker): SieveDecision {
                if (++reads == 1) return SieveDecision.Stop(GenerationEnd.WATCHDOG_TIMEOUT)
                dropped += first.id to second.id
                return SieveDecision.Drop
            }
            override fun report() = "прямое чтение"
        }
        val report = judge(sieve) { error("Печатному судье не было передачи") }.run("test", 60_000)
        assertEquals(3, reads)
        assertEquals(0, report.judged)
        assertEquals(2, report.droppedByDirect)
        assertEquals(1, report.remaining)
        assertEquals(1, report.deferredByTimeout.size)
    }

    @Test
    fun aSecondTimeoutMovesThePairToManualReviewUntilReturned() = runBlocking {
        var calls = 0
        val firstRun = judge {
            if (++calls == 1) throw JudgeGenerationException("лимит", GenerationEnd.WATCHDOG_TIMEOUT)
            "2"
        }.run("test", 60_000)
        val (first, second) = firstRun.deferredByTimeout.single()
        assertTrue(timeouts.pendingReview().isEmpty())

        val secondRun = judge {
            throw JudgeGenerationException("лимит", GenerationEnd.WATCHDOG_TIMEOUT)
        }.run("test", 60_000)
        assertEquals(1, secondRun.sentToManual)
        assertTrue(secondRun.deferredByTimeout.isEmpty())
        assertEquals(0, secondRun.remaining)
        assertEquals(JudgeStage.PRINTED, timeouts.pendingReview().single().stage)
        assertFalse(judge { error("Пара ждёт ручного суда") }.hasPending("test"))
        assertEquals(RunOutcome.NOTHING_TO_JUDGE, judge { error("Пара ждёт ручного суда") }.run("test", 60_000).outcome)

        assertEquals(1, timeouts.returnToQueue(first, second))
        assertTrue(judge { "2" }.hasPending("test"))
        assertEquals(1, judge { "2" }.run("test", 60_000).judged)
    }

    @Test
    fun timeoutWhileCheckingEtalonEndsTheRunBeforeAnyPair() = runBlocking {
        val sieve = object : PairSieve {
            override suspend fun begin() = Unit
            override suspend fun dropped(firstId: Long, secondId: Long) = false
            override suspend fun decide(first: Sticker, second: Sticker) =
                SieveDecision.Stop(GenerationEnd.WATCHDOG_TIMEOUT, checkingEtalon = true)
            override fun report() = "нет законченных чтений"
        }
        val report = judge(sieve) { error("Эталон не проверен") }.run("test", 60_000)
        assertEquals(RunOutcome.INTERRUPTED, report.outcome)
        assertTrue(report.interruptedBy.orEmpty().contains("проверка эталона прямого судьи"))
        assertEquals(3, report.remaining)
        assertTrue(report.deferredByTimeout.isEmpty())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun criticalZoneEndsTheRunAndKeepsAnIncompletePairPending() = runBlocking {
        var calls = 0
        val report = judge {
            calls++
            throw JudgeGenerationException("критическая зона", GenerationEnd.WATCHDOG_CRITICAL)
        }.run("test", 60_000)

        assertEquals(RunOutcome.INTERRUPTED, report.outcome)
        assertTrue(report.interruptedBy.orEmpty().contains("опасной зоне"))
        assertEquals(1, calls)
        assertEquals(3, report.remaining)
        assertTrue(report.deferredByTimeout.isEmpty())
        assertTrue(saved.isEmpty())
    }

    private fun judge(sieve: PairSieve? = null, answer: () -> String) = JudgeRun(
        { it() }, stickers, verdicts, object : MemoryJudge.Llm {
            override suspend fun answer(system: String, request: String) = answer()
        }, timeouts, sieve,
    )

    private class MemoryTimeouts : JudgeTimeoutDao {
        private val rows = mutableMapOf<JudgePairIds, JudgeTimeout>()

        override suspend fun insert(row: JudgeTimeout): Long =
            if (rows.putIfAbsent(JudgePairIds(row.firstId, row.secondId), row) == null) 1L else -1L

        override suspend fun repeat(first: Long, second: Long, stage: JudgeStage, at: Long): Int {
            val key = JudgePairIds(first, second)
            val row = rows[key]?.takeUnless { it.repeated } ?: return 0
            rows[key] = row.copy(repeated = true, stage = stage, lastAt = at)
            return 1
        }

        override suspend fun all() = rows.values.toList()
        override suspend fun held() = rows.filterValues { it.repeated }.keys.toList()
        override suspend fun pendingReview() = rows.values.filter { it.repeated && it.humanDispute == null }
        override suspend fun forgetDeletedRecords() = Unit

        override suspend fun clearFirstTimeout(first: Long, second: Long) {
            val key = JudgePairIds(first, second)
            if (rows[key]?.repeated == false) rows.remove(key)
        }

        override suspend fun returnToQueue(first: Long, second: Long): Int {
            val key = JudgePairIds(first, second)
            val row = rows[key] ?: return 0
            if (!row.repeated || row.humanDispute != null) return 0
            rows.remove(key)
            return 1
        }

        override suspend fun review(first: Long, second: Long, dispute: Boolean): Int = error("Решение человека проверяет Room")
    }

    private fun <T : Any> proxy(type: Class<T>, call: (Method, Array<out Any?>) -> Any?): T =
        requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method, args ?: emptyArray())
        }))
}
