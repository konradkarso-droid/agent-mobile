package com.uroboros.llm

import com.dark.gguf_lib.models.DecodingMetrics
import com.dark.gguf_lib.models.GenerationEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile

/**
 * Лестница ответа — одна на всех, кто отвечает собеседнику: на экран
 * (ConversationTurns) и в чаты телеграма (gate.link.ChatLink). Первый проход
 * с ловлей вызова приборов, взгляд на приборы, перехват готового ответа
 * (повтор, зеркало, порча слов), вторая попытка, три варианта.
 *
 * ПОЧЕМУ ОДНА. Две лестницы — две копии одних правил, и разошлись бы они
 * молча: на экране перехват есть, в телеграме нет, а с виду и там и там
 * «ответил». Здесь всё, что решает, какой ответ годен и что делать дальше.
 * Канал отвечает только за то, чем он правда отличается ([Ribbon]): из чего
 * собирается запрос, как ход ложится в его ленту и что значит «годного ответа
 * нет». На экране такой ход скрывается, и владелец видит заглушку; в
 * телеграме скрыть ход — значит промолчать живому человеку, поэтому там
 * уходит первый ответ (решение владельца, см. ChatLink).
 *
 * Чистая логика: движок приходит снаружи ([Engine]), Android здесь нет —
 * поэтому лестница закрепляется обычными тестами.
 *
 * ПОРЯДОК ХОДА:
 *  1. первый проход. Вызов приборов обрывает выдачу: дальше модель писала бы
 *     только хвост вызова. Позвала — показать доску и спросить снова; ответ
 *     хода — проход после доски. Второй вызов подряд доску снова не
 *     показывает: ответ режется по нему. После доски пусто — тот же проход
 *     ещё ОДИН раз (зерно у каждого запроса своё, LlmEngine.guardedFlow), и
 *     только если прошлый кончился сам или обрезан на вызове: оборванный
 *     сторожем или сбоем — это нагрев или поломка, от которых и обрывали.
 *     Третьего прохода нет намеренно: каждый — полный обсчёт хвоста запроса;
 *  2. готовый ответ меряется ([causeOf]) — повтор, зеркало, порча; петля до
 *     потолка ([loopAtCeiling]) ход скрывает без второй попытки;
 *  3. пойманный — вторая попытка ([secondAttempt]), негодная вторая — три
 *     варианта ([thirdAttempt]).
 * Перед каждым следующим проходом спрашивается [run] `halt`: стоп, ночь,
 * сторож. Остановленная лестница больше не зовёт модель, а ход ложится тем,
 * что уже есть.
 */
class AnswerLadder(
    private val engine: Engine,
    /** Потолок длины ответа канала, токенов: петля до него — признак обрыва ([loopAtCeiling]). */
    private val answerLimit: Int,
    private val damage: () -> WordDamage? = { WordDamageHolder.damage },
    private val warn: (String, Throwable) -> Unit = { _, _ -> },
) {

    /** Движок для лестницы: один прогон и итог последнего. */
    interface Engine {
        /**
         * Прогон по [messages]. [dryAgainst] — текст, против копий которого
         * включается штраф DRY, null — выключен; [stableMessages] — граница
         * снимка ленты, null — не снимать; [first] — первый проход хода (только
         * ему положено то, что делается один раз на ход, — живой отвод слоя).
         */
        fun generate(messages: List<Pair<String, String>>, dryAgainst: String?, stableMessages: Int?, first: Boolean): Flow<GenerationEvent>

        /** Чем кончился последний прогон. */
        val lastEnd: GenerationEnd

        /** Сколько токенов DRY увидел в последнем прогоне. */
        val lastDryFed: Int

        /** Стена — для слов запроса детектора порчи. */
        val wallText: String?
    }

    /** Лента канала: из чего собирается запрос хода и как ход в неё ложится. */
    interface Ribbon {
        /** Ходы до этого ответа, от старых к новым: по ним меряется повтор, их номера принимает [messages]. */
        fun history(): List<ConversationJournal.Turn>

        /** Запрос хода без ходов с номерами [without]. */
        fun messages(without: Set<Int>): List<Pair<String, String>>

        /**
         * Граница снимка ленты для запроса без [without]; null — не снимать.
         * Запросу с выкинутыми ходами снимок не положен: граница с дырой не
         * совпадёт со следующим ходом, а снимок первого прохода она заменила бы.
         */
        fun stableMessages(without: Set<Int>): Int?

        /**
         * Строки вспоминания этого хода со своей речью агента
         * ([EchoIntercept.ownSpeech]); пусто — у ленты вспоминания нет.
         */
        fun ownSpeech(): List<String> = emptyList()

        /**
         * Запрос без ходов [without] и без строк вспоминания [withoutRecall] —
         * второй попытке после копии записи своей речи ([EchoIntercept]). Ход,
         * закрытый ответом этой попытки (`rejected` не null), хранит
         * вспоминание без этих строк: ход хранит то, что ушло.
         */
        fun messages(without: Set<Int>, withoutRecall: Set<String>): List<Pair<String, String>> = messages(without)

        /** Ход ложится ответом [answer]; [rejected] — отброшенный первый ответ или null. */
        fun close(answer: String, rejected: String?)

        /**
         * Годного ответа нет. [reason] — почему, словами; [first] — первый ответ
         * хода; [rejected] — все отброшенные тексты по порядку.
         */
        fun hide(reason: String, first: String, rejected: List<ConversationJournal.Rejected>)
    }

    /** Почему первый ответ хода перехвачен. */
    sealed class Cause {
        /**
         * Повтор своего прошлого ответа ([EchoIntercept]): вторая попытка без
         * ходов-образцов [without] и без строк вспоминания своей речи
         * [withoutRecall]. [sentence] — первое повторённое предложение.
         */
        data class Repeat(val sentence: String, val without: Set<Int>, val withoutRecall: Set<String> = emptySet()) : Cause()

        /**
         * Порча слов без повтора ([WordDamage]): повторная выборка на той же
         * ленте. Запрос тот же, поэтому пересчёта ленты нет — платится только
         * сам ответ. Другим ответ выходит потому, что у каждого ответа
         * разговора своё зерно (LlmEngine.guardedFlow). [words] — найденные
         * куски, как их показать.
         */
        data class Damage(val words: List<String>) : Cause()

        /**
         * Зеркало реплики собеседника ([EchoCheck.mirrorOf]): вторая попытка на
         * том же запросе, но со штрафом DRY против его реплики этого хода —
         * источник копии реплика, а выкинуть её из запроса нельзя: на неё и
         * отвечают. [sentence] — первое отражённое предложение.
         */
        data class Mirror(val sentence: String) : Cause()
    }

    /**
     * Перехват на этом ходе: первый ответ пойман ([cause]), прошла вторая попытка.
     *
     * @property secondAnswer текст второй попытки, как выдан (возможно, оборванный).
     * @property secondEnd чем кончилась вторая попытка.
     * @property secondMs сколько она шла, от запуска до конца выдачи.
     * @property secondTokens сколько токенов она выдала — по отчёту движка; null —
     *   отчёта не пришло. Счёт событий Token токенами не является: движок шлёт
     *   текст пачками, по нескольку токенов в событии.
     * @property replaced в ленту лёг второй ответ (или вариант третьей ступени), а
     *   первый — отброшенным. false у скрытого хода ([Third.hidden]) и когда —
     *   в ленту лёг первый ответ как есть: вторая попытка не состоялась (оборвана,
     *   сбой, ноль токенов) или, при порче, сама оказалась не лучше (см. [secondAttempt]).
     * @property secondRepeats второй ответ — повтор той же мерой, что у прибора эха.
     *   При повторе он всё равно остаётся в ленте: честная строка была бы
     *   текстом-заглушкой, а их модель копирует; пометка нужна, чтобы частота
     *   тупиков была видна на экране.
     * @property secondDamage порча во втором ответе (пусто — нет или детектор не
     *   работает).
     * @property secondMirrors второй ответ — зеркало реплики собеседника
     *   ([EchoCheck.mirrorOf]).
     * @property third третья ступень — три варианта; null — не понадобилась
     *   (вторая чиста) или вторая не состоялась.
     * @property secondGlanced вторая попытка позвала приборы, хотя первый проход
     *   на них не глядел: ей показана доска, и [secondAnswer], [secondMs],
     *   [secondTokens] — уже прохода после доски (время — обоих).
     */
    data class Intercept(
        val cause: Cause,
        val secondAnswer: String,
        val secondEnd: GenerationEnd,
        val secondMs: Long,
        val secondTokens: Int?,
        val replaced: Boolean,
        val secondRepeats: Boolean,
        val secondDamage: List<String>,
        val third: Third? = null,
        val secondGlanced: Boolean = false,
        val secondMirrors: Boolean = false,
    )

    /**
     * Третья ступень: три варианта в одном ответе с включённым DRY, отбор кодом.
     *
     * @property variants варианты по порядку и почему каждый отброшен; null у
     *   причины — вариант годен (взят первый годный).
     * @property chosen взятый вариант; null — годного не нашлось (см. [hidden]).
     * @property hidden годного нет, выдача дошла до конца — ход отдан
     *   [Ribbon.hide]. false при [chosen] = null — выдача оборвана или сбилась,
     *   в ленте то, что было бы без третьей ступени.
     * @property end чем кончилась выдача вариантов.
     * @property ms сколько она шла.
     * @property tokens сколько токенов выдано — по отчёту движка; null — отчёта нет.
     * @property dryFed сколько токенов DRY увидел до выдачи (прошлые ответы, а
     *   при зеркале и реплику собеседника); 0 — текст не дошёл до движка, и DRY
     *   ленты не видел.
     */
    data class Third(
        val variants: List<Pair<String, String?>>,
        val chosen: String?,
        val end: GenerationEnd,
        val ms: Long,
        val tokens: Int?,
        val dryFed: Int,
        val hidden: Boolean = false,
    )

    /**
     * Итог хода.
     *
     * @property answer первый ответ хода как выдан (при взгляде — проход после доски).
     * @property tokensSeen пачек текста первой попытки; 0 — модель не выдала ни знака.
     * @property firstTokenAtMs первый знак первой попытки, мс от начала хода; null — не было.
     * @property metrics отчёт движка о первой попытке; null — не пришёл.
     * @property generationEnd чем кончилась первая попытка. Обрезанная нами на
     *   вызове приборов считается кончившейся сама: модель договорила то, что
     *   хотела сказать до вызова.
     * @property failure сбой посреди первой попытки; зовущий бросает его после своих дел.
     * @property intercept перехват; null — не было. Числа выше — первой попытки.
     * @property hidden ход отдан [Ribbon.hide] (годного нет или петля до потолка).
     * @property kept ответ, отданный [Ribbon.close], дословно; null — не отдан.
     * @property glanced агент глянул на приборы — первым проходом или во второй попытке.
     * @property glanceRetried после доски ответа не было, и проход со взглядом повторён.
     * @property loop петля до потолка ([loopAtCeiling]); null — не было.
     * @property haltedBy лестница остановлена перед очередным проходом — чем; null — нет.
     */
    data class Result(
        val answer: String,
        val tokensSeen: Int,
        val firstTokenAtMs: Long?,
        val metrics: DecodingMetrics?,
        val generationEnd: GenerationEnd,
        val failure: Throwable?,
        val intercept: Intercept?,
        val hidden: Boolean,
        val kept: String?,
        val glanced: Boolean,
        val glanceRetried: Boolean,
        val loop: EchoCheck.Result?,
        val haltedBy: String?,
    )

    /**
     * Провести ход по ленте [ribbon]. [question] — реплика собеседника этого
     * хода (мера зеркала и повтора); [desk] — доска приборов (null — приборов
     * нет, взгляд покажет «не видны»); [halt] — проверка перед каждым следующим
     * проходом, null — можно.
     *
     * @param onEvent каждое событие выдачи, как есть (экрану — показывать по мере).
     * @param afterSend реплика ушла в движок и выдача кончилась — до закрытия
     *   хода. Зовётся и на нуле токенов, один раз — после первой попытки.
     * @param onFirstMetrics отчёт движка о первой попытке, до закрытия хода
     *   (экрану — счёт токенов ленты: закрытие уводит ход на диск с этим числом).
     * @param onRetry первый ответ пойман, сейчас начнётся вторая попытка; её
     *   события пойдут в [onEvent] так же. Экрану — убрать показанный первый ответ.
     * @param onThird вторая тоже не годна, сейчас три варианта. Их текст в
     *   [onEvent] не идёт; экрану — убрать показанную вторую попытку.
     * @param onGlance агент позвал приборы, сейчас доска.
     */
    suspend fun run(
        ribbon: Ribbon,
        question: String,
        desk: (suspend () -> String)?,
        halt: () -> String? = { null },
        onEvent: (GenerationEvent) -> Unit = {},
        afterSend: suspend () -> Unit = {},
        onFirstMetrics: (DecodingMetrics) -> Unit = {},
        onRetry: (Cause) -> Unit = {},
        onThird: () -> Unit = {},
        onGlance: () -> Unit = {},
    ): Result {
        val startMs = System.currentTimeMillis()
        val messages = ribbon.messages(emptySet())
        val stable = ribbon.stableMessages(emptySet())
        var firstTokenAtMs: Long? = null
        var metrics: DecodingMetrics? = null
        // Пачки текста считаем сами, а не спрашиваем движок: метрики он
        // присылает не всегда, а факт «не появилось ни знака» надо назвать при
        // любом исходе.
        var tokensSeen = 0
        // Ответ копится ОТДЕЛЬНО от того, что видно на экране. Правило
        // дословности: в ленту должно лечь ровно то, что выдал движок. Собирать
        // текст обратно с экрана нельзя — там он смешан с прошлыми ходами и
        // подписями, и любое расхождение на один знак оборвало бы совпадение с
        // обсчитанным началом запроса на следующем ходе.
        val answer = StringBuilder()
        var failure: Throwable? = null
        var generationEnd: GenerationEnd? = null
        var cause: Cause? = null
        var loop: EchoCheck.Result? = null
        var haltedBy: String? = null
        // Слова запроса для детектора порчи: всё, что модель видела, вместе со
        // стеной. Слово ответа, стоящее там в той же форме (имя, термин из
        // записи, число с доски), порчей не считается. Собирается, только если
        // понадобится; доска читается после первого прохода.
        var glanceTail: List<Pair<String, String>> = emptyList()
        val request by lazy {
            WordDamage.requestWords((messages + glanceTail).joinToString("\n") { it.second } + "\n" + (engine.wallText ?: ""))
        }

        var kept: String? = null
        var hidden = false
        // Вызов приборов в ленту не ложится никогда: каждый проход режет его
        // сам, а здесь страховка на путь, которого не предусмотрели. Лёг бы —
        // модель видела бы в ленте свой неразобранный вызов как ответ.
        val close: (String, String?) -> Unit = { text, rejected ->
            val content = text.substringBefore(Glance.CALL_OPEN).trimEnd()
            ribbon.close(content, rejected)
            kept = content
        }
        val hide: (String, String, List<ConversationJournal.Rejected>) -> Unit = { reason, first, rejected ->
            ribbon.hide(reason, first, rejected)
            hidden = true
        }
        fun stopped(): Boolean {
            if (haltedBy == null) haltedBy = halt()
            return haltedBy != null
        }

        suspend fun pass(msgs: List<Pair<String, String>>, first: Boolean = false): String? =
            passCatchingCall(msgs, answer, onEvent, stable, dryAgainst = null, first = first, onPiece = {
                if (firstTokenAtMs == null) firstTokenAtMs = System.currentTimeMillis() - startMs
                tokensSeen++
            }) { metrics = it }

        var glanced = false
        var glanceRetried = false
        var cutByCall = false
        try {
            val before = pass(messages, first = true)
            if (before != null) {
                glanced = true
                if (stopped()) {
                    // Доски не будет: ход ложится сказанным до вызова.
                    cutByCall = true
                    cutAtCall(answer, before)
                } else {
                    onGlance()
                    glanceTail = listOf(
                        ConversationJournal.ROLE_ASSISTANT to Glance.callMessage(before),
                        ConversationJournal.ROLE_USER to Glance.response(desk?.invoke().orEmpty()),
                    )
                    answer.clear()
                    tokensSeen = 0
                    firstTokenAtMs = null
                    metrics = null
                    var again = pass(messages + glanceTail)
                    if (again != null) {
                        cutByCall = true
                        if (cutAtCall(answer, again)) tokensSeen = 0
                    }
                    if (answer.isBlank() && (again != null || engine.lastEnd == GenerationEnd.COMPLETED) && !stopped()) {
                        glanceRetried = true
                        answer.clear()
                        tokensSeen = 0
                        firstTokenAtMs = null
                        metrics = null
                        cutByCall = false
                        again = pass(messages + glanceTail)
                        if (again != null) {
                            cutByCall = true
                            if (cutAtCall(answer, again)) tokensSeen = 0
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e
        } finally {
            // Причина читается ПЕРВОЙ: движок хранит её до начала следующего
            // прогона, и всё, что ниже, вправе на неё опираться.
            generationEnd = if (cutByCall && failure == null) GenerationEnd.COMPLETED else engine.lastEnd
            afterSend()
            // Ноль пачек — отказ движка, а не реплика: ход не ложится.
            if (tokensSeen > 0) {
                metrics?.let(onFirstMetrics)
                // Перехват — только у ответа, выданного до конца: оборванный
                // (сторож, отмена, сбой) показывает не то, что модель хотела
                // сказать, и второй прогон после обрыва сторожем — тот самый
                // нагрев, от которого обрывали.
                val finished = failure == null && generationEnd == GenerationEnd.COMPLETED
                // Петля до потолка проверяется раньше перехвата: такой ответ
                // скрывается, а не переспрашивается (см. [loopAtCeiling]).
                loop = if (finished) loopAtCeiling(answer.toString(), metrics?.tokensPredicted ?: tokensSeen, answerLimit) else null
                cause = if (finished && loop == null) causeOf(answer.toString(), question, ribbon.history(), ribbon.ownSpeech()) { request } else null
                val looped = loop
                when {
                    looped != null -> hide(
                        "петля «${looped.loop}» до потолка $answerLimit токенов",
                        answer.toString(),
                        listOf(ConversationJournal.Rejected("первый", answer.toString())),
                    )
                    cause != null && stopped() -> close(answer.toString(), null)
                    cause == null -> close(answer.toString(), null)
                }
            }
        }

        // Сюда ход доходит, только если первую попытку не отменили (отмена
        // брошена выше, и ход уже закрыт первым ответом).
        val intercept = cause?.takeIf { haltedBy == null }?.let { c ->
            secondAttempt(c, ribbon, question, answer.toString(), { request }, glanceTail, desk, halt = ::stopped, onGlance, onRetry, onThird, onEvent, close, hide)
        }
        return Result(
            answer = answer.toString(),
            tokensSeen = tokensSeen,
            firstTokenAtMs = firstTokenAtMs,
            metrics = metrics,
            generationEnd = generationEnd!!,
            failure = failure,
            intercept = intercept,
            hidden = hidden,
            kept = kept,
            glanced = glanced || intercept?.secondGlanced == true,
            glanceRetried = glanceRetried,
            loop = loop,
            haltedBy = haltedBy,
        )
    }

    /**
     * Почему перехватывать готовый ответ, или null — не надо. Повтор проверяется
     * первым: при повторе ответ уходит целиком, и его порча уже не важна. Зеркало
     * — вторым, по той же причине. Детектор не загружен ([WordDamageHolder]) —
     * порча не проверяется вовсе, и об этом говорит его строка на экране.
     */
    private fun causeOf(
        answer: String,
        question: String,
        history: List<ConversationJournal.Turn>,
        ownSpeech: List<String>,
        request: () -> Set<String>,
    ): Cause? {
        EchoIntercept.decide(answer, question, history, ownSpeech)?.let { return Cause.Repeat(it.sentence, it.without, it.withoutRecall) }
        EchoCheck.mirrorToIntercept(answer, question)?.let { return Cause.Mirror(it) }
        val found = damage()?.check(answer, request()).orEmpty()
        return if (found.isEmpty()) null else Cause.Damage(found)
    }

    /**
     * Один проход модели с ловлей вызова приборов ([Glance]). Им идут первый
     * проход хода и вторая попытка перехвата; третья ступень вызов не ловит, а
     * бракует вариант с ним (см. [thirdAttempt]).
     *
     * Вызов обрывает выдачу на своём начале. Пока выданное может оказаться
     * началом вызова, [onEvent] ничего не отдаётся — иначе на экране мелькнуло
     * бы «<tool_call>». Знаки дописываются в [text]; [onPiece] зовётся на каждую
     * пачку до дописывания, [onMetrics] — на отчёт движка.
     *
     * Возвращает текст до вызова; null — вызова не было. [text] при вызове
     * содержит и сам его начальный знак — обрезать его дело вызывающего.
     */
    private suspend fun passCatchingCall(
        messages: List<Pair<String, String>>,
        text: StringBuilder,
        onEvent: (GenerationEvent) -> Unit,
        stableMessages: Int?,
        dryAgainst: String?,
        first: Boolean = false,
        onPiece: () -> Unit,
        onMetrics: (DecodingMetrics) -> Unit,
    ): String? {
        val held = mutableListOf<GenerationEvent>()
        var holding = true
        // Знак отдаётся сборщику ниже до проверки: тот, что открыл вызов,
        // уже лежит в [text], и выдача обрывается на нём же.
        engine.generate(messages, dryAgainst, stableMessages, first).transformWhile { event ->
            emit(event)
            text.indexOf(Glance.CALL_OPEN) < 0
        }.collect { event ->
            when (event) {
                is GenerationEvent.Token -> {
                    onPiece()
                    text.append(event.text)
                }
                is GenerationEvent.Metrics -> onMetrics(event.metrics)
                // Done намеренно НЕ разбирается: библиотека не обещает, что
                // метрики придут до него, поэтому итог собирается после
                // выхода из collect.
                else -> Unit
            }
            // Держатся только знаки: ход обсчёта, метрики, ошибки нужны сразу,
            // а вызова в них нет.
            if (holding && event is GenerationEvent.Token) {
                held += event
                val start = text.trimStart().toString()
                if (start.isEmpty() || Glance.CALL_OPEN.startsWith(start) || start.contains(Glance.CALL_OPEN)) return@collect
                holding = false
                held.forEach(onEvent)
                held.clear()
            } else {
                onEvent(event)
            }
        }
        val cut = text.indexOf(Glance.CALL_OPEN)
        if (cut < 0) {
            // Выдача кончилась, пока держали (ответ — пробелы или обрывок
            // «<tool»): отдать удержанное, чтобы не потерять знаков.
            held.forEach(onEvent)
            return null
        }
        return text.substring(0, cut)
    }

    /**
     * Оставить в [text] только сказанное до вызова ([before]). true — не
     * осталось ничего: проход был одним вызовом, ответа в нём нет.
     */
    private fun cutAtCall(text: StringBuilder, before: String): Boolean {
        text.setLength(0)
        text.append(before.trimEnd())
        return text.isEmpty()
    }

    /**
     * Вторая попытка после перехвата. При повторе — запрос без ходов-образцов
     * ([Ribbon.messages], `without`); при порче — тот же запрос, то есть
     * повторная выборка; при зеркале — тот же запрос со штрафом DRY против
     * реплики собеседника ([Cause.Mirror]). Закрывает ход сама — при любом
     * исходе, в том числе при отмене.
     *
     * ЧТО ЛОЖИТСЯ В ЛЕНТУ. Второй ответ выдан до конца — при ПОВТОРЕ и ЗЕРКАЛЕ он,
     * а первый отброшенным. При ПОРЧЕ второй берётся, только если он чист: без
     * порчи, не повтор и не зеркало. Иначе остаётся первый — повторная выборка,
     * давшая повтор вместо порчи, хуже: копия размножается по ленте, а порча —
     * нет. Вторая попытка оборвана, сбилась или пуста — первый ответ как есть:
     * оборванный второй ответом не является.
     *
     * ВТОРАЯ ВЫДАНА ДО КОНЦА, НО НЕ ГОДНА (повтор, зеркало или порча) — ход закрывает
     * третья ступень ([thirdAttempt]). Пересчёта ленты она не добавляет: её запрос
     * — запрос второй попытки плюс одна просьба в конце.
     *
     * ВЗГЛЯД НА ПРИБОРЫ. Вторая попытка ловит вызов приборов так же, как
     * первый проход: повтор числа из прошлого ответа — ровно тот случай, когда
     * модель во второй раз решает посмотреть. Первый проход не глядел — доска
     * показывается, и ответ второй попытки — проход после доски; глядел —
     * второй вызов подряд режется.
     *
     * ЧЕГО НЕ ДЕЛАЕТ:
     *  - сбой второй попытки наружу не бросается: ход состоялся первым ответом,
     *    сбой назван в [Intercept.secondEnd]. Оборванная или сбившаяся вторая
     *    третьей не зовёт: обрыв сторожем — нагрев, и ещё один прогон был бы
     *    тем самым нагревом.
     */
    private suspend fun secondAttempt(
        cause: Cause,
        ribbon: Ribbon,
        question: String,
        first: String,
        request: () -> Set<String>,
        glanceTail: List<Pair<String, String>>,
        desk: (suspend () -> String)?,
        halt: () -> Boolean,
        onGlance: () -> Unit,
        onRetry: (Cause) -> Unit,
        onThird: () -> Unit,
        onEvent: (GenerationEvent) -> Unit,
        close: (String, String?) -> Unit,
        hide: (String, String, List<ConversationJournal.Rejected>) -> Unit,
    ): Intercept {
        // История снимается ДО закрытия хода: мера второго ответа — против тех
        // же трёх прошлых ответов, что и у первого.
        val history = ribbon.history()
        val without = (cause as? Cause.Repeat)?.without ?: emptySet()
        val withoutRecall = (cause as? Cause.Repeat)?.withoutRecall ?: emptySet()
        val ownSpeech = ribbon.ownSpeech()
        val dry = if (cause is Cause.Mirror) question else null
        // Доска взгляда — и сюда: повторная попытка отвечает на тот же вопрос
        // о приборах и без доски назвала бы числа из головы.
        val base = ribbon.messages(without, withoutRecall)
        val stable = ribbon.stableMessages(without)
        // Хвост взгляда этой попытки: доска первого прохода или своя, если
        // вторая попытка позвала приборы сама. Его видит и третья ступень.
        var tail = glanceTail
        var glancedHere = false
        onRetry(cause)
        val startedAt = System.currentTimeMillis()
        val second = StringBuilder()
        // Пачки текста, а не токены (см. Intercept.secondTokens): годятся только
        // на «выдано ли хоть что-то».
        var pieces = 0
        var metrics: DecodingMetrics? = null
        var failed = false
        var end = GenerationEnd.UNEXPLAINED
        var replaced = false
        var repeats = false
        var mirrors = false
        var found = emptyList<String>()
        var needThird = false
        // Слова доски — тоже запрос: своя доска попытки дописывается к ним,
        // иначе её числа и названия сошли бы за порчу.
        var requestHere = request
        // Проход оборван на вызове кодом, а не движком: модель договорила то,
        // что хотела сказать до вызова. Движок при таком обрыве называет конец
        // отменой — для хода это законченная выдача.
        var cutByCall = false
        try {
            val before = passCatchingCall(base + tail, second, onEvent, stable, dryAgainst = dry, onPiece = { pieces++ }) { metrics = it }
            if (before != null && tail.isEmpty() && !halt()) {
                glancedHere = true
                onGlance()
                tail = listOf(
                    ConversationJournal.ROLE_ASSISTANT to Glance.callMessage(before),
                    ConversationJournal.ROLE_USER to Glance.response(desk?.invoke().orEmpty()),
                )
                val deskWords = WordDamage.requestWords(tail.joinToString("\n") { it.second })
                requestHere = { request() + deskWords }
                second.clear()
                pieces = 0
                metrics = null
                val again = passCatchingCall(base + tail, second, onEvent, stable, dryAgainst = dry, onPiece = { pieces++ }) { metrics = it }
                if (again != null) {
                    cutByCall = true
                    if (cutAtCall(second, again)) pieces = 0
                }
            } else if (before != null) {
                // Доску этот ход уже видел (или лестница остановлена): вызов режется.
                cutByCall = true
                if (cutAtCall(second, before)) pieces = 0
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = true
            warn("вторая попытка сорвалась", e)
        } finally {
            end = if (cutByCall && !failed) GenerationEnd.COMPLETED else engine.lastEnd
            val done = pieces > 0 && !failed && end == GenerationEnd.COMPLETED
            val dmg = damage()
            if (done) {
                repeats = EchoIntercept.decide(second.toString(), question, history, ownSpeech) != null
                mirrors = EchoCheck.mirrorToIntercept(second.toString(), question) != null
                found = dmg?.check(second.toString(), requestHere()).orEmpty()
            }
            needThird = done && (repeats || mirrors || found.isNotEmpty())
            replaced = when (cause) {
                is Cause.Repeat, is Cause.Mirror -> done
                is Cause.Damage -> done && !repeats && !mirrors && found.isEmpty()
            }
            // Остановленная лестница третьей ступени не зовёт: ход ложится тем, что есть.
            if (needThird && halt()) needThird = false
            // Третья ступень закроет ход сама — при любом своём исходе.
            if (!needThird) {
                if (replaced) close(second.toString(), first) else close(first, null)
            }
        }
        val secondMs = System.currentTimeMillis() - startedAt
        // Без третьей ступени в ленте лежало бы: при повторе — вторая (с пометкой),
        // при порче — первая. Это же остаётся, если третья оборвётся или собьётся.
        val fallback = if (replaced) second.toString() to first else first to null
        val third = if (needThird) {
            thirdAttempt(cause, ribbon, question, history, requestHere, tail, fallback, first, second.toString(), onThird, close, hide)
        } else {
            null
        }
        return Intercept(
            cause = cause,
            secondAnswer = second.toString(),
            secondEnd = end,
            secondMs = secondMs,
            secondTokens = metrics?.tokensPredicted,
            // Отданный [Ribbon.hide] ход не лёг вовсе — ни второй ответ, ни первый.
            replaced = third?.hidden != true && (replaced || third?.chosen != null),
            secondRepeats = repeats,
            secondDamage = found,
            third = third,
            secondGlanced = glancedHere,
            secondMirrors = mirrors,
        )
    }

    /**
     * Третья ступень: модель даёт три коротких варианта ответа в одном ответе, с
     * включённым штрафом DRY, а код берёт первый годный. Закрывает ход сама — при
     * любом исходе, в том числе при отмене.
     *
     * ЗАПРОС — тот же, что у второй попытки (при повторе — без ходов-образцов, при
     * порче — полный), плюс просьба системным сообщением в конце. Пересчитывается
     * только просьба: всё до неё движок уже обсчитал для второй попытки.
     *
     * DRY ВИДИТ — при повторе ответы выкинутых ходов-образцов (источник копии),
     * при порче — три последних ответа ленты, при зеркале — их же и реплику
     * собеседника этого хода. Без поданного текста он ленты не видит вообще (см.
     * LlmEngine.DRY_PARAMS_JSON); сколько дошло — [Third.dryFed].
     *
     * ГОДНЫЙ ВАРИАНТ — не вызов приборов, не строка-вступление (кончается двоеточием: «Конечно, вот три
     * варианта:»), не повтор себя и не зеркало той же мерой, что у перехвата, и без порчи слов.
     * Разбор ответа на варианты — у ночного зеркала ([com.uroboros.memory.dream.Mirror.parse]).
     *
     * ИСХОД — взятый вариант ложится ответом, первый ответ хода — отброшенным.
     * Выдача дошла до конца, а годного нет — [Ribbon.hide]: что это значит, решает
     * канал. Выдача оборвана или сбилась — то, что лежало бы без третьей ступени
     * ([fallback]): обрыв сторожем — нагрев, и ответа нет не потому, что сказать
     * нечего.
     *
     * ЧЕГО НЕ УМЕЕТ:
     *  - непонимание вопроса («твои воспоминания» вместо «мои о тебе») не видит:
     *    это не повтор и не порча;
     *  - на ходе, где модели нечего сказать, годный вариант находится примерно в
     *    половине случаев (стенды на модели 3B); остальное — [Ribbon.hide];
     *  - вторая попытка и отброшенные варианты отдаются только [Ribbon.hide];
     *    при взятом варианте отброшенным остаётся только первый ответ хода.
     */
    private suspend fun thirdAttempt(
        cause: Cause,
        ribbon: Ribbon,
        question: String,
        history: List<ConversationJournal.Turn>,
        request: () -> Set<String>,
        glanceTail: List<Pair<String, String>>,
        fallback: Pair<String, String?>,
        first: String,
        second: String,
        onThird: () -> Unit,
        close: (String, String?) -> Unit,
        hide: (String, String, List<ConversationJournal.Rejected>) -> Unit,
    ): Third {
        val without = (cause as? Cause.Repeat)?.without ?: emptySet()
        val withoutRecall = (cause as? Cause.Repeat)?.withoutRecall ?: emptySet()
        val ownSpeech = ribbon.ownSpeech()
        val sources = if (without.isNotEmpty()) {
            without.sorted().map { history[it].agentContent }
        } else {
            history.takeLast(EchoCheck.EARLIER_ANSWERS).map { it.agentContent }
        } + withoutRecall.map { EchoIntercept.quoteOf(it) } +
            (if (cause is Cause.Mirror) listOf(question) else emptyList())
        val startedAt = System.currentTimeMillis()
        val text = StringBuilder()
        var metrics: DecodingMetrics? = null
        var failed = false
        var end = GenerationEnd.UNEXPLAINED
        val variants = mutableListOf<Pair<String, String?>>()
        var chosen: String? = null
        var dryFed = 0
        var hidden = false
        try {
            onThird()
            val messages = ribbon.messages(without, withoutRecall) + glanceTail + (ConversationJournal.ROLE_SYSTEM to THREE_VARIANTS_ASK)
            // Как у второй попытки: с выкинутыми ходами снимок не снимается.
            engine.generate(messages, sources.joinToString("\n"), ribbon.stableMessages(without), first = false).collect { event ->
                when (event) {
                    is GenerationEvent.Token -> text.append(event.text)
                    is GenerationEvent.Metrics -> metrics = event.metrics
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = true
            warn("третья попытка сорвалась", e)
        } finally {
            end = engine.lastEnd
            dryFed = engine.lastDryFed
            val completed = !failed && end == GenerationEnd.COMPLETED
            if (completed) {
                // Вызов сжимается в одну строку до разбора: разбор делит ответ
                // по строкам, и трёхстрочный вызов занял бы места настоящих
                // вариантов. Сказанное перед вызовом на той же строке («Гляну
                // на приборы.») остаётся с ним и бракуется вместе: обещание
                // глянуть, которое не исполнится, — не ответ.
                val spoken = CALL_BLOCK.replace(text.toString(), "${Glance.CALL_OPEN}\n")
                val dmg = damage()
                for (raw in com.uroboros.memory.dream.Mirror.parse(spoken)) {
                    val v = raw.replaceFirst(AGENT_PREFIX, "").trim()
                    if (v.isEmpty()) continue
                    val why = when {
                        // Доску третьей ступени не показывают: вызов здесь —
                        // не ответ, а просьба, которую уже не исполнить.
                        v.contains("tool_call") || v.contains(Glance.TOOL_NAME) -> "вызов приборов"
                        v.endsWith(":") -> "вступление"
                        EchoIntercept.decide(v, question, history, ownSpeech) != null -> "повтор"
                        EchoCheck.mirrorToIntercept(v, question) != null -> "зеркало"
                        else -> dmg?.check(v, request())?.takeIf { it.isNotEmpty() }
                            ?.let { "порча «${it.joinToString(", ")}»" }
                    }
                    variants += v to why
                    if (why == null && chosen == null) chosen = v
                }
            }
            val pick = chosen
            when {
                pick != null -> close(pick, first)
                completed -> {
                    hidden = true
                    hide(reasonOf(cause), first, rejectedOf(first, second, variants, text.toString()))
                }
                else -> close(fallback.first, fallback.second)
            }
        }
        return Third(
            variants = variants,
            chosen = chosen,
            end = end,
            ms = System.currentTimeMillis() - startedAt,
            tokens = metrics?.tokensPredicted,
            dryFed = dryFed,
            hidden = hidden,
        )
    }

    /** Причина [Ribbon.hide] словами. */
    private fun reasonOf(cause: Cause): String = when (cause) {
        is Cause.Repeat -> "повтор «${cause.sentence}»"
        is Cause.Damage -> "порча слов «${cause.words.joinToString(", ")}»"
        is Cause.Mirror -> "зеркало «${cause.sentence}»"
    }

    /**
     * Все отброшенные тексты хода по порядку — для [Ribbon.hide]. Ответ третьей
     * ступени, не разобранный ни на один вариант, идёт целиком: иначе от неё не
     * осталось бы ничего.
     */
    private fun rejectedOf(
        first: String,
        second: String,
        variants: List<Pair<String, String?>>,
        thirdRaw: String,
    ): List<ConversationJournal.Rejected> = buildList {
        add(ConversationJournal.Rejected("первый", first))
        add(ConversationJournal.Rejected("вторая попытка", second))
        if (variants.isEmpty()) {
            add(ConversationJournal.Rejected("три варианта, не разобраны", thirdRaw))
        } else {
            variants.forEachIndexed { i, (v, why) ->
                add(ConversationJournal.Rejected("вариант ${i + 1} (${why ?: "годен"})", v))
            }
        }
    }

    companion object {
        /**
         * Просьба третьей ступени — системным сообщением в конце запроса. Слова те,
         * что мерили стенды; меняя их, меняешь то, что мерили.
         */
        const val THREE_VARIANTS_ASK =
            "Дай три разных коротких варианта ответа на последнюю реплику собеседника, " +
                "каждый с новой строки, без нумерации и пояснений."

        /**
         * Вызов приборов в ответе третьей ступени — от начала до закрытия или,
         * если не закрыт, до конца ответа.
         */
        private val CALL_BLOCK = Regex("<tool_call>.*?(</tool_call>|$)", RegexOption.DOT_MATCHES_ALL)

        /** Подпись «Агент:», которой модель иногда начинает вариант. */
        private val AGENT_PREFIX = Regex("^\\s*Агент\\s*:\\s*")

        /**
         * Петля до потолка: ответ дошёл до [limit] токенов, и внутри него
         * предложение повторяет более раннее (часть «петля в ответе» прибора
         * эха, [EchoCheck.LOOP_SHARE]). Итог проверки с петлёй или null — ход
         * ложится как обычно.
         *
         * ЗАЧЕМ. Такой ответ не кончился сам — его оборвал наш предел посреди
         * круга. Положенный в ленту, он стал бы образцом для следующих ходов
         * (малая модель копирует свои прошлые ответы), а из архива ленты
         * вернулся бы модели своей речью агента (nav.OwnSpeech). Поэтому ход
         * отдаётся [Ribbon.hide].
         *
         * ПОЧЕМУ НЕ ВТОРАЯ ПОПЫТКА, как у повтора прошлого ответа. Она шла бы
         * тем же путём до того же потолка: ещё полторы-две минуты работы
         * процессора и нагрев за ответ, который скорее всего тоже уйдёт в круг.
         *
         * ПОЧЕМУ ДВА УСЛОВИЯ, А НЕ ОДНО. Петля без потолка бывает законной —
         * цитата, названная в ответе дважды, — и такой ответ кончился сам.
         * Потолок без петли — просто длинный ответ, оборванный на полуслове.
         *
         * ЧЕГО НЕ УМЕЕТ: петлю другими словами не видит (мера — основы слов,
         * см. [EchoCheck]); ответ второй попытки перехвата не проверяет —
         * только первый проход хода.
         *
         * @param predicted сколько токенов выдано: по отчёту движка, а без
         * него — по своему счёту.
         */
        fun loopAtCeiling(answer: String, predicted: Int, limit: Int): EchoCheck.Result? {
            if (predicted < limit) return null
            val result = EchoCheck.check(answer.substringBefore(Glance.CALL_OPEN), "", emptyList())
            return if (result.loop == null) null else result
        }
    }
}
