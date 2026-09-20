package com.uroboros.memory.judge

import com.uroboros.llm.GenerationEnd
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.HOT_LAYERS
import com.uroboros.memory.HourglassMemory
import com.uroboros.memory.RiskTrigger
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
    /**
     * Номера записей, которые в этот раз не судились вовсе: слишком длинные,
     * см. [JudgeRun.MAX_RECORD_CHARS]. Пустой список — таких нет.
     *
     * Номера, а не одно число: человеку нужно найти, какая именно запись
     * выпала, иначе число нечем проверить.
     */
    val tooLong: List<Long> = emptyList(),
    /**
     * Номера записей из одних вопросов — тоже не судились вовсе; почему — в
     * шапке [JudgeRun]. Пустой список — таких нет.
     */
    val onlyQuestions: List<Long> = emptyList(),
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
 * со всем остальным — кроме пар, где отчёты стоят с обеих сторон. Правило одно
 * на проект и живёт у RiskTrigger.bothAgentReports, там же и причина; здесь оно
 * только применяется.
 *
 * Порядок — от новых записей к старым, и это не украшение. Прогон почти всегда
 * обрывается на середине, и обрываться он должен на самом старом, а не на самом
 * свежем: свежая запись — та самая, которая только что могла опровергнуть
 * старую.
 *
 * Записи из одних вопросов не судятся вовсе, ни в одной паре. Судья отвечает,
 * утверждают ли две записи несовместимое, а вопрос не утверждает ничего: пара с
 * ним может дать только ложную тревогу, и человеку придётся её разбирать. Это
 * наблюдалось живьём: автозапись кладёт в память каждую отправленную реплику,
 * вопросы тоже, и судья, идущий от новых записей, тратил почти всё время на
 * пары вопросов, а все найденные им «споры» были такими парами. Признак вопроса
 * — общий с правилом противоречия (RiskTrigger.isOnlyQuestions), и его
 * промахи те же: риторический вопрос со скрытым утверждением судье больше не
 * попадёт, вопрос без знака судится как раньше. Запись при этом не меняется и
 * из памяти не уходит; её номер называется в отчёте каждого прогона.
 *
 * Слишком длинные записи не судятся вовсе, ни в одной паре, — см.
 * [MAX_RECORD_CHARS]. Без этого одна такая запись останавливает весь разбор
 * навсегда: пара с ней не укладывается в потолок сторожа, оборванная пара не
 * сохраняется, и каждый следующий прогон начинает с неё же.
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
     *
     * [onProgress] зовётся после каждой пары, которую прогон довёл до конца
     * (с вердиктом или с нечитаемым ответом), и получает их число за этот
     * прогон. Нужен тому, кто показывает ход работы снаружи: по моменту
     * последнего вызова видно, идёт ли прогон или стоит. Пары, пропущенные
     * как уже разобранные, и пара, оборванная часовым, его не зовут.
     */
    suspend fun run(
        fingerprint: String,
        budgetMs: Long,
        onProgress: (done: Int) -> Unit = {},
    ): JudgeRunReport {
        val startedAt = System.currentTimeMillis()
        verdicts.forgetVerdictsOfDeletedStickers()
        // Показания прежних судей сносятся здесь, а не при смене модели: момент
        // смены никому не виден, а начало прогона — единственное место, где
        // нынешний отпечаток точно известен. Заодно таблица всегда весит один
        // комплект, а не по комплекту на каждого судью, которого пробовали.
        verdicts.forgetOtherJudges(fingerprint)

        // Пары отбираются по слою, а слой без уборки отстаёт от часов: записи,
        // которые по времени уже остыли, судились бы как горячие, и прогон
        // тратил бы нагрев на то, чего модель в ответах уже не видит. Пул
        // снимается один раз, в начале; запись, остывшая за время прогона,
        // досуживается в нём — прогон от этого только длиннее, не неверней.
        HourglassMemory(stickers).migrateExpired()
        val candidates = stickers.getAll()
            .filter { it.layer in HOT_LAYERS }
            .filter { !it.reviewPending }
            .sortedByDescending { it.createdAt }
        val (sized, tooLong) = candidates.partition { it.content.length <= MAX_RECORD_CHARS }
        val (pool, onlyQuestions) = sized.partition { !RiskTrigger.isOnlyQuestions(it.content) }

        var judged = 0
        var disputes = 0
        var unreadable = 0
        var interruptedBy: String? = null
        var sawWork = false

        try {
            engine.withDeterministicSampling {
                outer@ for (i in pool.indices) {
                    for (k in i + 1 until pool.size) {
                        if (RiskTrigger.bothAgentReports(pool[i].source, pool[k].source)) continue
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
                            onProgress(judged + unreadable)
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
                        onProgress(judged + unreadable)
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
            remaining = unjudgedPairs(pool, fingerprint),
            spentMs = System.currentTimeMillis() - startedAt,
            interruptedBy = interruptedBy,
            tooLong = tooLong.map { it.id },
            onlyQuestions = onlyQuestions.map { it.id },
        )
    }

    /** Отчёт агента против отчёта агента — единственная пара, которую не судят. */
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

    /**
     * Сколько пар нынешнего пула этот судья ещё не разбирал.
     *
     * Считается по самим парам, а не вычитанием числа вердиктов из числа пар:
     * вердикты остаются и у пар, выпавших из пула (запись остыла, ушла в
     * очередь или оказалась вопросом), и вычитание занижало бы остаток на
     * их число, вплоть до нуля при живой очереди. Обход тот же, что в
     * основном цикле, поэтому и пары «отчёт против отчёта» пропускаются так
     * же. Цена — по короткому запросу на пару, один раз в конце прогона.
     */
    private suspend fun unjudgedPairs(pool: List<Sticker>, fingerprint: String): Int {
        var left = 0
        for (i in pool.indices) {
            for (k in i + 1 until pool.size) {
                if (RiskTrigger.bothAgentReports(pool[i].source, pool[k].source)) continue
                val (first, second) = order(pool[i], pool[k])
                if (verdicts.judged(first.id, second.id, fingerprint) == 0) left++
            }
        }
        return left
    }

    companion object {
        /**
         * Самая длинная запись, которую судья берёт в пару, в знаках.
         *
         * ОТКУДА ЧИСЛО. Оно выводится из потолка сторожа, а не подбирается:
         * одно обращение к модели не может длиться дольше пяти минут
         * (DeviceSafetyWatchdog.shouldForceCooldown), а в каждом обращении судьи
         * обе записи пары обсчитываются целиком. Обсчёт идёт порядка десяти
         * лексем в секунду (замер — в пояснении к BibleSoftWall, раздел ЦЕНА),
         * значит в потолок помещается около трёх тысяч лексем. Русский текст у
         * этой модели — около двух с половиной знаков на лексему. Две записи
         * по полторы тысячи знаков — это примерно тысяча двести лексем, вместе
         * с промптом судьи около полутора тысяч, то есть около двух минут на
         * обращение: меньше половины потолка. Запас двойной
         * намеренно: скорость обсчёта падает под нагревом и на длинном
         * запросе, а за пределом запаса ошибка не мягкая — пара рвётся и
         * держит весь разбор.
         *
         * ОБЛАСТЬ. Число верно для нынешней модели (3B) на этом телефоне. Другая
         * модель считает с другой скоростью и режет текст на лексемы
         * по-другому; тогда число выводится заново тем же путём. Перепроверяется
         * строкой «на пару … мс» в отчёте прогона: пара двух записей у самого
         * этого числа должна занимать около четырёх минут (два обращения по
         * две). Заметно больше — число пора выводить заново.
         *
         * ЧЕГО ЭТО НЕ УМЕЕТ. Запись длиннее этого числа не судится ни с кем, то есть
         * спор с ней не будет найден никогда. Она не прячется: номер её
         * называется в отчёте каждого прогона. Разбить её на части и судить по
         * частям можно, но это другой механизм, а не правка этого числа.
         */
        const val MAX_RECORD_CHARS = 1_500

        private const val REASON_BUDGET = "кончилось отведённое время"
        private const val REASON_HEAT = "устройство перегрелось"
        private const val REASON_WORK_LIMIT = "исчерпан потолок непрерывной работы"
        private const val REASON_ENGINE = "сбой движка"
    }
}
