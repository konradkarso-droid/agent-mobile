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
    /** Сколько пар прочитал прямой судья этой модели и сколько отбросил ([JudgeSieveRow]). */
    val directRead: Int = 0,
    val directDropped: Int = 0,
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
    private val sieveRows: JudgeSieveDao = MemoryDatabase.getInstance(context).judgeSieveDao()

    /**
     * Направление прямого судьи из файла приложения — один раз на сборку.
     * Нет файла или он испорчен — [DirectJudgeDirections.Parsed.Bad], и прямой
     * судья в каждом прогоне говорит об этом словами, а судит печатный.
     */
    private val directions: DirectJudgeDirections.Parsed by lazy {
        runCatching { context.assets.open(DirectJudge.ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() } }
            .fold(
                onSuccess = { DirectJudgeDirections.parse(it) },
                onFailure = { DirectJudgeDirections.Parsed.Bad("файла ${DirectJudge.ASSET} нет") },
            )
    }

    /** Отпечаток прямого судьи: модель и версия направления вместе, см. [JudgeSieveRow]. */
    private fun directFingerprint(modelIdentity: String): String {
        val version = (directions as? DirectJudgeDirections.Parsed.Ok)?.directions?.version ?: 0
        return Integer.toHexString(modelIdentity.hashCode()) + "-d" + version
    }

    /**
     * Прогон собирается на каждый вызов: у прямого судьи состояние прогона
     * (эталон, счёт прочитанного) и отпечаток, зависящий от модели.
     */
    private fun run(modelIdentity: String): JudgeRun = JudgeRun(
        engine, stickers, verdicts, EngineJudgeLlm(engine),
        DirectSieve(directions, sieveRows, directFingerprint(modelIdentity), engine::readLayerMean),
    )

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
        describe(run(modelIdentity).run(fingerprint(modelIdentity), budgetMs, onProgress))

    /** Есть ли что судить судье этой модели — без модели. См. JudgeRun.hasPending. */
    suspend fun hasPending(modelIdentity: String): Boolean = run(modelIdentity).hasPending(fingerprint(modelIdentity))

    suspend fun counters(modelIdentity: String): JudgeCounters {
        val print = fingerprint(modelIdentity)
        return JudgeCounters(
            judged = verdicts.countFor(print),
            disputes = verdicts.countVerdict(print, MemoryJudge.Verdict.DISPUTE.name),
            reviewed = verdicts.countHuman(print, HumanVerdict.CONFIRMED.name) +
                verdicts.countHuman(print, HumanVerdict.MISS.name),
            misses = verdicts.countHuman(print, HumanVerdict.MISS.name),
            byRing = ringCounters(print),
            directRead = sieveRows.countFor(directFingerprint(modelIdentity)),
            directDropped = sieveRows.countDropped(directFingerprint(modelIdentity)),
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
            if (report.droppedByDirect > 0) {
                append("Отброшено прямым судьёй (печатному не шли): ").append(report.droppedByDirect).append("\n")
            }
            // Всегда, когда прямой судья есть: молчащий и выключенный выглядели
            // бы одинаково, а причина выключения — первое, что надо видеть.
            report.direct?.let { append("Прямой судья: ").append(it).append("\n") }
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
            if (report.onlySocial.isNotEmpty()) {
                // Отдельной строкой по той же причине, что просьбы.
                append("Не судятся — один обычай: ").append(report.onlySocial.size)
                append(" зап. (")
                append(report.onlySocial.take(SHOWN_IDS).joinToString(", ") { "№$it" })
                if (report.onlySocial.size > SHOWN_IDS) append(", …")
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
