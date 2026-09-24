package com.uroboros.memory.judge

import android.content.Context
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.RiskTrigger
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
    /**
     * Те же числа по кольцам сита (см. JudgeSieve): внутреннее, внешнее и
     * «вне колец» — пары, которые сито сегодня не пропустило бы. Последние
     * могли судиться только до появления сита.
     *
     * Кольцо считается по текстам пары СЕЙЧАС, а не хранится с вердиктом.
     * Граница этого: поменяй правило колец — и старые вердикты разложатся уже
     * по новому. Для вопроса, ради которого счёт заведён, это верно: «что
     * находит внешнее кольцо в нынешнем виде». Пары с исчезнувшей записью не
     * посчитаны ни в одном кольце — кольцо без текста не определить.
     */
    val byRing: RingCounters = RingCounters(),
)

/** Счёт вердиктов одного кольца: судья сказал, человек подтвердил или нет. */
data class RingTally(
    val judged: Int = 0,
    val disputes: Int = 0,
    val confirmed: Int = 0,
    val misses: Int = 0,
)

/** Счёт по кольцам. [outside] — пары вне колец, судившиеся до сита. */
data class RingCounters(
    val inner: RingTally = RingTally(),
    val outer: RingTally = RingTally(),
    val outside: RingTally = RingTally(),
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
            byRing = ringCounters(print),
        )
    }

    /**
     * Счёт по кольцам. Записи читаются одним запросом, основы слов — по разу
     * на запись: вердиктов может быть сотни, записей — десятки.
     */
    private suspend fun ringCounters(print: String): RingCounters {
        val texts = stickers.getAll().associate { it.id to it.content }
        val stems = HashMap<Long, Set<String>>()
        fun stemsOf(id: Long): Set<String>? =
            texts[id]?.let { text -> stems.getOrPut(id) { RiskTrigger.significantStems(text) } }

        var inner = RingTally()
        var outer = RingTally()
        var outside = RingTally()
        for (row in verdicts.all(print)) {
            val first = stemsOf(row.firstId) ?: continue
            val second = stemsOf(row.secondId) ?: continue
            val add = { tally: RingTally ->
                tally.copy(
                    judged = tally.judged + 1,
                    disputes = tally.disputes + if (row.verdict == MemoryJudge.Verdict.DISPUTE.name) 1 else 0,
                    confirmed = tally.confirmed + if (row.humanVerdict == HumanVerdict.CONFIRMED.name) 1 else 0,
                    misses = tally.misses + if (row.humanVerdict == HumanVerdict.MISS.name) 1 else 0,
                )
            }
            when (JudgeSieve.ring(first, second)) {
                JudgeRing.INNER -> inner = add(inner)
                JudgeRing.OUTER -> outer = add(outer)
                null -> outside = add(outside)
            }
        }
        return RingCounters(inner, outer, outside)
    }

    /**
     * Спорные пары, которых человек ещё не смотрел.
     *
     * Пары с исчезнувшими записями пропускаются молча: уборка снесёт их в
     * начале следующего прогона, а до тех пор показывать половину пары нечестно.
     *
     * Пары, где одна сторона отвергнута человеком, тоже не показываются: спор
     * по ним уже решён, отвергнутая запись в ответы не попадёт. Вердикт судьи
     * при этом остаётся в хранилище и в счёт спорных входит — он о судье, а не
     * о памяти.
     */
    suspend fun pendingDisputes(modelIdentity: String): List<DisputePair> =
        verdicts.pairs(
            fingerprint = fingerprint(modelIdentity),
            verdict = MemoryJudge.Verdict.DISPUTE.name,
            human = HumanVerdict.UNREVIEWED.name,
        ).mapNotNull { row ->
            val first: Sticker = stickers.getById(row.firstId) ?: return@mapNotNull null
            val second: Sticker = stickers.getById(row.secondId) ?: return@mapNotNull null
            if (first.rejectedAt != null || second.rejectedAt != null) return@mapNotNull null
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
            if (report.onlyQuestions.isNotEmpty()) {
                // Та же форма, что у строки выше, и по той же причине.
                append("Не судятся — одни вопросы: ").append(report.onlyQuestions.size)
                append(" зап. (")
                append(report.onlyQuestions.take(SHOWN_IDS).joinToString(", ") { "№$it" })
                if (report.onlyQuestions.size > SHOWN_IDS) append(", …")
                append(")\n")
            }
            if (report.onlyRequests.isNotEmpty()) {
                // Отдельной строкой от вопросов: ложное срабатывание признака
                // просьб не должно прятаться в числе вопросов.
                append("Не судятся — одни просьбы: ").append(report.onlyRequests.size)
                append(" зап. (")
                append(report.onlyRequests.take(SHOWN_IDS).joinToString(", ") { "№$it" })
                if (report.onlyRequests.size > SHOWN_IDS) append(", …")
                append(")\n")
            }
            if (report.outsideSieve > 0) {
                // Число, а не номера: таких пар большинство. Строка нужна,
                // чтобы «осталось 0» не читалось как «все пары разобраны».
                append("Не судятся — нет общих слов: ").append(report.outsideSieve)
                append(" пар\n")
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
