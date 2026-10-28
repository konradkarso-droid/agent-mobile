package com.uroboros.memory.judge

import com.uroboros.llm.GenerationEnd
import com.uroboros.llm.LayerRead
import com.uroboros.memory.Sticker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сито прямого судьи с подставным чтением: «состояние» — одно число, доля
 * «спорят» выходит из него через направление ширины 1 (логит спора = x,
 * остальные 0). Числа выбираются тестом, модели нет.
 */
class DirectSieveTest {

    private val directions = DirectJudgeDirections.parse(
        """
        версия 1
        слой 21
        ширина 1
        mu 0
        sd 1
        w_spor 1
        w_soglasie 0
        w_nesvyazany 0
        b 0 0 0
        """.trimIndent()
    )

    private class Store : JudgeSieveDao {
        val rows = mutableMapOf<Pair<Long, Long>, JudgeSieveRow>()
        override suspend fun put(row: JudgeSieveRow) { rows[row.firstId to row.secondId] = row }
        override suspend fun get(firstId: Long, secondId: Long, fingerprint: String) =
            rows[firstId to secondId]?.takeIf { it.sieveFingerprint == fingerprint }
        override suspend fun countFor(fingerprint: String) = rows.values.count { it.sieveFingerprint == fingerprint }
        override suspend fun countDropped(fingerprint: String) = rows.values.count { it.sieveFingerprint == fingerprint && !it.kept }
        override suspend fun forgetOtherSieves(fingerprint: String) = 0
        override suspend fun forgetSievesOfDeletedStickers() = 0
    }

    /**
     * Подставное чтение: тест задаёт долю «спорят» (или готовый исход чтения),
     * состояние подбирается так, чтобы направление дало ровно её:
     * p = e^x / (e^x + 2). Считает чтения.
     */
    private class Reader(val share: (head: String, tail: String) -> Any) {
        var reads = 0
        suspend fun read(wall: String, head: String, tail: String, layer: Int): LayerRead {
            reads++
            return when (val r = share(head, tail)) {
                is Double -> LayerRead.Read(floatArrayOf(kotlin.math.ln(2 * r / (1 - r)).toFloat()))
                is LayerRead -> r
                else -> error("не то")
            }
        }
    }

    /** Эталон проходит: спор 0,95, несвязанные 0,02; прочие пары — [pairShare]. */
    private fun reader(pairShare: (String, String) -> Any) = Reader { head, tail ->
        val (dA, dB) = DirectJudge.ETALON_DISPUTE
        val (uA, uB) = DirectJudge.ETALON_UNRELATED
        when {
            head.endsWith("Запись А: $dA\nЗапись Б:") && tail == " $dB" -> 0.95
            head.endsWith("Запись А: $dB\nЗапись Б:") && tail == " $dA" -> 0.95
            head.endsWith("Запись А: $uA\nЗапись Б:") && tail == " $uB" -> 0.02
            head.endsWith("Запись А: $uB\nЗапись Б:") && tail == " $uA" -> 0.02
            else -> pairShare(head, tail)
        }
    }

    private fun sticker(id: Long, text: String) = Sticker(id = id, content = text)

    private fun sieve(store: Store, r: Reader) = DirectSieve(directions, store, "fp", r::read)

    @Test
    fun `уверенное «не спорят» отбрасывается и запоминается`() = runBlocking {
        val store = Store()
        val r = reader { _, _ -> 0.05 }
        val s = sieve(store, r)
        assertEquals(SieveDecision.Drop, s.decide(sticker(1, "а"), sticker(2, "б")))
        assertTrue(s.dropped(1, 2))
        assertEquals(6, r.reads) // эталон 4 + пара 2
    }

    @Test
    fun `сомнительная пара идёт печатному судье и отброшенной не считается`() = runBlocking {
        val store = Store()
        val s = sieve(store, reader { _, tail -> if (tail == " б") 0.3 else 0.05 })
        assertEquals(SieveDecision.Keep, s.decide(sticker(1, "а"), sticker(2, "б")))
        assertFalse(s.dropped(1, 2))
    }

    @Test
    fun `прочитанная пара не читается второй раз`() = runBlocking {
        val store = Store()
        val r = reader { _, _ -> 0.05 }
        sieve(store, r).decide(sticker(1, "а"), sticker(2, "б"))
        val again = reader { _, _ -> error("второе чтение") }
        assertEquals(SieveDecision.Drop, sieve(store, again).decide(sticker(1, "а"), sticker(2, "б")))
    }

    @Test
    fun `эталон не узнан — ни одна пара не отбрасывается`() = runBlocking {
        val store = Store()
        // чужая модель: всё ровно
        val s = sieve(store, Reader { _, _ -> 0.33 })
        assertTrue(s.decide(sticker(1, "а"), sticker(2, "б")) is SieveDecision.Off)
        assertTrue(s.decide(sticker(3, "в"), sticker(4, "г")) is SieveDecision.Off)
        assertTrue(store.rows.isEmpty())
        assertTrue(s.report().startsWith("выключено"))
    }

    @Test
    fun `сбой чтения выключает сито на весь прогон, пара не пропадает`() = runBlocking {
        val store = Store()
        val s = sieve(store, reader { _, _ -> LayerRead.Failed("сбой") })
        assertTrue(s.decide(sticker(1, "а"), sticker(2, "б")) is SieveDecision.Off)
        assertFalse(s.dropped(1, 2))
        assertTrue(s.decide(sticker(3, "в"), sticker(4, "г")) is SieveDecision.Off)
    }

    @Test
    fun `сторож остановил — прогон обрывается, решения нет`() = runBlocking {
        val store = Store()
        val s = sieve(store, reader { _, _ -> LayerRead.Stopped(GenerationEnd.WATCHDOG_CRITICAL) })
        assertEquals(SieveDecision.Stop(GenerationEnd.WATCHDOG_CRITICAL), s.decide(sticker(1, "а"), sticker(2, "б")))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `нет направления — сито молчит словами`() = runBlocking {
        val s = DirectSieve(DirectJudgeDirections.Parsed.Bad("нет файла"), Store(), "fp") { _, _, _, _ -> error("читать нельзя") }
        assertTrue(s.decide(sticker(1, "а"), sticker(2, "б")) is SieveDecision.Off)
        assertTrue(s.report().contains("нет файла"))
    }
}
