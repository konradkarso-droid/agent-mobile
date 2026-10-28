package com.uroboros.memory.judge

import com.uroboros.llm.GenerationEnd
import com.uroboros.llm.LayerRead
import com.uroboros.memory.Sticker

/** Что сито решило о паре в этом прогоне. */
sealed class SieveDecision {
    /** Пара идёт печатному судье. */
    object Keep : SieveDecision()

    /** Пара отброшена: прямое чтение уверенно говорит «не спорят». */
    object Drop : SieveDecision()

    /** Сито в этом прогоне не работает ([why] — для экрана); пара и все дальше — печатному судье. */
    data class Off(val why: String) : SieveDecision()

    /** Сторож велел остановиться до чтения; прогон обрывается, как при остановке печатного судьи. */
    data class Stop(val end: GenerationEnd) : SieveDecision()
}

/** Сито перед печатным судьёй для одного прогона ([JudgeRun]). */
interface PairSieve {
    /** Убрать строки сита чужой модели или направления и пар с удалёнными записями. */
    suspend fun begin()

    /** Отброшена ли пара ситом раньше: тогда она разобрана, и судить её не надо. */
    suspend fun dropped(firstId: Long, secondId: Long): Boolean

    /** Решение по паре; модель читается, только если пара этим ситом ещё не читалась. */
    suspend fun decide(first: Sticker, second: Sticker): SieveDecision

    /** Что сито сделало за прогон, строкой для отчёта. */
    fun report(): String
}

/**
 * Сито прямого судьи: эталон, чтение пары в двух порядках, хранение решения.
 * Правила — в [DirectJudge]; здесь только порядок шагов и учёт.
 *
 * ЭТАЛОН ПЕРЕД ПЕРВОЙ ПАРОЙ, в каждом прогоне: четыре чтения пар с известным
 * ответом. Не узнан — сито выключено до конца прогона, и всё идёт печатному
 * судье. Так чужая модель или испорченное направление видны на экране словами,
 * а не ровными неверными числами.
 *
 * ЛЮБОЙ СБОЙ ЧТЕНИЯ ВЫКЛЮЧАЕТ СИТО НА ПРОГОН, а не пропускает пару: пропущенная
 * пара не разобрана бы ни ситом, ни судьёй, и вернулась бы в каждую ночь.
 * Выключенное сито ничего не теряет — пара просто судится как раньше.
 */
class DirectSieve(
    private val parsed: DirectJudgeDirections.Parsed,
    private val store: JudgeSieveDao,
    /** Отпечаток модели и направления вместе, см. [JudgeSieveRow]. */
    private val fingerprint: String,
    private val read: suspend (wall: String, head: String, tail: String, layer: Int) -> LayerRead,
) : PairSieve {

    private var directions: DirectJudgeDirections? = (parsed as? DirectJudgeDirections.Parsed.Ok)?.directions
    private var off: String? = (parsed as? DirectJudgeDirections.Parsed.Bad)?.let { "направление не прочитано: ${it.why}" }
    private var etalon: String? = null
    private var readPairs = 0
    private var droppedPairs = 0
    private var readMs = 0L

    override suspend fun begin() {
        store.forgetOtherSieves(fingerprint)
        store.forgetSievesOfDeletedStickers()
    }

    override suspend fun dropped(firstId: Long, secondId: Long): Boolean =
        store.get(firstId, secondId, fingerprint)?.kept == false

    override suspend fun decide(first: Sticker, second: Sticker): SieveDecision {
        off?.let { return SieveDecision.Off(it) }
        val d = directions ?: return SieveDecision.Off("направления нет")
        store.get(first.id, second.id, fingerprint)?.let { return if (it.kept) SieveDecision.Keep else SieveDecision.Drop }

        if (etalon == null) {
            val (a, b) = DirectJudge.ETALON_DISPUTE
            val (c, e) = DirectJudge.ETALON_UNRELATED
            val p = listOf(a to b, b to a, c to e, e to c).map { (x, y) ->
                when (val r = share(d, x, y)) {
                    is Share.Ok -> r.p
                    is Share.Stop -> return SieveDecision.Stop(r.end)
                    is Share.Fail -> return switchOff(r.why)
                }
            }
            DirectJudge.etalonRefusal(p[0], p[1], p[2], p[3])?.let { return switchOff(it) }
            etalon = "спор ${DirectJudge.share(p[0])}/${DirectJudge.share(p[1])}, " +
                "несвязанные ${DirectJudge.share(p[2])}/${DirectJudge.share(p[3])} — узнан"
        }

        val startedAt = System.currentTimeMillis()
        val forward = when (val r = share(d, first.content, second.content)) {
            is Share.Ok -> r.p
            is Share.Stop -> return SieveDecision.Stop(r.end)
            is Share.Fail -> return switchOff(r.why)
        }
        val backward = when (val r = share(d, second.content, first.content)) {
            is Share.Ok -> r.p
            is Share.Stop -> return SieveDecision.Stop(r.end)
            is Share.Fail -> return switchOff(r.why)
        }
        val spent = System.currentTimeMillis() - startedAt
        val kept = DirectJudge.keep(forward, backward)
        store.put(
            JudgeSieveRow(
                firstId = first.id,
                secondId = second.id,
                sieveFingerprint = fingerprint,
                pForward = forward,
                pBackward = backward,
                kept = kept,
                readAt = System.currentTimeMillis(),
                spentMs = spent,
            )
        )
        readPairs++
        readMs += spent
        if (!kept) droppedPairs++
        return if (kept) SieveDecision.Keep else SieveDecision.Drop
    }

    override fun report(): String {
        off?.let { return "выключено — $it; все пары судит печатный судья" }
        if (readPairs == 0) return etalon?.let { "эталон: $it; новых пар не читалось" } ?: "не читало — новых пар не было"
        val perPair = readMs / readPairs
        return "прочитано пар $readPairs, отброшено $droppedPairs (порог ${DirectJudge.share(DirectJudge.SIEVE_THRESHOLD)})" +
            " · на пару $perPair мс · эталон: $etalon"
    }

    private fun switchOff(why: String): SieveDecision {
        off = why
        return SieveDecision.Off(why)
    }

    private sealed class Share {
        class Ok(val p: Double) : Share()
        class Stop(val end: GenerationEnd) : Share()
        class Fail(val why: String) : Share()
    }

    /** Доля «спорят» для пары в порядке «[first], потом [second]». */
    private suspend fun share(d: DirectJudgeDirections, first: String, second: String): Share =
        when (val r = read(DirectJudge.WALL, DirectJudge.head(first), DirectJudge.tail(second), d.layer)) {
            is LayerRead.Read -> d.pDispute(r.mean)?.let { Share.Ok(it) }
                ?: Share.Fail("состояние ширины ${r.mean.size}, а направление на ${d.width}")
            is LayerRead.Stopped -> Share.Stop(r.end)
            is LayerRead.Failed -> Share.Fail("чтение не удалось: ${r.why}")
        }
}
