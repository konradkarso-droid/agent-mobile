package com.uroboros.memory.judge

import com.uroboros.llm.GenerationEnd
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.HOT_LAYERS
import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import com.uroboros.memory.StickerDao
import kotlinx.coroutines.CancellationException

/** Чем кончился прогон. Четыре разных ответа, которые нельзя сводить к одному. */
enum class RunOutcome {
    /** Прогон не запускали. Начальное состояние прибора, не результат. */
    NOT_STARTED,

    /**
     * Запускали, но судить было нечего: все пары уже разобраны этим судьёй.
     *
     * Отдельно от [COMPLETED] намеренно. «Разобрал и ничего не нашёл» и «делать
     * было нечего» выглядят на экране одинаково — пусто, — а означают разное, и
     * без этого различия молчащий механизм не отличить от отработавшего.
     */
    NOTHING_TO_JUDGE,

    /** Оборван: кончилось время, вмешался часовой или сломался движок. См. [JudgeRunReport.interruptedBy]. */
    INTERRUPTED,

    /** Дошёл до конца очереди. */
    COMPLETED,
}

/**
 * Что прогон сделал. Всё, что показывается человеку, берётся отсюда.
 *
 * [remaining] считается по хранилищу, а не вычитанием сделанного из
 * задуманного: пары могли появиться прямо во время прогона, и разность
 * показывала бы вчерашнюю картину.
 */
data class JudgeRunReport(
    val outcome: RunOutcome,
    val judged: Int,
    val disputes: Int,
    val unreadable: Int,
    val remaining: Int,
    val spentMs: Long,
    val interruptedBy: String? = null,
)

/**
 * Ночной разбор памяти: кто с кем спорит.
 *
 * Как выбираются пары. Берутся записи, которые модель и вправду читает в
 * ответах: из горячих слоёв и не стоящие в очереди на проверку. Остывшая запись
 * в ответ не попадает, значит и ввести в заблуждение никого не может, а запись
 * из очереди скрыта до решения человека — судить её означало бы спорить о том,
 * чего агент всё равно не видит, да ещё и вторым путём после правила.
 *
 * Метка записи НЕ учитывается, в отличие от правила: метку сегодня никто не
 * проставляет, и фильтр по ней молча прятал бы споры между темами, когда её
 * начнут ставить.
 *
 * Отчёты агента о собственной работе (провенанс AGENT_INFERRED) судятся наравне
 * со всем остальным — кроме пар, где отчёты стоят с обеих сторон: двум отчётам
 * о разных прогонах спорить не о чем. А отчёт против слов человека спорить
 * может, и этот случай наблюдался живьём: модель пересказала пользователю его
 * же словами то, что на самом деле было отчётом агента.
 *
 * Порядок — от новых записей к старым, и это не украшение. Прогон почти всегда
 * обрывается на середине, и обрываться он должен на самом старом, а не на самом
 * свежем: свежая запись — та самая, которая только что могла опровергнуть
 * старую.
 *
 * Продолжение с места держится на хранилище вердиктов: разобранная пара — это
 * пара, у которой есть строка. Отдельного курсора нет намеренно, он был бы
 * вторым местом для той же мысли и разошёлся бы с хранилищем молча.
 *
 * ЧЕГО ПРОГОН НЕ ДЕЛАЕТ. Он не меняет память: ни одной записи не правит, не
 * удаляет и не помечает. Его выход — вердикты, которые читает человек.
 */
class JudgeRun(
    private val engine: LlmEngine,
    private val stickers: StickerDao,
    private val verdicts: JudgeVerdictDao,
    private val llm: MemoryJudge.Llm,
) {

    /**
     * Разобрать, сколько успеется за [budgetMs].
     *
     * [fingerprint] — чем судим: модель, её загрузка и текст промпта вместе.
     * Пары, разобранные другим судьёй, в счёт не идут и будут разобраны заново;
     * см. [JudgeVerdict].
     *
     * Бюджет проверяется между парами, а не внутри: пара судится целиком или не
     * судится вовсе, потому что вердикт по одному порядку — не вердикт.
     * Значит прогон перебирает бюджет на время последней пары.
     */
    suspend fun run(fingerprint: String, budgetMs: Long): JudgeRunReport {
        val startedAt = System.currentTimeMillis()
        verdicts.forgetVerdictsOfDeletedStickers()
        // Показания прежних судей сносятся здесь, а не при смене модели: момент
        // смены никому не виден, а начало прогона — единственное место, где
        // нынешний отпечаток точно известен. Заодно таблица всегда весит один
        // комплект, а не по комплекту на каждого судью, которого пробовали.
        verdicts.forgetOtherJudges(fingerprint)

        val pool = stickers.getAll()
            .filter { it.layer in HOT_LAYERS }
            .filter { !it.reviewPending }
            .sortedByDescending { it.createdAt }

        var judged = 0
        var disputes = 0
        var unreadable = 0
        var interruptedBy: String? = null
        var sawWork = false

        try {
            engine.withDeterministicSampling {
                outer@ for (i in pool.indices) {
                    for (k in i + 1 until pool.size) {
                        if (bothAgentReports(pool[i], pool[k])) continue
                        val (first, second) = order(pool[i], pool[k])
                        if (verdicts.judged(first.id, second.id, fingerprint) > 0) continue
                        sawWork = true

                        if (System.currentTimeMillis() - startedAt >= budgetMs) {
                            interruptedBy = REASON_BUDGET
                            break@outer
                        }

                        val pairStartedAt = System.currentTimeMillis()
                        val judgement = MemoryJudge.judge(llm, first.content, second.content)
                        val spent = System.currentTimeMillis() - pairStartedAt

                        val stoppedBy = watchdogStop()
                        if (stoppedBy != null) {
                            // Ответ, обрезанный часовым, — не ответ, и в хранилище он не
                            // едет: пара останется неразобранной и достанется следующему
                            // прогону целой.
                            interruptedBy = stoppedBy
                            break@outer
                        }

                        if (judgement.verdict == MemoryJudge.Verdict.UNREADABLE) {
                            unreadable++
                            continue
                        }

                        verdicts.put(
                            JudgeVerdict(
                                firstId = first.id,
                                secondId = second.id,
                                loadFingerprint = fingerprint,
                                forward = judgement.forward.toString(),
                                backward = judgement.backward.toString(),
                                verdict = judgement.verdict.name,
                                judgedAt = System.currentTimeMillis(),
                                spentMs = spent,
                            )
                        )
                        judged++
                        if (judgement.verdict == MemoryJudge.Verdict.DISPUTE) disputes++
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // Отмена — не исход прогона, а конец всей работы: экран закрыли или
            // область отменили. Наверх она обязана уйти как есть, иначе вызывающий
            // решит, что прогон просто не дотянул, и запустит его снова.
            throw cancelled
        } catch (broken: JudgeGenerationException) {
            interruptedBy = broken.message ?: REASON_ENGINE
        }

        val outcome = when {
            interruptedBy != null -> RunOutcome.INTERRUPTED
            !sawWork -> RunOutcome.NOTHING_TO_JUDGE
            else -> RunOutcome.COMPLETED
        }
        return JudgeRunReport(
            outcome = outcome,
            judged = judged,
            disputes = disputes,
            unreadable = unreadable,
            remaining = judgeablePairs(pool) - verdicts.countFor(fingerprint),
            spentMs = System.currentTimeMillis() - startedAt,
            interruptedBy = interruptedBy,
        )
    }

    /** Отчёт агента против отчёта агента — единственная пара, которую не судят. */
    private fun bothAgentReports(one: Sticker, other: Sticker): Boolean =
        one.source == SourceKind.AGENT_INFERRED.name &&
            other.source == SourceKind.AGENT_INFERRED.name

    /** Пара неупорядочена: меньший номер первым — см. [JudgeVerdict]. */
    private fun order(one: Sticker, other: Sticker): Pair<Sticker, Sticker> =
        if (one.id <= other.id) one to other else other to one

    /**
     * Оборвал ли движок последнюю выдачу по своим причинам.
     *
     * Спрашивается у движка, а не у часового напрямую: решение о том, когда
     * останавливаться, принимается внутри движка в одном месте, и второй
     * спрашивающий со своим условием разошёлся бы с первым молча.
     */
    private fun watchdogStop(): String? = when (engine.lastGenerationEnd) {
        GenerationEnd.WATCHDOG_CRITICAL -> REASON_HEAT
        GenerationEnd.WATCHDOG_TIMEOUT -> REASON_WORK_LIMIT
        else -> null
    }

    /** Сколько пар в пуле вообще подлежит суду — без пар «отчёт против отчёта». */
    private fun judgeablePairs(pool: List<Sticker>): Int {
        val all = pool.size * (pool.size - 1) / 2
        val reports = pool.count { it.source == SourceKind.AGENT_INFERRED.name }
        return all - reports * (reports - 1) / 2
    }

    private companion object {
        const val REASON_BUDGET = "кончилось отведённое время"
        const val REASON_HEAT = "устройство перегрелось"
        const val REASON_WORK_LIMIT = "исчерпан потолок непрерывной работы"
        const val REASON_ENGINE = "сбой движка"
    }
}
