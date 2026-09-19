package com.uroboros.memory.judge

import android.content.Context
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.Sticker
import com.uroboros.memory.StickerDao

/** Спорная пара с текстами обеих записей — всё, что нужно экрану. */
data class DisputePair(
    val firstId: Long,
    val secondId: Long,
    val first: String,
    val second: String,
)

/** Три числа, по которым видно, работает ли разбор и читает ли его человек. */
data class JudgeCounters(
    val judged: Int,
    val disputes: Int,
    val reviewed: Int,
    val misses: Int,
)

/**
 * Судья памяти, собранный для экрана: запуск разбора, отметки человека и числа.
 *
 * Одна точка сборки, как у посредника памяти: кто зовёт разбор, тому не нужно
 * знать ни про базу, ни про хранилище вердиктов, ни про то, чем считается
 * отпечаток судьи.
 *
 * ЗДЕСЬ НЕТ РЕШЕНИЙ О ТОМ, ЧТО ЗНАЧИТ ВЕРДИКТ. Только сборка и перевод чисел в
 * текст; разбор — в [JudgeRun], вердикт — в [MemoryJudge], вид на экране — в
 * JudgeUi.
 */
class JudgeLauncher(
    context: Context,
    private val engine: LlmEngine,
) {
    private val stickers: StickerDao = MemoryDatabase.getInstance(context).stickerDao()
    private val verdicts: JudgeVerdictDao = MemoryDatabase.getInstance(context).judgeVerdictDao()
    private val run = JudgeRun(engine, stickers, verdicts, EngineJudgeLlm(engine))

    /**
     * Чем судим, короткой строкой: модель и текст промпта вместе.
     *
     * Отпечаток попадает в каждую строку хранилища, поэтому он короткий:
     * различать надо не содержание промпта, а факт его смены. Две разные
     * формулировки с одинаковым хэшем — случай, которым можно пренебречь;
     * молчаливая правка промпта — нет.
     *
     * [modelIdentity] приходит снаружи: движок своё имя наружу не отдаёт, а
     * заводить ради этого ещё один путь к нему дороже, чем передать то, что у
     * вызывающего уже есть.
     */
    private fun fingerprint(modelIdentity: String): String =
        Integer.toHexString(modelIdentity.hashCode()) + "-" +
            Integer.toHexString(MemoryJudge.SYSTEM.hashCode())

    /**
     * Разобрать, сколько успеется за [budgetMs]. Текст — для ленты, не для «Показать».
     * [onProgress] — см. JudgeRun.run.
     */
    suspend fun runAndReport(
        modelIdentity: String,
        budgetMs: Long,
        onProgress: (done: Int) -> Unit = {},
    ): String =
        describe(run.run(fingerprint(modelIdentity), budgetMs, onProgress))

    suspend fun counters(modelIdentity: String): JudgeCounters {
        val print = fingerprint(modelIdentity)
        return JudgeCounters(
            judged = verdicts.countFor(print),
            disputes = verdicts.countVerdict(print, MemoryJudge.Verdict.DISPUTE.name),
            reviewed = verdicts.countHuman(print, HumanVerdict.CONFIRMED.name) +
                verdicts.countHuman(print, HumanVerdict.MISS.name),
            misses = verdicts.countHuman(print, HumanVerdict.MISS.name),
        )
    }

    /**
     * Спорные пары, которых человек ещё не смотрел.
     *
     * Пары с исчезнувшими записями пропускаются молча: уборка снесёт их в
     * начале следующего прогона, а до тех пор показывать половину пары нечестно.
     */
    suspend fun pendingDisputes(modelIdentity: String): List<DisputePair> =
        verdicts.pairs(
            fingerprint = fingerprint(modelIdentity),
            verdict = MemoryJudge.Verdict.DISPUTE.name,
            human = HumanVerdict.UNREVIEWED.name,
        ).mapNotNull { row ->
            val first: Sticker = stickers.getById(row.firstId) ?: return@mapNotNull null
            val second: Sticker = stickers.getById(row.secondId) ?: return@mapNotNull null
            DisputePair(row.firstId, row.secondId, first.content, second.content)
        }

    /** Что человек сказал о паре. Снятие отметки — тот же вызов с UNREVIEWED. */
    suspend fun mark(modelIdentity: String, pair: DisputePair, human: HumanVerdict) {
        verdicts.mark(
            firstId = pair.firstId,
            secondId = pair.secondId,
            fingerprint = fingerprint(modelIdentity),
            human = human.name,
            at = if (human == HumanVerdict.UNREVIEWED) null else System.currentTimeMillis(),
        )
    }

    private fun describe(report: JudgeRunReport): String {
        val head = when (report.outcome) {
            RunOutcome.NOT_STARTED -> "Разбор не запускался."
            RunOutcome.NOTHING_TO_JUDGE -> "Судить нечего: все пары уже разобраны этим судьёй."
            RunOutcome.INTERRUPTED -> "Разбор оборван: ${report.interruptedBy}."
            RunOutcome.COMPLETED -> "Разбор дошёл до конца очереди."
        }
        val perPair = if (report.judged > 0) report.spentMs / report.judged else 0
        return buildString {
            append(head).append("\n")
            append("Разобрано за этот раз: ").append(report.judged).append("\n")
            append("Из них спорных: ").append(report.disputes).append("\n")
            if (report.unreadable > 0) {
                // Строка появляется, только когда есть что сказать: постоянный
                // нуль перестают замечать, а именно это число первым покажет,
                // что судья сломался.
                append("Ответов не прочитано: ").append(report.unreadable).append("\n")
            }
            if (report.tooLong.isNotEmpty()) {
                // Строка только при непустом списке, по той же причине, что и
                // строка выше. Номера даются, чтобы запись можно было найти.
                append("Не судятся — длиннее ").append(JudgeRun.MAX_RECORD_CHARS)
                append(" знаков: ").append(report.tooLong.size).append(" зап. (")
                append(report.tooLong.take(SHOWN_IDS).joinToString(", ") { "№$it" })
                if (report.tooLong.size > SHOWN_IDS) append(", …")
                append(")\n")
            }
            append("Осталось пар: ").append(report.remaining).append("\n")
            append("Времени ушло: ").append(report.spentMs / 1000).append(" с")
            if (perPair > 0) append(" · на пару ").append(perPair).append(" мс")
            append("\n\nСпорные пары — в «Показать».")
        }
    }

    private companion object {
        /** Сколько номеров длинных записей называть: список длиннее строки не читают. */
        const val SHOWN_IDS = 5
    }
}
