package com.uroboros.llm

import android.util.Log
import com.dark.gguf_lib.models.DecodingMetrics
import com.dark.gguf_lib.models.GenerationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ход разговора и лента — один раз на процесс, а не на экран.
 *
 * ЗАЧЕМ ВЫНЕСЕНО ИЗ ЭКРАНА. Ход разговора умеет делать не только экран: служба
 * тоже может сказать реплику в ленту, в том числе когда экран закрыт. Сборка
 * хода в двух местах разошлась бы молча — одна проверка края поменялась бы, а
 * вторая нет. Поэтому путь хода один, и он здесь.
 *
 * ЧТО ЗДЕСЬ:
 *  - лента ([journal]) и её единственное хранилище ([store]). Хранилище одно на
 *    процесс не для порядка: у него своя очередь обращений, и два хранилища
 *    были бы двумя очередями, то есть никакой;
 *  - запись каждого закрытого хода на диск — здесь, а не у экрана: иначе ход,
 *    закрытый без экрана, на диск бы не попал;
 *  - чтение сохранённой ленты с диска и подъём её в память вместе с точкой
 *    движка ([loadSaved], [raise], [resumeSaved]). Решает, поднимать ли,
 *    по-прежнему вызывающий;
 *  - сам ход ([run]): обе проверки края, возврат движка после чужой работы,
 *    счёт токенов и закрытие хода в ленте. Прогон с перехватом и взглядом на
 *    приборы — общая лестница ответа ([AnswerLadder]), та же, что у чатов
 *    телеграма; здесь — что значит она для ленты экрана.
 *
 * ЧЕГО ЗДЕСЬ НЕТ, и это граница. Что положить в реплику (записи, сны, сверка,
 * состояние), автозапись в память, подхват снов, приборы и диалоги — дело
 * того, кто зовёт. Ход не знает, чья это реплика и что с ней делать дальше.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - закрытие ленты (архив) по-прежнему пишет экран через [store]: лента
 *    закрывается только по решению человека на экране;
 *  - замок ([run]) держит только сам ход. Сборка реплики вызывающим идёт до
 *    замка, поэтому то, что она прочла из ленты, к моменту хода может
 *    устареть, если между ними успел пройти чужой ход.
 */
class ConversationTurns(
    private val engine: LlmEngine,
    val store: JournalStore,
    scope: CoroutineScope,
) {

    /** Лента разговора; живёт процесс (см. [ConversationJournal.shared]). */
    val journal: ConversationJournal = ConversationJournal.shared

    /**
     * Последний разговор: открытая лента, если в ней есть ходы, иначе
     * последний разговор архива ленты ([archiveIndex] — его номер). Пустой
     * [turns] — разговоров нет нигде.
     */
    data class LastTalk(val turns: List<ConversationJournal.Turn>, val archiveIndex: Int?)

    /**
     * Последний разговор (см. [LastTalk]); null — открытая лента пуста, а архив
     * не прочитался. Открытая лента читается до обращения к диску: ход ложится
     * в неё на главном потоке, и зовущий оттуда получает её без замка хода.
     *
     * ЧЕГО НЕ УМЕЕТ: после комы открытая лента в памяти пуста, пока её не
     * подняли с диска; до этого последним разговором считается архивный.
     */
    suspend fun lastTalk(): LastTalk? {
        val live = journal.history()
        if (live.isNotEmpty()) return LastTalk(live, null)
        val archive = store.readArchive() ?: return null
        val lastIndex = archive.maxOfOrNull { it.archiveIndex } ?: return LastTalk(emptyList(), null)
        val turns = archive.filter { it.archiveIndex == lastIndex }
            .sortedBy { it.turnIndex }
            .map { ConversationJournal.Turn(it.userContent, it.agentContent, it.question, at = it.at) }
        return LastTalk(turns, lastIndex)
    }

    /** Исход записи хода на диск. [ok] = false — ход остался только в памяти. */
    data class DiskWrite(val index: Int, val ok: Boolean)

    private val diskWrites = MutableSharedFlow<DiskWrite>(extraBufferCapacity = 64)

    /**
     * Записи ходов на диск. Событие, а не состояние: экрана может не быть, и
     * тогда событие никому не нужно — строку на диске экран перечитает сам при
     * открытии.
     */
    val diskEvents: SharedFlow<DiskWrite> = diskWrites.asSharedFlow()

    private val closes = MutableSharedFlow<Int>(extraBufferCapacity = 64)

    /**
     * Ходы, легшие в ленту: номер хода. Событие уходит ПОСЛЕ того, как замок
     * хода отпущен, — в отличие от [diskEvents]: запись на диск идёт отдельной
     * корутиной, запущенной изнутри хода, и может кончиться раньше, чем ход
     * отпустит замок. Кто ждёт конца хода, чтобы перерисовать ленту (экран —
     * ход тела агента), ждёт этого события, а не записи на диск.
     */
    val closedEvents: SharedFlow<Int> = closes.asSharedFlow()

    private val lock = Mutex()

    /**
     * Строка шва для «Подробно»: стена сменилась на лету перед таким-то ходом
     * и что в ней добавилось или убралось (см. [BuildSelfDescription.changeLine]),
     * либо почему ожидающая стена не встала. Номер хода знает лента, поэтому
     * строка собирается здесь, а не в движке. null — смены в этом процессе не
     * было. Живёт в памяти процесса: после перезапуска её нет.
     */
    @Volatile
    var wallChangeLine: String? = null
        private set

    private val _busy = MutableStateFlow(false)

    /**
     * Замок хода занят: идёт ход или подъём ленты — чей угодно, экрана или
     * службы. По нему экран гасит то, что нельзя делать посреди хода.
     */
    val busy: StateFlow<Boolean> = _busy

    /** Под замком хода, с отметкой [busy]. */
    private suspend inline fun <T> locked(block: () -> T): T = lock.withLock {
        _busy.value = true
        try {
            block()
        } finally {
            _busy.value = false
        }
    }

    /**
     * Чужой ход под тем же замком: ход чата телеграма (gate.link.ChatLink). Ленты
     * экрана он не трогает — у чата своя лента, — но движок один, и два хода
     * сразу перемешали бы состояние движка. Под этим замком ход экрана и ход
     * первым ждут, пока ход чата не кончится, и наоборот; [busy] видят ночные
     * проверки службы.
     *
     * Возврат движка к разговору экрана — не здесь: его делает следующий ход
     * экрана (см. LlmEngine.conversationDisplaced).
     */
    suspend fun <T> aside(block: suspend () -> T): T = lock.withLock {
        _busy.value = true
        try {
            block()
        } finally {
            _busy.value = false
        }
    }

    init {
        // Запись уходит в отдельную корутину: хранилище ходит в базу, а держать
        // на ней закрытие хода незачем. Плата названа прямо: если приложение
        // убьют ровно между закрытием хода и записью, ход останется только в
        // памяти. Следующий запуск увидит дыру в нумерации и откажет в подъёме
        // — то есть промах будет назван, а не проглочен.
        journal.onTurnAppended = { index, turn ->
            // Оба числа снимаются ЗДЕСЬ, а не внутри корутины: пока запись
            // идёт на диск, мог бы пройти следующий ход, и в строку легло
            // бы чужое значение.
            val fingerprint = engine.loadFingerprint
            val tokens = journal.lastPromptTokens
            scope.launch {
                val ok = store.append(index, turn, tokens, fingerprint)
                if (!diskWrites.tryEmit(DiskWrite(index, ok))) {
                    Log.w(TAG, "событие записи хода ${index + 1} потеряно: очередь полна")
                }
            }
        }
    }

    /** Сохранённая лента с диска, проверенная хранилищем. В память не кладёт. */
    suspend fun loadSaved(): JournalStore.LoadResult = store.load(engine.loadFingerprint)

    /**
     * Поднять прочитанную ленту в память, а следом за ней — точку движка.
     * Единственный путь подъёма: и по выбору владельца в диалоге, и сам
     * ([resumeSaved]).
     *
     * ТОЧКА ПОДНИМАЕТСЯ ТОЛЬКО СЛЕДОМ ЗА УСПЕШНО ПОДНЯТОЙ ЛЕНТОЙ и никогда сама
     * по себе. Точка — состояние движка для этой ленты; без ленты движок взял
     * бы из неё только общее с новым запросом начало (стену), а остальное
     * стёр бы, то есть подъём был бы чтением с диска впустую. Ноля токенов
     * подъём не грозит ни при какой ленте: движок берёт из обсчитанного не
     * больше запроса без одного токена (reusable_prefix в gguf_lib.cpp).
     *
     * Отказ подъёма точки ничего не ломает: не поднялась — значит первый ход
     * пересчитает ленту целиком. Поэтому исход точки не ветвит, а только
     * показывается ([LlmEngine.getStateCheckpointReport]).
     *
     * @return false — лента не пуста, подъём поверх живого разговора сбил бы
     *   нумерацию ходов; точка тогда не трогается.
     */
    suspend fun raise(saved: List<ConversationJournal.Turn>, promptTokens: Int): Boolean =
        locked { raiseLocked(saved, promptTokens) }

    private suspend fun raiseLocked(saved: List<ConversationJournal.Turn>, promptTokens: Int): Boolean {
        if (!restore(saved, promptTokens)) return false
        engine.restoreStateCheckpoint()
        return true
    }

    /** Исход подъёма без спроса. */
    sealed class Resume {
        /** На диске пусто — поднимать нечего. */
        object NothingSaved : Resume()

        /** Лента в памяти уже не пуста — подниматься поверх нельзя. */
        object AlreadyLive : Resume()

        /** Поднято ходов [count]. */
        data class Raised(val count: Int) : Resume()

        /** Хранилище отказало; [reason] — его словами. */
        data class Refused(val reason: String) : Resume()
    }

    /**
     * Поднять сохранённую ленту без спроса. Чтение и подъём — под одним
     * замком: экран и служба, поднимающие разом, не поднимут дважды.
     */
    suspend fun resumeSaved(): Resume = locked {
        if (!journal.isEmpty) return@locked Resume.AlreadyLive
        when (val result = loadSaved()) {
            is JournalStore.LoadResult.Empty -> Resume.NothingSaved
            is JournalStore.LoadResult.Refused -> Resume.Refused(result.reason)
            is JournalStore.LoadResult.Restored ->
                if (raiseLocked(result.turns, result.promptTokens)) Resume.Raised(result.turns.size) else Resume.AlreadyLive
        }
    }

    /**
     * Лента в памяти.
     *
     * @return false — лента не пуста, подъём поверх живого разговора сбил бы
     *   нумерацию ходов.
     */
    private fun restore(saved: List<ConversationJournal.Turn>, promptTokens: Int): Boolean {
        if (!journal.restore(saved)) return false
        // Через существующий вход, а не новый: величина та же самая, и второе
        // место, где она задаётся, разошлось бы с первым молча. Ноль вход
        // отбрасывает сам — значит "не измерено" остаётся "не измерено", а не
        // становится измеренным нулём.
        journal.notePromptTokens(promptTokens)
        return true
    }

    /** Исход хода. */
    sealed class Outcome {
        /** Лента кончилась: не влезет даже короткий вопрос. В движок не ушло. */
        object JournalFull : Outcome()

        /** Эта реплика не влезает в остаток. В движок не ушло. */
        data class TooLong(val contentChars: Int, val maxChars: Int) : Outcome()

        /**
         * Реплика ушла в движок. [appended] — ход лёг в ленту (выдан хотя бы
         * один токен). [hidden] — ход скрыт: годного ответа лестница не нашла,
         * в ленту он не лёг, на экране заглушка (ConversationJournal.Hidden);
         * [appended] тогда false, хотя токены были. [failure] — сбой посреди выдачи; вызывающий бросает его
         * после своих дел, как бросал бы сбой изнутри хода.
         */
        data class Ran(
            val answer: String,
            val tokensSeen: Int,
            val firstTokenAtMs: Long?,
            val metrics: DecodingMetrics?,
            val generationEnd: GenerationEnd,
            val appended: Boolean,
            val failure: Throwable?,
            /** Был перехват (повтор или порча) — что с ним стало; null — не было. Числа выше — первой попытки. */
            val intercept: AnswerLadder.Intercept? = null,
            val hidden: Boolean = false,
            /**
             * Ответ, легший в ленту, дословно; null — ход не лёг (ноль токенов
             * или скрыт). При перехвате это НЕ [answer]: [answer] — первая
             * попытка, а в ленте вторая или вариант. Всё, что судит об ответе
             * хода после его закрытия (уведомление, вспоминание записей), берёт
             * этот текст: судить по отброшенному — судить о том, чего агент не
             * говорил.
             */
            val kept: String? = null,
            /**
             * Агент глянул на приборы ([Glance]) — первым проходом или во второй
             * попытке перехвата ([AnswerLadder.Intercept.secondGlanced]); ответ хода дан после
             * доски.
             */
            val glanced: Boolean = false,
            /**
             * После доски ответа не было, и проход со взглядом повторён (см.
             * [runLocked], ветка взгляда). Числа хода — повтора.
             */
            val glanceRetried: Boolean = false,
            /**
             * Ход скрыт как петля до потолка (см. [loopAtCeiling]): первое
             * повторившееся предложение и его номер в ответе с единицы; null —
             * не было. [hidden] тогда true, [intercept] — null: второй попытки
             * нет.
             */
            val loopSentence: String? = null,
            val loopAt: Int? = null,
        ) : Outcome()
    }

    /**
     * Провести ход: реплика [content] уходит в движок вслед за лентой, ответ
     * ложится в ленту парой с ней.
     *
     * @param question что показать как реплику в ленте (см. [ConversationJournal.appendTurn]).
     * @param records строки записей и снов этого хода — по ним лента отсеивает повторы.
     * @param selfNote строка состояния агента — отдельным сообщением перед
     *   репликой (голос и место — у [ConversationJournal.messagesFor]); null —
     *   перемен нет.
     *   Ушла ли она, видно по [ConversationJournal.Turn.selfNote] закрытого хода.
     *   Сторож места ([gate]) её не считает:
     *   это десятки токенов против запаса на ответ.
     * @param recordMarks пометки «кто → о ком» к строкам [records] (для экрана).
     * @param dreamNote описание последней ночи — сразу за строкой о себе, по
     *   тем же правилам голоса и места; null — не подаётся.
     * @param recall записи памяти этого хода сообщением агента перед репликой
     *   (см. [ConversationJournal.recallOf]); null — записей нет. Сторож места
     *   считает его вместе с [content].
     * @param onAccepted обе проверки края пройдены, реплика сейчас уйдёт.
     * @param onStarted выдача начинается в момент `at`; `engineReturn` — строка
     *   о возврате движка после чужой работы или null, если возврата не было.
     * @param onEvent каждое событие выдачи, как есть.
     * @param afterSend реплика ушла в движок и выдача кончилась — до закрытия хода
     *   в ленте. Зовётся и на нуле токенов: реплика всё равно сказана. Зовётся
     *   один раз — после первой попытки: реплика сказана тогда.
     * @param onRetry первый ответ пойман (повтор или порча — [AnswerLadder.Cause]), сейчас
     *   начнётся вторая попытка; её события пойдут в [onEvent] так же, как
     *   первой. Экрану — убрать показанный первый ответ, иначе второй
     *   допишется к нему.
     * @param onThird вторая попытка тоже не годна, сейчас пойдёт третья — три
     *   варианта. Её текст в [onEvent] не идёт (три варианта подряд — не ответ);
     *   экрану — убрать показанную вторую попытку и ждать закрытия хода.
     */
    suspend fun run(
        content: String,
        question: String,
        records: List<String>,
        selfNote: String? = null,
        recordMarks: Map<String, String> = emptyMap(),
        dreamNote: String? = null,
        recall: String? = null,
        onAccepted: () -> Unit = {},
        onStarted: (at: Long, engineReturn: String?) -> Unit = { _, _ -> },
        onEvent: (GenerationEvent) -> Unit = {},
        afterSend: suspend () -> Unit = {},
        onRetry: (AnswerLadder.Cause) -> Unit = {},
        onThird: () -> Unit = {},
        desk: (suspend () -> String)? = null,
        onGlance: () -> Unit = {},
        liveRead: LiveReadOrder? = null,
    ): Outcome {
        var closedIndex: Int? = null
        val outcome = locked {
            runLocked(content, question, records, selfNote, recordMarks, dreamNote, recall, onAccepted, onStarted, onEvent, afterSend, onRetry, onThird, desk, onGlance, liveRead) { closedIndex = it }
        }
        // Замок уже отпущен — затем событие и шлётся здесь (см. [closedEvents]).
        closedIndex?.let {
            if (!closes.tryEmit(it)) Log.w(TAG, "событие закрытия хода ${it + 1} потеряно: очередь полна")
        }
        return outcome
    }

    /** Тело [run], под замком. [onClosed] — номер хода, если он лёг в ленту. */
    private suspend fun runLocked(
        content: String,
        question: String,
        records: List<String>,
        selfNote: String?,
        recordMarks: Map<String, String>,
        dreamNote: String?,
        recall: String?,
        onAccepted: () -> Unit,
        onStarted: (at: Long, engineReturn: String?) -> Unit,
        onEvent: (GenerationEvent) -> Unit,
        afterSend: suspend () -> Unit,
        onRetry: (AnswerLadder.Cause) -> Unit,
        onThird: () -> Unit,
        desk: (suspend () -> String)?,
        onGlance: () -> Unit,
        liveRead: LiveReadOrder?,
        onClosed: (Int) -> Unit,
    ): Outcome {
        gate(journal, content, CONTEXT_SIZE, ANSWER_TOKEN_LIMIT, recall)?.let { return it }
        onAccepted()

        // Движок брал кто-то другой — разбор памяти или цикл, — и разговора в
        // нём нет. Точка, записанная движком перед той работой, поднимается
        // здесь, в паре с лентой. Зачем и чего не умеет — у
        // LlmEngine.conversationDisplaced. До секундомера: подъём — не часть
        // ответа, у него своя строка.
        var engineReturn: String? = null
        if (!journal.isEmpty && engine.conversationDisplaced) {
            val restoreStartedAt = System.currentTimeMillis()
            engine.restoreStateCheckpoint()
            val restoreMs = System.currentTimeMillis() - restoreStartedAt
            engineReturn = "Возврат движка: " +
                (engine.borrowReport?.let { "перед чужой работой — $it; " } ?: "") +
                "перед ответом — ${engine.getStateCheckpointReport()} " +
                "(${"%.1f".format(restoreMs / 1000.0)} с)"
        }

        // Номер хода, перед которым может встать ожидающая стена: ход ещё не лёг.
        val turnNumber = journal.history().size + 1

        val startMs = System.currentTimeMillis()
        onStarted(startMs, engineReturn)
        var appended = false
        // Лента экрана для лестницы: запрос — из ленты разговора, ход ложится
        // в неё парой с репликой, а годного ответа нет — ход скрывается
        // (ConversationJournal.Hidden): на экране вопрос и заглушка, модели не
        // подаётся ни то, ни другое.
        val ribbon = object : AnswerLadder.Ribbon {
            override fun history() = journal.history()
            override fun messages(without: Set<Int>) =
                journal.messagesFor(content, selfNote, dreamNote, recall, without = without)
            override fun stableMessages(without: Set<Int>) = if (without.isEmpty()) journal.stableMessageCount() else null
            override fun close(answer: String, rejected: String?) {
                journal.appendTurn(
                    userContent = content,
                    agentContent = answer,
                    question = question,
                    records = records,
                    selfNote = selfNote,
                    at = startMs,
                    dreamNote = dreamNote,
                    marks = recordMarks,
                    recall = recall,
                    rejected = rejected,
                )
                appended = true
                onClosed(journal.history().lastIndex)
            }
            override fun hide(reason: String, first: String, rejected: List<ConversationJournal.Rejected>) {
                journal.hideTurn(question, reason, rejected)
            }
        }
        val ran = ladder(recall, liveRead).run(
            ribbon, question, desk,
            onEvent = onEvent,
            afterSend = afterSend,
            // Размер запроса берём у движка, а не считаем сами: пересчёт знаков
            // в токены завышает на треть. Зовётся ДО закрытия хода: закрытие
            // уводит ход на диск вместе с этим числом, и обнови счётчик после —
            // в строку ушло бы значение предыдущего хода. Ход со взглядом
            // считает запрос прохода с доской, которой в ленте нет: завышение на
            // доску до следующего хода — в безопасную сторону. Число — первой
            // попытки и при перехвате: сторож места ([gate]) должен считать от
            // полной ленты.
            onFirstMetrics = { journal.notePromptTokens(it.tokensEvaluated) },
            onRetry = onRetry,
            onThird = onThird,
            onGlance = onGlance,
        )
        // Стена ставится в начале запроса разговора (LlmEngine.applyWall), то
        // есть уже случилась или не случилась к этому месту.
        val wallChange = engine.takeAppliedWallChange()
        if (wallChange != null) {
            wallChangeLine = BuildSelfDescription.changeLine(turnNumber, wallChange.first, wallChange.second)
        } else if (engine.wallDeferredBusy) {
            wallChangeLine = "Стена ждёт: перед ходом $turnNumber движок был занят другой " +
                "работой, встанет со следующего хода"
        }
        return Outcome.Ran(
            answer = ran.answer,
            tokensSeen = ran.tokensSeen,
            firstTokenAtMs = ran.firstTokenAtMs,
            metrics = ran.metrics,
            generationEnd = ran.generationEnd,
            appended = appended,
            failure = ran.failure,
            intercept = ran.intercept,
            hidden = ran.hidden,
            kept = ran.kept,
            glanced = ran.glanced,
            glanceRetried = ran.glanceRetried,
            loopSentence = ran.loop?.loop,
            loopAt = ran.loop?.loopAt,
        )
    }

    /** Лестница ответа экрана: движок — разговор экрана (см. [AnswerLadder]). */
    private fun ladder(recall: String?, liveRead: LiveReadOrder?) = AnswerLadder(
        object : AnswerLadder.Engine {
            override fun generate(messages: List<Pair<String, String>>, dryAgainst: String?, stableMessages: Int?, first: Boolean) =
                engine.generateConversationFlow(
                    messages, ANSWER_TOKEN_LIMIT, dryAgainst = dryAgainst, recall = recall,
                    // Живой отвод — только первому проходу: реплика собеседника
                    // обсчитывается в нём, следующие проходы берут её готовой.
                    liveRead = liveRead.takeIf { first }, stableMessages = stableMessages,
                )
            override val lastEnd: GenerationEnd get() = engine.lastGenerationEnd
            override val lastDryFed: Int get() = engine.lastDryFed
            override val wallText: String? get() = engine.wallText
        },
        ANSWER_TOKEN_LIMIT,
        warn = { message, e -> Log.w(TAG, message, e) },
    )

    companion object {
        private const val TAG = "ConversationTurns"

        /**
         * Потолок длины ответа для хода разговора.
         *
         * Раньше здесь не стояло ничего и потолок брался из умолчания
         * [LlmEngine.generateFlow]. Названо явно по двум причинам: чтобы смена
         * умолчания в движке не поменяла нам поведение молча, и чтобы отчёт о
         * генерации мог СРАВНИТЬ с этим числом длину ответа и сказать, что
         * ответ обрезан.
         *
         * Наблюдалось живьём 26.08.2026: `Токенов: ответ 512`, текст кончается
         * посреди фразы словом «Стоит», и на экране об этом ни строки.
         *
         * ЭТО ЧИСЛО НЕ ТОЛЬКО ПОТОЛОК ГЕНЕРАЦИИ: от него же считается бюджет
         * ленты (см. [gate]). Поднимая его, отнимаешь у разговора столько же
         * токенов общего окна — лента кончается раньше, и длинные вопросы
         * перестают приниматься. Поэтому оно не поднимается «на всякий случай»
         * и не остаётся поднятым.
         *
         * Пятиминутный потолок непрерывной работы в стороже штатным ответом
         * этой длины недостижим: при наблюдавшихся 160–210 мс на токен 512
         * токенов укладываются в полторы минуты. Чтобы наблюдать обрыв по
         * потолку, число временно поднимают до 2000 и задают счётный вопрос;
         * после замера возвращают, потому что бюджет ленты считается отсюда же.
         */
        const val ANSWER_TOKEN_LIMIT = 512

        /** Петля до потолка экрана — правило у [AnswerLadder.loopAtCeiling]. */
        fun loopAtCeiling(answer: String, predicted: Int): EchoCheck.Result? =
            AnswerLadder.loopAtCeiling(answer, predicted, ANSWER_TOKEN_LIMIT)

        /** Строка «Перехват:» хода, скрытого как петля до потолка (см. [loopAtCeiling]). */
        fun loopCutLine(at: Int, sentence: String): String =
            "Перехват: ответ ушёл в петлю — с предложения $at «$sentence» — и упёрся в потолок " +
                "$ANSWER_TOKEN_LIMIT токенов · ход скрыт от агента, на экране заглушка · второй " +
                "попытки нет: она шла бы до того же потолка · в ленту не записан, записи не " +
                "помечены уложенными"

        /**
         * Проверка края ДО отправки. Движок при переполнении молча выбрасывает
         * половину ленты посреди генерации, поэтому упереться незаметно нельзя
         * — останавливаемся явно.
         *
         * ПРОВЕРОК ДВЕ, И ПОРЯДОК У НИХ НЕ СЛУЧАЕН. Сперва «влезет ли сюда хоть
         * что-нибудь»: если не помещается даже короткий вопрос, укорачивать
         * бесполезно, и совет укоротить был бы советом в никуда. Только потом —
         * «влезает ли этот текст». Лечения у них разные и противоположные по
         * цене: первое закрывает разговор, второе просит сократить одно
         * сообщение.
         *
         * Меряется всё, что этот ход добавит, а не набранный вопрос: в движок
         * уходит [content] и перед ним [recall] — записи памяти голосом агента
         * (ConversationJournal.recallOf). Знаки считаются вместе, одним числом.
         *
         * @return [Outcome.JournalFull], [Outcome.TooLong] или null — можно.
         */
        fun gate(
            journal: ConversationJournal,
            content: String,
            contextSize: Int,
            answerLimit: Int,
            recall: String? = null,
        ): Outcome? {
            if (journal.roomLeft(contextSize, answerLimit) <= 0) return Outcome.JournalFull
            val maxChars = journal.maxContentChars(contextSize, answerLimit)
            val chars = content.length + (recall?.length ?: 0)
            if (chars > maxChars) return Outcome.TooLong(chars, maxChars)
            return null
        }
    }
}
