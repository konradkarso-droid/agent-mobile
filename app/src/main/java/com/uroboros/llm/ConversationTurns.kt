package com.uroboros.llm

import android.util.Log
import com.dark.gguf_lib.models.DecodingMetrics
import com.dark.gguf_lib.models.GenerationEvent
import kotlinx.coroutines.CancellationException
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
 *    прогон, счёт токенов и закрытие хода в ленте.
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
         * один токен). [failure] — сбой посреди выдачи; вызывающий бросает его
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
            val intercept: Intercept? = null,
        ) : Outcome()
    }

    /** Почему первый ответ хода перехвачен. */
    sealed class Cause {
        /**
         * Повтор своего прошлого ответа ([EchoIntercept]): вторая попытка без
         * ходов-образцов [without]. [sentence] — первое повторённое предложение.
         */
        data class Repeat(val sentence: String, val without: Set<Int>) : Cause()

        /**
         * Порча слов без повтора ([WordDamage]): повторная выборка на той же
         * ленте. Запрос тот же, поэтому пересчёта ленты нет — платится только
         * сам ответ. Другим ответ выходит потому, что у каждого ответа
         * разговора своё зерно (LlmEngine.guardedFlow). [words] — найденные
         * куски, как их показать.
         */
        data class Damage(val words: List<String>) : Cause()
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
     * @property replaced в ленту лёг второй ответ, а первый — отброшенным. false —
     *   в ленту лёг первый ответ как есть: вторая попытка не состоялась (оборвана,
     *   сбой, ноль токенов) или, при порче, сама оказалась не лучше (см. [secondAttempt]).
     * @property secondRepeats второй ответ — повтор той же мерой, что у прибора эха.
     *   При повторе он всё равно остаётся в ленте: честная строка была бы
     *   текстом-заглушкой, а их модель копирует; пометка нужна, чтобы частота
     *   тупиков была видна на экране.
     * @property secondDamage порча во втором ответе (пусто — нет или детектор не
     *   работает). При повторе второй ответ остаётся в ленте и с ней: заменить его
     *   пока нечем.
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
    ) {
        // ПЕРЕХОДНОЕ: старые поля для экрана, собранного до [cause]. Нужны, чтобы
        // файлы грузились по одному без красной сборки между ними; убираются
        // со следующей правкой этого файла.
        val sentence: String get() = (cause as? Cause.Repeat)?.sentence ?: ""
        val without: Set<Int> get() = (cause as? Cause.Repeat)?.without ?: emptySet()
    }

    /**
     * Провести ход: реплика [content] уходит в движок вслед за лентой, ответ
     * ложится в ленту парой с ней.
     *
     * @param question что показать как реплику в ленте (см. [ConversationJournal.appendTurn]).
     * @param records строки записей и снов этого хода — по ним лента отсеивает повторы.
     * @param selfNote строка состояния агента для системного сообщения перед
     *   репликой (см. [ConversationJournal.messagesFor]); null — перемен нет.
     *   Ушла ли она, видно по [ConversationJournal.Turn.selfNote] закрытого хода:
     *   на пустой ленте она не уходит. Сторож места ([gate]) её не считает:
     *   это десятки токенов против запаса на ответ.
     * @param recordMarks пометки «кто → о ком» к строкам [records] (для экрана).
     * @param dreamNote описание последней ночи — системным сообщением сразу за
     *   строкой о себе, по тем же правилам места; null — не подаётся.
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
     * @param onRetry первый ответ пойман (повтор или порча — [Cause]), сейчас
     *   начнётся вторая попытка; её события пойдут в [onEvent] так же, как
     *   первой. Экрану — убрать показанный первый ответ, иначе второй
     *   допишется к нему.
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
        onRetry: (Cause) -> Unit = {},
    ): Outcome {
        var closedIndex: Int? = null
        val outcome = locked {
            runLocked(content, question, records, selfNote, recordMarks, dreamNote, recall, onAccepted, onStarted, onEvent, afterSend, onRetry) { closedIndex = it }
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
        onRetry: (Cause) -> Unit,
        onClosed: (Int) -> Unit,
    ): Outcome {
        gate(journal, content, CONTEXT_SIZE, ANSWER_TOKEN_LIMIT, recall)?.let { return it }
        val messages = journal.messagesFor(content, selfNote, dreamNote, recall)
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
        var firstTokenAtMs: Long? = null
        var metrics: DecodingMetrics? = null
        // Хвост 19: считаем выданные токены сами, а не спрашиваем движок.
        // Метрики он присылает не всегда, а факт "не появилось ни знака" надо
        // назвать при любом исходе.
        var tokensSeen = 0
        // Ответ копится ОТДЕЛЬНО от того, что видно на экране. Правило
        // дословности: в ленту должно лечь ровно то, что выдал движок. Собирать
        // текст обратно с экрана нельзя — там он смешан с прошлыми ходами и
        // подписями, и любое расхождение на один знак оборвало бы совпадение с
        // обсчитанным началом. Признаком была бы только выросшая строка "до
        // 1-го токена", то есть поломка, о которой ничто не сообщит.
        val answer = StringBuilder()
        var failure: Throwable? = null
        var appended = false
        var generationEnd: GenerationEnd? = null
        var cause: Cause? = null
        // Слова запроса для детектора порчи: всё, что модель видела, вместе со
        // стеной. Слово ответа, стоящее там в той же форме (имя, термин из
        // записи), порчей не считается. Собирается, только если понадобится.
        val request by lazy { WordDamage.requestWords(messages.joinToString("\n") { it.second } + "\n" + (engine.wallText ?: "")) }

        // Закрыть ход в ленте. Одно место для обеих попыток: правило
        // дословности ([answer] выше) одно и то же.
        fun close(agentContent: String, rejected: String?) {
            journal.appendTurn(
                userContent = content,
                agentContent = agentContent,
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

        try {
            engine.generateConversationFlow(messages, ANSWER_TOKEN_LIMIT).collect { event ->
                when (event) {
                    is GenerationEvent.Token -> {
                        if (firstTokenAtMs == null) firstTokenAtMs = System.currentTimeMillis() - startMs
                        tokensSeen++
                        answer.append(event.text)
                    }
                    is GenerationEvent.Metrics -> metrics = event.metrics
                    // Done намеренно НЕ разбирается: библиотека не обещает, что
                    // метрики придут до него, поэтому итог собирается после
                    // выхода из collect.
                    else -> Unit
                }
                onEvent(event)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e
        } finally {
            // Причина читается ПЕРВОЙ: движок хранит её до начала следующего
            // прогона, и всё, что ниже, вправе на неё опираться.
            generationEnd = engine.lastGenerationEnd
            afterSend()
            // Ноль токенов — отказ движка, а не реплика разговора: ход в ленту
            // не идёт. Ход без экрана ТЕМ ЖЕ правилом.
            if (tokensSeen > 0) {
                // Размер запроса берём у движка, а не считаем сами: пересчёт
                // знаков в токены завышает на треть, замерено 28.08 на тексте
                // стены.
                //
                // Стоит ДО закрытия хода намеренно: закрытие хода уводит его на
                // диск вместе с этим числом. Обнови счётчик после — и в строку
                // ушло бы значение предыдущего хода, расхождение на один ход,
                // которое ничем себя не выдаёт.
                //
                // Число — первой попытки и при перехвате: запрос второй короче
                // на выкинутые ходы, а следующий ход пойдёт с полной лентой.
                // Сторож места ([gate]) должен считать от полной.
                metrics?.let { journal.notePromptTokens(it.tokensEvaluated) }
                // Перехват — только у ответа, выданного до конца: оборванный
                // (сторож, отмена, сбой) показывает не то, что модель хотела
                // сказать, и второй прогон после обрыва сторожем — тот самый
                // нагрев, от которого обрывали.
                cause = if (failure == null && generationEnd == GenerationEnd.COMPLETED) {
                    causeOf(answer.toString(), question, journal.history()) { request }
                } else {
                    null
                }
                if (cause == null) close(answer.toString(), null)
            }
        }

        // Сюда ход доходит, только если первую попытку не отменили (отмена
        // брошена выше, и ход уже закрыт первым ответом).
        val intercept = cause?.let { c -> secondAttempt(c, content, selfNote, dreamNote, recall, question, answer.toString(), { request }, onRetry, onEvent, ::close) }
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
            answer = answer.toString(),
            tokensSeen = tokensSeen,
            firstTokenAtMs = firstTokenAtMs,
            metrics = metrics,
            generationEnd = generationEnd!!,
            appended = appended,
            failure = failure,
            intercept = intercept,
        )
    }

    /**
     * Почему перехватывать готовый ответ, или null — не надо. Повтор проверяется
     * первым: при повторе ответ уходит целиком, и его порча уже не важна.
     * Детектор не загружен ([WordDamageHolder]) — порча не проверяется вовсе,
     * и об этом говорит его строка в «Подробно».
     */
    private fun causeOf(
        answer: String,
        question: String,
        history: List<ConversationJournal.Turn>,
        request: () -> Set<String>,
    ): Cause? {
        EchoIntercept.decide(answer, question, history)?.let { return Cause.Repeat(it.sentence, it.without) }
        val damage = WordDamageHolder.damage?.check(answer, request()).orEmpty()
        return if (damage.isEmpty()) null else Cause.Damage(damage)
    }

    /**
     * Вторая попытка после перехвата. При повторе — запрос без ходов-образцов
     * ([ConversationJournal.messagesFor], `without`); при порче — тот же запрос,
     * то есть повторная выборка. Закрывает ход сам — при любом исходе, в том
     * числе при отмене.
     *
     * ЧТО ЛОЖИТСЯ В ЛЕНТУ. Второй ответ выдан до конца — при ПОВТОРЕ он, а первый
     * в поле «отброшенный» (модели не подаётся, см. [ConversationJournal.Turn]).
     * При ПОРЧЕ второй берётся, только если он чист: без порчи и не повтор. Иначе
     * остаётся первый — повторная выборка, давшая повтор вместо порчи, хуже: копия
     * размножается по ленте, а порча — нет. Вторая попытка оборвана, сбилась или
     * пуста — первый ответ как есть: человек его уже видел целиком, а оборванный
     * второй ответом не является.
     *
     * ЧЕГО НЕ ДЕЛАЕТ:
     *  - третьей попытки нет. Второй ответ, снова пойманный на повторе или на
     *    порче, остаётся при повторе, а при порче остаётся испорченный первый:
     *    честная строка вместо них была бы текстом-заглушкой, а их модель копирует.
     *    Это видно в [Intercept.secondRepeats] и [Intercept.secondDamage];
     *  - сбой второй попытки наружу не бросается: ход состоялся первым ответом,
     *    сбой назван в [Intercept.secondEnd].
     */
    private suspend fun secondAttempt(
        cause: Cause,
        content: String,
        selfNote: String?,
        dreamNote: String?,
        recall: String?,
        question: String,
        first: String,
        request: () -> Set<String>,
        onRetry: (Cause) -> Unit,
        onEvent: (GenerationEvent) -> Unit,
        close: (String, String?) -> Unit,
    ): Intercept {
        // История снимается ДО закрытия хода: мера второго ответа — против тех
        // же трёх прошлых ответов, что и у первого.
        val history = journal.history()
        val without = (cause as? Cause.Repeat)?.without ?: emptySet()
        val messages = journal.messagesFor(content, selfNote, dreamNote, recall, without = without)
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
        var damage = emptyList<String>()
        try {
            engine.generateConversationFlow(messages, ANSWER_TOKEN_LIMIT).collect { event ->
                when (event) {
                    is GenerationEvent.Token -> {
                        pieces++
                        second.append(event.text)
                    }
                    is GenerationEvent.Metrics -> metrics = event.metrics
                    else -> Unit
                }
                onEvent(event)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = true
            Log.w(TAG, "вторая попытка сорвалась", e)
        } finally {
            end = engine.lastGenerationEnd
            val done = pieces > 0 && !failed && end == GenerationEnd.COMPLETED
            if (done) {
                repeats = EchoIntercept.decide(second.toString(), question, history) != null
                damage = WordDamageHolder.damage?.check(second.toString(), request()).orEmpty()
            }
            replaced = when (cause) {
                is Cause.Repeat -> done
                is Cause.Damage -> done && !repeats && damage.isEmpty()
            }
            if (replaced) close(second.toString(), first) else close(first, null)
        }
        return Intercept(
            cause = cause,
            secondAnswer = second.toString(),
            secondEnd = end,
            secondMs = System.currentTimeMillis() - startedAt,
            secondTokens = metrics?.tokensPredicted,
            replaced = replaced,
            secondRepeats = repeats,
            secondDamage = damage,
        )
    }

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
