package com.uroboros.memory.judge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.StickerDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Спорная пара с текстами обеих записей — всё, что нужно экрану. */
data class DisputePair(
    val firstId: Long,
    val secondId: Long,
    val first: String,
    val second: String,
)

data class ManualJudgePair(val pair: DisputePair, val stage: JudgeStage)

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
    private val resolver = context.applicationContext.contentResolver
    private val db = MemoryDatabase.getInstance(context)
    private val stickers: StickerDao = db.stickerDao()
    private val verdicts: JudgeVerdictDao = db.judgeVerdictDao()
    private val sieveRows: JudgeSieveDao = db.judgeSieveDao()
    private val timeouts = db.judgeTimeoutDao()

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

    /** Версия направления прямого судьи; 0 — направления нет. */
    private fun directVersion(): Int = (directions as? DirectJudgeDirections.Parsed.Ok)?.directions?.version ?: 0

    /** Отпечаток прямого судьи: модель и версия направления вместе, см. [JudgeSieveRow] и [JudgePrint]. */
    private suspend fun directFingerprint(modelIdentity: String): String = JudgePrint.direct(modelOf(modelIdentity), directVersion())

    /** Имя и размер файла модели; null — не прочитались. */
    private class ModelFile(val name: String?, val size: Long?)

    /** Прочитанное о файлах моделей — по адресу, на время жизни этого сборщика. */
    private val files = ConcurrentHashMap<String, ModelFile>()

    /**
     * Имя и размер файла по адресу [modelIdentity]; не прочиталось — пусто
     * (JudgePrint, «ЧЕГО НЕ УМЕЕТ»). Чтение — обращение к системе за файлом,
     * поэтому не на главном потоке.
     */
    private suspend fun fileOf(modelIdentity: String): ModelFile = files[modelIdentity] ?: withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(
                Uri.parse(modelIdentity), arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) null
                else ModelFile(c.getString(0), if (c.isNull(1)) null else c.getLong(1))
            }
        }.getOrNull() ?: ModelFile(null, null)
    }.also { files[modelIdentity] = it }

    /** Модель для отпечатков — по имени и размеру файла, см. [JudgePrint]. */
    private suspend fun modelOf(modelIdentity: String): String =
        fileOf(modelIdentity).let { JudgePrint.model(modelIdentity, it.name, it.size) }

    /**
     * Перенести строки, снятые под отпечатком по адресу файла, на отпечаток по
     * имени и размеру (JudgePrint, «ПРЕЖНИЙ ОТПЕЧАТОК ПЕРЕНОСИТСЯ»). Зовётся
     * перед каждым чтением и прогоном: прогон в начале сносит чужих судей, и
     * перенос обязан успеть раньше. Когда переносить нечего, это два пустых
     * запроса.
     */
    private suspend fun carry(modelIdentity: String) {
        val model = modelOf(modelIdentity)
        val legacy = JudgePrint.legacyModel(modelIdentity)
        if (model == legacy) return
        verdicts.carry(JudgePrint.printed(legacy, MemoryJudge.SYSTEM), JudgePrint.printed(model, MemoryJudge.SYSTEM))
        sieveRows.carry(JudgePrint.direct(legacy, directVersion()), JudgePrint.direct(model, directVersion()))
    }

    /** Строка «Судья:» отчёта прогона — по чему узнаётся судья. */
    private suspend fun judgeLine(modelIdentity: String): String {
        val file = fileOf(modelIdentity)
        val name = file.name
        val size = file.size
        return if (name.isNullOrBlank() || size == null || size <= 0) {
            "Судья: имя или размер файла модели не прочитались — узнаётся по адресу файла, выбор файла заново начнёт суд сначала"
        } else {
            "Судья: $name (" + String.format(Locale.US, "%.1f ГБ", size / (1024.0 * 1024 * 1024)) + ")"
        }
    }

    /**
     * Прогон собирается на каждый вызов: у прямого судьи состояние прогона
     * (эталон, счёт прочитанного) и отпечаток, зависящий от модели.
     */
    private suspend fun run(modelIdentity: String): JudgeRun = JudgeRun(
        engine, stickers, verdicts, EngineJudgeLlm(engine), timeouts,
        DirectSieve(directions, sieveRows, directFingerprint(modelIdentity), engine::readLayerMean),
    )

    /**
     * Чем судим, короткой строкой: модель и текст промпта вместе.
     *
     * Отпечаток попадает в каждую строку хранилища, поэтому он короткий:
     * различать надо не содержание промпта, а факт его смены. Две разные
     * формулировки с одинаковым хэшем — случай, которым можно пренебречь;
     * молчаливая правка промпта — нет. Что значит «модель» — у [JudgePrint].
     *
     * [modelIdentity] — адрес файла модели; приходит снаружи: движок своё имя
     * наружу не отдаёт, а заводить ради этого ещё один путь к нему дороже, чем
     * передать то, что у вызывающего уже есть.
     */
    private suspend fun fingerprint(modelIdentity: String): String =
        JudgePrint.printed(modelOf(modelIdentity), MemoryJudge.SYSTEM)

    /**
     * Разобрать, сколько успеется за [budgetMs]. Текст — для ленты, не для «Показать».
     * [onProgress] — см. JudgeRun.run.
     */
    suspend fun runAndReport(
        modelIdentity: String,
        budgetMs: Long,
        onProgress: (done: Int) -> Unit = {},
    ): String {
        carry(modelIdentity)
        return describe(run(modelIdentity).run(fingerprint(modelIdentity), budgetMs, onProgress)) +
            "\n" + judgeLine(modelIdentity)
    }

    /** Есть ли что судить судье этой модели — без модели. См. JudgeRun.hasPending. */
    suspend fun hasPending(modelIdentity: String): Boolean {
        carry(modelIdentity)
        return run(modelIdentity).hasPending(fingerprint(modelIdentity))
    }

    suspend fun counters(modelIdentity: String): JudgeCounters {
        carry(modelIdentity)
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
    suspend fun pendingDisputes(modelIdentity: String): List<DisputePair> {
        carry(modelIdentity)
        return verdicts.pairs(
            fingerprint = fingerprint(modelIdentity),
            verdict = MemoryJudge.Verdict.DISPUTE.name,
            human = HumanVerdict.UNREVIEWED.name,
        ).mapNotNull { row -> visiblePair(row.firstId, row.secondId) }
    }

    suspend fun pendingManual(): List<ManualJudgePair> = timeouts.pendingReview().mapNotNull { row ->
        visiblePair(row.firstId, row.secondId)?.let { ManualJudgePair(it, row.stage) }
    }

    suspend fun reviewManual(pair: DisputePair, dispute: Boolean) {
        timeouts.review(pair.firstId, pair.secondId, dispute)
    }

    suspend fun returnToQueue(pair: DisputePair) {
        timeouts.returnToQueue(pair.firstId, pair.secondId)
    }

    private suspend fun visiblePair(firstId: Long, secondId: Long): DisputePair? {
        val first = stickers.getById(firstId) ?: return null
        val second = stickers.getById(secondId) ?: return null
        if (first.rejectedAt != null || second.rejectedAt != null) return null
        return DisputePair(firstId, secondId, first.content, second.content)
    }

    /** Что человек сказал о паре. Снятие отметки — тот же вызов с UNREVIEWED. */
    suspend fun mark(modelIdentity: String, pair: DisputePair, human: HumanVerdict) {
        carry(modelIdentity)
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
            RunOutcome.NOTHING_TO_JUDGE -> "В автоматической очереди новых пар нет."
            RunOutcome.INTERRUPTED -> "Разбор оборван: ${report.interruptedBy}."
            RunOutcome.COMPLETED -> if (report.deferredByTimeout.isEmpty()) {
                "Разбор дошёл до конца очереди."
            } else {
                "Разбор дошёл до конца очереди. Отложено по потолку: ${report.deferredByTimeout.size} пар."
            }
        }
        val perPair = if (report.judged > 0) report.spentMs / report.judged else 0
        return buildString {
            append(head)
            if (report.sentToManual > 0) {
                append(" На ручной суд после второй отсечки: ").append(report.sentToManual).append(".")
            }
            append("\n")
            append("Разобрано за этот раз: ").append(report.judged).append("\n")
            append("Из них спорных: ").append(report.disputes).append("\n")
            if (report.droppedByDirect > 0) {
                append("Отброшено прямым судьёй (печатному не шли): ").append(report.droppedByDirect).append("\n")
            }
            // Всегда, когда прямой судья есть: молчащий и выключенный выглядели
            // бы одинаково, а причина выключения — первое, что надо видеть.
            report.direct?.let { append("Прямой судья: ").append(it).append("\n") }
            if (report.deferredByTimeout.isNotEmpty()) {
                append("Отложены до следующего прогона после лимита одного обращения: ")
                append(report.deferredByTimeout.size).append(" пар (")
                append(report.deferredByTimeout.take(SHOWN_IDS).joinToString(", ") { (a, b) -> "№$a и №$b" })
                if (report.deferredByTimeout.size > SHOWN_IDS) append(", …")
                append(")\n")
            }
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
            append("Осталось пар в автоматической очереди: ").append(report.remaining).append("\n")
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
