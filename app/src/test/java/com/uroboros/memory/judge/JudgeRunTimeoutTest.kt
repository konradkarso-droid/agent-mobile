package com.uroboros.memory.judge

import com.uroboros.llm.GenerationEnd
import com.uroboros.memory.Sticker
import com.uroboros.memory.StickerDao
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgeRunTimeoutTest {
    private val records = listOf(
        Sticker(id = 1, content = "Корабль стоит в порту у причала.", createdAt = 100),
        Sticker(id = 2, content = "Корабль вышел из порта утром.", createdAt = 200),
        Sticker(id = 3, content = "Корабль вернулся в порт вечером.", createdAt = 300),
    )
    private val saved = mutableListOf<JudgeVerdict>()
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
    }

    @Test
    fun timeoutInDirectReadLetsThePrintedJudgeHandleTheRest() = runBlocking {
        var reads = 0
        val sieve = object : PairSieve {
            override suspend fun begin() = Unit
            override suspend fun dropped(firstId: Long, secondId: Long) = false
            override suspend fun decide(first: Sticker, second: Sticker): SieveDecision {
                reads++
                return SieveDecision.Stop(GenerationEnd.WATCHDOG_TIMEOUT)
            }
            override fun report() = "прямое чтение"
        }
        val report = judge(sieve) { "2" }.run("test", 60_000)
        assertEquals(1, reads)
        assertEquals(2, report.judged)
        assertEquals(1, report.remaining)
        assertEquals(1, report.deferredByTimeout.size)
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
        }, sieve,
    )

    private fun <T : Any> proxy(type: Class<T>, call: (Method, Array<out Any?>) -> Any?): T =
        requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method, args ?: emptyArray())
        }))
}
