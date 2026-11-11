package com.uroboros.gate.link

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.documentfile.provider.DocumentFile
import com.dark.gguf_lib.models.GenerationEvent
import com.uroboros.AgentService
import com.uroboros.ModelPrefs
import com.uroboros.ProcessObjects
import com.uroboros.gate.ChatFeed
import com.uroboros.gate.CrossChannel
import com.uroboros.gate.ChatOwnSpeech
import com.uroboros.gate.ChatPortrait
import com.uroboros.gate.ChatPromises
import com.uroboros.gate.ChatQuiet
import com.uroboros.gate.ChatRecall
import com.uroboros.gate.ChatReply
import com.uroboros.gate.ChatRevive
import com.uroboros.gate.ChatThread
import com.uroboros.gate.ChatTurnMeter
import com.uroboros.gate.GateBotApi
import com.uroboros.gate.GateTelegram
import com.uroboros.gate.TgWords
import com.uroboros.llm.AnswerLadder
import com.uroboros.llm.ChatDatabase
import com.uroboros.llm.ChatLine
import com.uroboros.llm.ConversationJournal
import com.uroboros.llm.EchoCheck
import com.uroboros.llm.GenerationEnd
import com.uroboros.llm.Glance
import com.uroboros.llm.GlanceDesk
import com.uroboros.llm.Silence
import com.uroboros.llm.WordDamageHolder
import com.uroboros.memory.ActionProvenance
import com.uroboros.memory.ActionRequest
import com.uroboros.memory.ActionType
import com.uroboros.memory.ConfidenceLevel
import com.uroboros.memory.DolmenCircle
import com.uroboros.memory.EmergencyStop
import com.uroboros.memory.GateResult
import com.uroboros.memory.GatedAction
import com.uroboros.memory.desk.Promises
import com.uroboros.memory.dream.FactsReader
import com.uroboros.memory.Heard
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.RecallLines
import com.uroboros.memory.RetrievalPurpose
import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.AddressSorter
import com.uroboros.memory.nav.Coordinates
import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.memory.nav.LemmaHolder
import com.uroboros.memory.nav.NameClaim
import com.uroboros.memory.nav.OwnSpeech
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place
import com.uroboros.memory.nav.Portrait
import com.uroboros.memory.nav.RecallRequest
import com.uroboros.memory.nav.RetellHolder
import com.uroboros.safety.BodyLine
import com.uroboros.safety.SafetyZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Слова в телеграме: обычные сообщения людей и ботов боту агента — лента
 * чата, кольцо обращения, память, ответ. Живёт в службе агента рядом с
 * каналом врат ([GateLink]): тот опрашивает и шлёт, этот решает, что делать
 * со словами.
 *
 * ПУТЬ СООБЩЕНИЯ ([heard]): слова ([TgWords]) → «сообщение прямо перед этим»
 * из ленты чата → кольцо ([AddressSorter.sort]) → строка ленты чата
 * ([ChatDatabase]) → память, если велит кольцо (AddressSorter.memory) → в
 * очередь ответа, если кольцо велит ответить или переспросить.
 *
 * ПАМЯТЬ — общая, через дверь записи (TrustedMediator.saveEvent) со словом
 * канала: кто сказал, где, в каком чате, кольцо (memory.Heard). Чужие слова —
 * «Подпись: текст» ([TgWords.memoryText]): отбор, портрет и судья читают их
 * чужой речью, как реплики других, переданные владельцем. Слова владельца,
 * отмеченного «это я» ([ChatPrefs.owner]), — без подписи: они его.
 *
 * ОТВЕТ ([work]) — по одному, в очереди, под замком хода
 * (ConversationTurns.aside), с решением [ChatReply.decide] до хода и
 * повторной проверкой после замка. Модель читает стену, ленту чата окном
 * скачками ([ChatFeed.window]), нить — выжимку того, что окна не видно
 * ([ChatThread]), — и записи памяти, дозволенные этому чату: владельцу в
 * личке — вся память тем же отбором, что на экране, остальным — только своё
 * (граница и её цена — у [ChatRecall]). Ответ идёт той же
 * лестницей, что на экране (AnswerLadder): взгляд на приборы (доска этого
 * чата, GlanceDesk; на слово о приборах в вопросе доску показывает код —
 * Glance.asksInstruments), перехват повтора, зеркала и порчи. Ушло — только через ворота
 * действий с согласием владельца, без срока ([ChatPrefs.consent]); каждое
 * отправленное — в журнале действий.
 *
 * СУД И НОЧЬ. Пока в телеграме идёт разговор, сами они не начинаются
 * (gate.ChatQuiet). Начатые самим агентом — уступают: сообщение, на которое
 * агент ответит, обрывает их так же, как реплика на экране ([heard]), и ответ
 * ждёт, пока они отпустят модель ([awaitYield]).
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - разбор, начатый кнопкой «Разбор памяти», телеграму не уступает: ответ
 *    молчит со строкой «идёт ночная работа» — кнопку нажал владелец, и обрывать
 *    его работу чужим сообщением нельзя;
 *  - очередь и счёт обращения — в памяти процесса. Ждавшие ответа после комы
 *    подхватываются по ленте чатов и отметкам на диске (ChatRevive) — свежие,
 *    и ответ, на котором служба умерла, только один раз сверх первого; счёт
 *    обращения начинается заново;
 *  - отвечает ровно на то сообщение, что его позвало: несколько подряд — несколько ответов.
 */
object ChatLink {

    /** Компонент для ворот действий и согласия: одно имя в одном месте. */
    const val REQUESTED_BY = "ChatLink: ответ в телеграме"

    /**
     * Объявленные числа, не подобранные: предел ответа; сколько последних строк
     * ленты, всех колец, читать на ход (текущая строка, счёт ответов ботам, фон
     * группы для хвоста нити); сколько читаемых строк — для окна модели (их
     * должно хватать на окно ChatFeed.BUDGET_CHARS и при коротких репликах).
     */
    const val ANSWER_TOKENS = 300
    private const val RIBBON_READ = 300
    private const val WINDOW_READ = 400

    /** Между двумя отправками в один чат: правило телеграма — около раза в секунду (помню). */
    private const val SEND_GAP_MS = 1_100L

    /** Срок блокировки сна на ход — страховка, если finally не отработает. Объявленное. */
    private const val WAKE_MS = 10 * 60_000L

    /**
     * Сколько ответ ждёт, пока уступивший суд или шаги ночи отпустят модель:
     * после обрыва служба возвращает дневную загрузку модели. Объявленное число,
     * то же, что у экрана.
     */
    private const val YIELD_WAIT_MS = 5 * 60_000L

    private val _status = MutableStateFlow(listOf("Слова: сообщений не было"))

    /** Строки слов для экрана врат: счёт, согласие, владелец, имена, кольца, ответы. */
    val status: StateFlow<List<String>> get() = _status

    private class Job(
        val chatId: Long,
        val chat: String,
        val thread: Long?,
        val lineId: Long,
        val messageId: Long,
        val sender: PersonKey,
        val answer: AddressSorter.Answer,
        val senderIsBot: Boolean,
        val atMs: Long,
        val place: Place,
        val chatTitle: String?,
        val tallyKey: String,
    )

    private val queue = Channel<Job>(Channel.UNLIMITED)
    private val waiting = ConcurrentHashMap<String, AtomicInteger>()
    private val tallies = ConcurrentHashMap<String, Pair<String, AddressSorter.Tally>>()
    private val answers = ConcurrentHashMap<String, String>()
    private val evals = ConcurrentHashMap<String, String>()
    private val threads = ConcurrentHashMap<String, String>()
    private val points = ConcurrentHashMap<String, String>()

    /** Ответов и строк молчания в каждом чате с запуска службы — для строки «Ответ». */
    private val decisions = ConcurrentHashMap<String, IntArray>()

    /** Последняя запись снимка каждого чата перед чужой работой — по отчёту движка, узнаётся на ходе чата. */
    private val saves = ConcurrentHashMap<String, String>()

    /** Скорость выдачи прошлого ответа в каждом чате, ток/с, — для доски чата (GlanceDesk). */
    private val speeds = ConcurrentHashMap<String, String>()

    /** Строка «Записи (…)» каждого чата: что память дала последнему ходу и по какой границе (ChatRecall). */
    private val recalls = ConcurrentHashMap<String, String>()

    /** Начало окна прошлого ответа в чате — для «на месте / сдвинулось» на экране врат. */
    private val windowStarts = ConcurrentHashMap<String, Long>()
    private val lastSent = ConcurrentHashMap<Long, Long>()
    private val direct = AtomicInteger(0)
    private val group = AtomicInteger(0)

    @Volatile
    private var lastAt: Long? = null

    /** Отметки подхвата после комы (ChatRevive); пишет только очередь ответов. */
    @Volatile
    private var marks: ChatRevive.Marks? = null

    /** Строка подхвата для экрана врат; null — очередь ещё не смотрела. */
    @Volatile
    private var reviveLine: String? = null

    /** Строки ленты, подхваченные после комы: пришедшие ещё и очередью второй раз не отвечаются. */
    private val revived = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var me: GateBotApi.Me? = null

    @Volatile
    private var failedUri: String? = null

    private var worker: kotlinx.coroutines.Job? = null

    /** Запустить очередь ответов в области службы; уже идёт — ничего. */
    @Synchronized
    fun start(context: Context, scope: CoroutineScope) {
        if (worker?.isActive == true) return
        val app = context.applicationContext
        // Объекты процесса — здесь, на потоке службы, а не первым сообщением на
        // потоке опроса: сторож и движок заводятся там, где их ждут.
        ProcessObjects.get(app)
        refresh(app)
        worker = scope.launch(Dispatchers.IO) { work(app) }
    }

    private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))

    private fun moment(ms: Long) = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(ms))

    /** День для модели: «30 октября» — как у доски экрана. */
    private fun day(ms: Long) = SimpleDateFormat("d MMMM", Locale("ru")).format(Date(ms))

    /**
     * Когда установлена нынешняя сборка — граница разговора чата
     * (ChatFeed.conversations). Не меняется, пока жив процесс: установка
     * сборки процесс убивает. null — система не ответила, делит только тишина.
     */
    @Volatile private var installedAtCache: Long? = null
    private fun installedAt(app: Context): Long? = installedAtCache ?: runCatching {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
    }.getOrNull()?.also { installedAtCache = it }

    /** Сообщение без файла из канала. Зовёт опрос [GateLink] на своём потоке. */
    suspend fun heard(app: Context, m: GateBotApi.Message, me: GateBotApi.Me) {
        this.me = me
        val w = when (val read = TgWords.of(m, me)) {
            is TgWords.Read.NotWords -> {
                GateLink.note("Слова: ${read.why}")
                refresh(app)
                return
            }
            is TgWords.Read.Of -> read.words
        }
        val dao = ChatDatabase.getInstance(app).chatLineDao()
        if (w.messageId > 0 && dao.heardId(w.chat.id, w.messageId) != null) {
            GateLink.note("Слова (${w.name}): сообщение ${w.messageId} уже в ленте — телеграм прислал его снова, второй раз не беру")
            refresh(app)
            return
        }
        val prior = dao.recent(w.chat.id, w.thread, 1).firstOrNull()?.let(::priorOf)
        val route = AddressSorter.sort(w.message, self(app, me), prior)
        val lineId = dao.insert(
            ChatLine(
                chat = w.chat.id, thread = w.thread, messageId = w.messageId, sender = w.message.sender.id,
                name = w.name, signature = w.signature, isBot = w.senderIsBot, isAgent = false,
                text = w.message.text, ring = route.ring.name, reason = route.reason,
                replyTo = w.message.replyTo?.id, at = w.atMs, place = w.place.name,
            )
        )
        if (w.place == Place.DIRECT) direct.incrementAndGet() else group.incrementAndGet()
        lastAt = System.currentTimeMillis()
        val tallyKey = w.chatRef
        val chatName = if (w.place == Place.DIRECT) w.name else (w.chatTitle ?: w.chat.id) + (w.thread?.let { ", ветка $it" } ?: "")
        tallies.compute(tallyKey) { _, old -> chatName to ((old?.second ?: AddressSorter.Tally()) + route) }

        val owner = ChatPrefs.owner(app)?.key == w.message.sender
        val memo = remember(app, w, route, owner)
        GateLink.note("Слова (${w.name}, ${placeWord(w.place)}): ${ringWord(route.ring)} — ${route.reason}; $memo")

        val answer = AddressSorter.answer(route.ring)
        if (answer != AddressSorter.Answer.SILENT) {
            // Начатые самим агентом суд и шаги ночи уступают, как реплике на экране.
            if (selfRunning()) AgentService.yieldToConversation(app)
            val counter = waiting.getOrPut(tallyKey) { AtomicInteger(0) }
            if (counter.get() >= ChatReply.MAX_QUEUE) {
                answers[tallyKey] = "Ответ ($chatName): не отвечаю — очередь полна (${ChatReply.MAX_QUEUE})"
            } else {
                counter.incrementAndGet()
                queue.trySend(
                    Job(
                        chatId = m.chatId, chat = w.chat.id, thread = w.thread, lineId = lineId, messageId = w.messageId,
                        sender = w.message.sender, answer = answer, senderIsBot = w.senderIsBot, atMs = w.atMs,
                        place = w.place, chatTitle = w.chatTitle, tallyKey = tallyKey,
                    )
                )
            }
        }
        refresh(app)
    }

    /** Положить в память по кольцу; итог словами для строки события. */
    private suspend fun remember(app: Context, w: TgWords.Words, route: AddressSorter.Route, owner: Boolean): String {
        if (AddressSorter.memory(route.ring) == AddressSorter.Memory.NONE) return "в память не идёт"
        val text = TgWords.memoryText(w.message.text, w.signature, owner)
            ?: return "в память не легло: подпись «${w.signature}» не читается чужой речью"
        return try {
            val saved = ProcessObjects.get(app).mediator.saveEvent(
                content = text,
                source = SourceKind.USER_STATED,
                confidence = ConfidenceLevel.OBSERVED,
                heard = Heard(w.message.sender.id, w.place.name, w.chatRef, route.ring.name),
            )
            when {
                !saved.stored -> "в памяти уже есть"
                owner -> "в память — ваши слова"
                else -> "в память — с подписью «${w.signature}»"
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            "в память не легло: ${t.javaClass.simpleName}"
        }
    }

    private fun self(app: Context, me: GateBotApi.Me) = AddressSorter.Self(
        key = GateTelegram.userKey(me.id),
        usernames = setOfNotNull(me.username),
        names = ChatPrefs.names(app),
    )

    private fun priorOf(line: ChatLine) = AddressSorter.Prior(
        sender = PersonKey(line.sender),
        ring = line.ring?.let { r -> AddressSorter.Ring.entries.firstOrNull { it.name == r } },
        replyTo = line.replyTo?.let(::PersonKey),
    )

    private fun placeWord(p: Place) = if (p == Place.DIRECT) "личка" else "группа"

    private fun ringWord(r: AddressSorter.Ring) = when (r) {
        AddressSorter.Ring.TO_AGENT -> "к агенту"
        AddressSorter.Ring.ABOUT_AGENT -> "про агента"
        AddressSorter.Ring.DOUBTFUL -> "сомнительно"
        AddressSorter.Ring.GENERAL -> "общее"
    }

    // --- Очередь ответов ---

    private suspend fun work(app: Context) {
        // Подхваченные после комы — раньше всех новых: они пришли раньше.
        for (job in revive(app)) process(app, job)
        for (job in queue) {
            waiting[job.tallyKey]?.decrementAndGet()
            if (job.lineId in revived) continue
            process(app, job)
        }
    }

    /** Один ответ из очереди с отметками подхвата до и после (ChatRevive). */
    private suspend fun process(app: Context, job: Job) {
        mark(app) { ChatRevive.taking(it, job.lineId) }
        val name = tallies[job.tallyKey]?.first ?: job.chat
        val line = try {
            reply(app, job)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            "не ответил — сбой: ${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}"
        }
        mark(app) { ChatRevive.decided(it, job.lineId) }
        answers[job.tallyKey] = "Ответ ($name): $line"
        // И событием: строка «Ответ» держит только последний исход чата, а следующее сообщение её затирает.
        GateLink.note("Ответ ($name): $line")
        refresh(app)
    }

    // --- Подхват после комы ---

    private const val REVIVE_PREFS = "uroboros_chat_revive"
    private const val DECIDED_UP_TO = "decided_up_to"
    private const val TAKEN = "taken"
    private const val TRIES = "tries"

    private fun revivePrefs(app: Context) = app.getSharedPreferences(REVIVE_PREFS, Context.MODE_PRIVATE)

    private fun storedMarks(app: Context): ChatRevive.Marks? {
        val p = revivePrefs(app)
        if (!p.contains(DECIDED_UP_TO)) return null
        return ChatRevive.Marks(p.getLong(DECIDED_UP_TO, 0L), p.getLong(TAKEN, 0L), p.getInt(TRIES, 0))
    }

    /**
     * Сменить отметки и записать сразу, не откладывая: смерть службы через миг
     * после отметки «взялся» — ровно тот случай, ради которого она пишется.
     */
    private fun mark(app: Context, change: (ChatRevive.Marks) -> ChatRevive.Marks) {
        val m = change(marks ?: storedMarks(app) ?: ChatRevive.startMarks(0L))
        marks = m
        revivePrefs(app).edit().putLong(DECIDED_UP_TO, m.decidedUpTo).putLong(TAKEN, m.taken).putInt(TRIES, m.tries).commit()
    }

    /** Ждавшие ответа после комы — задания по порядку; строка экрана — в [reviveLine]. */
    private suspend fun revive(app: Context): List<Job> {
        val at = clock(System.currentTimeMillis())
        return try {
            val dao = ChatDatabase.getInstance(app).chatLineDao()
            val stored = storedMarks(app)
            if (stored == null) {
                val maxId = dao.maxId()
                mark(app) { ChatRevive.startMarks(maxId) }
                reviveLine = ChatRevive.line(ChatRevive.plan(emptyList(), null, System.currentTimeMillis())) + " (подъём $at)"
                return emptyList()
            }
            marks = stored
            val lines = dao.waitingAfter(stored.decidedUpTo, ChatQuiet.dialogRings)
            val plan = ChatRevive.plan(lines.map { ChatRevive.Waiting(it.id, it.at, it.text) }, stored, System.currentTimeMillis())
            val byId = lines.associateBy { it.id }
            val jobs = plan.again.mapNotNull { id -> byId[id]?.let(::jobOf) }
            revived += jobs.map { it.lineId }
            val lost = plan.again.size - jobs.size
            reviveLine = ChatRevive.line(plan) + (if (lost > 0) " · не разобрал строку ленты: $lost" else "") + " (подъём $at)"
            jobs
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            reviveLine = "После комы: лента чатов не прочиталась — ${t.javaClass.simpleName} (подъём $at)"
            emptyList()
        }
    }

    /** Задание ответа из строки ленты; null — строка не того вида (ключ чата, кольцо, место). */
    private fun jobOf(l: ChatLine): Job? {
        val chat = PersonKey(l.chat)
        val chatId = GateTelegram.chatId(chat) ?: return null
        val ring = AddressSorter.Ring.entries.firstOrNull { it.name == l.ring } ?: return null
        val place = Place.entries.firstOrNull { it.name == l.place } ?: return null
        val tallyKey = TgWords.chatRefOf(chat, l.thread)
        // Название группы в ленте не хранится: после комы группа названа ключом.
        tallies.putIfAbsent(tallyKey, (if (place == Place.DIRECT) l.name else l.chat) to AddressSorter.Tally())
        return Job(
            chatId = chatId, chat = l.chat, thread = l.thread, lineId = l.id, messageId = l.messageId,
            sender = PersonKey(l.sender), answer = AddressSorter.answer(ring), senderIsBot = l.isBot, atMs = l.at,
            place = place, chatTitle = null, tallyKey = tallyKey,
        )
    }

    private fun watchdogRefusal(app: Context): String? {
        val watchdog = ProcessObjects.get(app).watchdog
        return when {
            watchdog.zoneObservation().powerLastAtMs == null -> "нет показаний батареи"
            watchdog.zone.value != SafetyZone.COMFORT -> "зона ${watchdog.zone.value} — не «норма»"
            else -> watchdog.longRunBlockReason()
        }
    }

    private fun nightRunning() = AgentService.state.value is AgentService.RunState.Running

    /**
     * Почему модель сейчас не зовётся на ответ; null — можно. Одно место для
     * проверки за замком хода и перед каждым следующим проходом (AnswerLadder).
     */
    private fun haltReason(app: Context): String? = when {
        EmergencyStop.isActive() -> "аварийный стоп"
        nightRunning() -> "началась ночная работа"
        watchdogRefusal(app) != null -> "сторож: ${watchdogRefusal(app)}"
        !ProcessObjects.get(app).llmEngine.isLoaded -> "модель выгружена"
        else -> null
    }

    /** Идёт суд или шаги ночи, начатые самим агентом: они уступают телеграму. */
    private fun selfRunning() = (AgentService.state.value as? AgentService.RunState.Running)?.self == true

    /**
     * Дождаться, пока уступивший самостоятельный прогон кончится. Разбор по
     * кнопке не ждётся: он не уступает, и [ChatReply.decide] назовёт его.
     */
    private suspend fun awaitYield() {
        if (!selfRunning()) return
        withTimeoutOrNull(YIELD_WAIT_MS) { AgentService.state.first { it !is AgentService.RunState.Running } }
    }

    /** Один ответ; итог словами. */
    private suspend fun reply(app: Context, job: Job): String {
        awaitYield()
        val dao = ChatDatabase.getInstance(app).chatLineDao()
        val recent = dao.recent(job.chat, job.thread, RIBBON_READ).asReversed()
        val at = recent.indexOfLast { it.id == job.lineId }
        if (at < 0) return "не ответил — строки нет в ленте чата"
        val upTo = recent.subList(0, at + 1)
        val now = System.currentTimeMillis()
        val inputs = ChatReply.Inputs(
            answer = job.answer,
            emergencyStop = EmergencyStop.isActive(),
            watchdogRefusal = watchdogRefusal(app),
            consentValid = ChatPrefs.consent(app, now) != null,
            nightRunning = nightRunning(),
            ageMs = now - job.atMs,
            newer = dao.countAfter(job.chat, job.thread, job.lineId),
            senderIsBot = job.senderIsBot,
            answersSinceHuman = ChatReply.answersSinceHuman(upTo.dropLast(1).map { ChatReply.Turn(it.isAgent, it.isBot) }),
        )
        return when (val d = ChatReply.decide(inputs)) {
            is ChatReply.Decision.Silent -> "не отвечаю — ${d.why}"
            ChatReply.Decision.Ask -> {
                val failure = send(app, job, ChatReply.ASK_TEXT, ActionProvenance.CODE)
                if (failure != null) "не переспросил — $failure" else {
                    tallies.computeIfPresent(job.tallyKey) { _, v -> v.first to v.second.asked() }
                    "переспросил ${clock(System.currentTimeMillis())}"
                }
            }
            ChatReply.Decision.Answer -> answer(app, job, upTo)
        }
    }

    private suspend fun answer(app: Context, job: Job, upTo: List<ChatLine>): String {
        val objects = ProcessObjects.get(app)
        ensureModel(app, objects)?.let { return "не отвечаю — $it" }
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uroboros:chat").apply { setReferenceCounted(false) }
        wake.acquire(WAKE_MS)
        val generated = try {
            objects.turns.aside {
                // За замком — заново: пока ждали, мог начаться суд, сработать стоп,
                // нагреться телефон или выгрузиться модель по сигналу памяти (safety.MemoryWatch).
                haltReason(app)?.let { Generated(null, it) }
                    ?: ensureModel(app, objects)?.let { Generated(null, it) }
                    ?: generate(app, job, upTo)
            }
        } finally {
            if (wake.isHeld) wake.release()
        }
        generated.why?.let { return "не отвечаю — $it" + (generated.ladder?.let { l -> " · $l" } ?: "") }
        val text = generated.text ?: return "не отвечаю — ответа нет"
        tallies.computeIfPresent(job.tallyKey) { _, v -> v.first to v.second.read() }
        val count = decisions.getOrPut(job.tallyKey) { IntArray(2) }
        // Право промолчать (Silence): решила модель — в телеграм не уходит.
        Silence.reasonOf(text)?.let { why ->
            keepSilence(app, job, text.trimStart().lineSequence().first().trim(), why)
            count[1]++
            return "промолчал сам ${clock(System.currentTimeMillis())} — $why · промолчал ${count[1]} из ${count[0] + count[1]} за запуск службы"
        }
        send(app, job, text, ActionProvenance.MODEL_OUTPUT, generated.records)?.let { return "ответ не ушёл — $it" }
        count[0]++
        return ChatTurnMeter.answered(clock(System.currentTimeMillis()), generated.tookMs ?: 0L, text.length, generated.ladder)
    }

    /**
     * Строка молчания — в ленту чата своей строкой с кольцом [Silence.RING],
     * без отправки: на следующем ходе модель видит, что промолчала и почему
     * (Silence). Ответам ботам подряд (ChatReply.answersSinceHuman) она
     * считается ответом — в сторону молчания, где предел безопасен.
     */
    private suspend fun keepSilence(app: Context, job: Job, line: String, why: String) {
        val meId = me?.id ?: return
        ChatDatabase.getInstance(app).chatLineDao().insert(
            ChatLine(
                chat = job.chat, thread = job.thread, messageId = 0L,
                sender = GateTelegram.userKey(meId).id, name = "агент", signature = "", isBot = true, isAgent = true,
                text = line, ring = Silence.RING, reason = why, replyTo = job.sender.id,
                at = System.currentTimeMillis(), place = job.place.name,
            )
        )
    }

    /**
     * Итог генерации: текст или причина, сколько шла генерация (null — до неё
     * не дошло) и что было на лестнице ответа словами (ChatTurnMeter.ladderNote).
     */
    private class Generated(
        val text: String?,
        val why: String?,
        val tookMs: Long? = null,
        val ladder: String? = null,
        /** Тексты записей памяти, поданных модели в этот ход (см. [Recall]). */
        val records: List<String> = emptyList(),
    )

    /** Имена колец, строки которых модель читает (AddressSorter.modelReads), — для запросов к ленте чата. */
    private val readRings: List<String> = AddressSorter.Ring.entries.filter(AddressSorter::modelReads).map { it.name }

    private fun feedLine(l: ChatLine) = ChatFeed.Line(
        isAgent = l.isAgent,
        signature = l.signature,
        text = l.text,
        modelReads = l.isAgent || l.ring?.let { r ->
            AddressSorter.Ring.entries.firstOrNull { it.name == r }?.let(AddressSorter::modelReads)
        } == true,
        id = l.id,
        at = l.at,
        sender = l.sender,
    )

    private suspend fun generate(app: Context, job: Job, upTo: List<ChatLine>): Generated {
        val engine = ProcessObjects.get(app).llmEngine
        val owner = ChatPrefs.owner(app)
        val dao = ChatDatabase.getInstance(app).chatLineDao()
        val chatName = tallies[job.tallyKey]?.first ?: job.chat
        val hhmm: (Long) -> String = { clock(it) }

        // Словарь — для слов-тем нити. Его грузят экран и ночь; загружен — второй раз не грузится.
        withContext(Dispatchers.IO) { LemmaHolder.load { app.assets.open(LemmaHolder.ASSET) } }
        val dictionary = LemmaHolder.loaded

        // Окно — из читаемых строк до текущей; сетка — от начала чата (ChatFeed, «ГДЕ ВСТАЁТ НАЧАЛО»).
        val readRaw = dao.readBefore(job.chat, job.thread, job.lineId + 1, readRings, WINDOW_READ).asReversed()
        if (readRaw.lastOrNull()?.id != job.lineId) return Generated(null, "строки нет среди читаемых моделью")
        val offset = dao.readCostBefore(job.chat, job.thread, readRaw.first().id, readRings)
        // Разговор — окно не заходит за его начало (ChatFeed, «РАЗГОВОР»).
        val installedAt = installedAt(app)
        val window = ChatFeed.window(readRaw.map(::feedLine), offset, installedAt = installedAt)

        // Голова нити — из строк перед началом окна, хвост — из последних строк всех колец.
        val before = dao.readBefore(job.chat, job.thread, window.startId, readRings, ChatThread.HEAD_READ)
            .asReversed().map(::feedLine)
        val agentNames = ChatPrefs.names(app)
        val head = ChatThread.head(before, window.lines.first().at, dictionary, agentNames, installedAt)
        val previous = if (window.startsConversation) ChatThread.previous(before, dictionary, agentNames, installedAt) else null
        val tail = ChatThread.tail(upTo.map(::feedLine), dictionary, agentNames, installedAt)
        val moved = windowStarts.put(job.tallyKey, window.startId)?.let { it != window.startId }

        // Конец нити — ещё что я обещал в этом чате. Тела здесь нет: о нём
        // агент узнаёт, глянув на приборы (GlanceDesk).
        val now = System.currentTimeMillis()
        val promised = runCatching {
            val lines = dao.readSinceIn(job.chat, job.thread, now - Promises.TERM_MS, readRings).map(ChatPromises::lineOf)
            ChatPromises.ofChat(ChatPromises.find(lines, now, dictionary, owner?.key?.id, ChatFeed.selfLines(engine.wallFor())), job.tallyKey)
        }.getOrNull()
        val promisedText = promised?.let { ChatPromises.chatText(it, ::day) }
        val watchdog = ProcessObjects.get(app).watchdog
        val body = BodyLine.of(watchdog.power.value, watchdog.zone.value)
        val insist = ChatThread.insistence(upTo.map(::feedLine))
        // Где ещё я говорил с прошлой реплики здесь — только личке владельца (CrossChannel).
        val cross = crossFor(app, job, owner, upTo.last().at, dictionary, agentNames, installedAt)
        val tailBlock = listOfNotNull(cross.first, tail?.let(ChatThread::tailText), insist, promisedText).joinToString("\n")
        threads[job.tallyKey] = ChatThread.meterLine(chatName, window, moved, head, tail, dictionary != null, hhmm, previous = previous) +
            " · обещаний этого чата: ${promised?.size ?: "не прочитались"} · $body · ${cross.second}"

        // Подпись владельца — по окну: строка «где я» стоит первой и должна меняться только вместе с окном.
        val ownerSignature = owner?.let { o -> readRaw.lastOrNull { it.id >= window.startId && it.sender == o.key.id }?.signature }
        val place = ChatFeed.placeLine(job.place == Place.DIRECT, job.chatTitle, ownerSignature)
        val selfLines = ChatFeed.selfLines(engine.wallFor())
        val note = ChatFeed.prochnostNote(upTo.last().text, upTo.filter { it.isAgent }.map { it.text }, selfLines)
        val headText = head?.let { ChatThread.headText(it, hhmm) } ?: previous?.let { ChatThread.previousText(it, hhmm, ::day) }

        // Детектор порчи грузит и экран, но служба после комы поднимается без
        // экрана — без этой строки порча в чате молча не проверялась бы.
        // Загружен — второй раз не грузится.
        withContext(Dispatchers.IO) { WordDamageHolder.load { app.assets.open(WordDamageHolder.ASSET) } }

        // Записи памяти этого хода — по границе чата (ChatRecall). Не прочитались —
        // ход идёт без них, строка экрана говорит почему.
        val question = ChatFeed.question(window)
        val recall = recallFor(app, job, window, question, owner, dictionary, chatName, now)
        recalls[job.tallyKey] = recall.meter

        // Лента чата для лестницы ответа (AnswerLadder): запрос — окно чата,
        // ходы для мер перехвата — ответы агента в окне. Годного ответа нет —
        // уходит первый: скрыть ход в телеграме значит промолчать человеку
        // (решение владельца). Памяти это не трогает: свои ответы агента ложатся
        // только в ленту чата.
        val turnsOfWindow = ChatFeed.history(window)
        var chosen: String? = null
        var sentFirst = false
        val ribbon = object : AnswerLadder.Ribbon {
            override fun history() = turnsOfWindow
            override fun messages(without: Set<Int>) =
                ChatFeed.messages(ChatFeed.without(window, without), place, headText, tailBlock, note, recall.message)
            override fun stableMessages(without: Set<Int>) =
                if (without.isEmpty()) ChatFeed.stableMessages(messages(without), recall = recall.message != null) else null
            override fun close(answer: String, rejected: String?) {
                chosen = answer
            }
            override fun hide(reason: String, first: String, rejected: List<ConversationJournal.Rejected>) {
                chosen = first.substringBefore(Glance.CALL_OPEN).trimEnd()
                sentFirst = true
            }
        }

        // Числа обсчёта — первого прохода: в нём обсчитан сам чат, а следующие
        // проходы (доска, перехват) берут почти всё готовым. Читаются, когда
        // первый проход уже кончился — перед вторым или после хода, — под замком
        // хода, пока их не сменил чужой запрос (ChatTurnMeter).
        var metered = false
        var firstReached = false
        fun meterFirst() {
            if (metered) return
            metered = true
            val at = hhmm(System.currentTimeMillis())
            evals[job.tallyKey] = if (firstReached) {
                val b = runCatching { engine.getLastDecodeBreakdown() }.getOrNull()
                ChatTurnMeter.evalLine(chatName, at, b?.promptTokens ?: 0L, b?.promptUs ?: 0L, engine.promptReuseReport())
            } else ChatTurnMeter.noEvalLine(chatName, at)
            points[job.tallyKey] = "Снимок чата ($chatName, $at): перед ответом — ${engine.chatLiftReport ?: "не поднимался"}"
            engine.chatSaveReport?.let { (key, line) -> saves[key] = line }
        }
        val ladder = AnswerLadder(
            object : AnswerLadder.Engine {
                override fun generate(messages: List<Pair<String, String>>, dryAgainst: String?, stableMessages: Int?, first: Boolean): Flow<GenerationEvent> {
                    if (!first) meterFirst()
                    return engine.generateChatFlow(messages, ANSWER_TOKENS, job.tallyKey, stableMessages, dryAgainst).onEach { e ->
                        if (first && (e is GenerationEvent.Token || e is GenerationEvent.Metrics)) firstReached = true
                    }
                }
                override val lastEnd: GenerationEnd get() = engine.lastGenerationEnd
                override val lastDryFed: Int get() = engine.lastDryFed
                override val wallText: String? get() = engine.wallText
            },
            ANSWER_TOKENS,
        )

        // Доска чата — на момент взгляда; что на ней и почему — у GlanceDesk.
        val desk: suspend () -> String = {
            val records = runCatching {
                withContext(Dispatchers.IO) { MemoryDatabase.getInstance(app).stickerDao().count() }
            }.getOrNull()
            val w = ProcessObjects.get(app).watchdog
            GlanceDesk.of(
                GlanceDesk.Readings(
                    body = BodyLine.of(w.power.value, w.zone.value),
                    records = records,
                    speed = speeds[job.tallyKey],
                    promises = ChatPromises.deskLines(promised, ::day),
                ),
                GlanceDesk.Trust.OUTSIDE,
            )
        }

        val startedAt = System.currentTimeMillis()
        // Слово о приборах в вопросе — доска сразу (Glance, «ДОСКА ПО СЛОВУ ВОПРОСА»).
        val ran = ladder.run(ribbon, question, desk, halt = { haltReason(app) }, deskFirst = Glance.asksInstruments(question))
        meterFirst()
        val tookMs = System.currentTimeMillis() - startedAt
        val said = ChatTurnMeter.ladderNote(ran, sentFirst)
        ran.haltedBy?.let { return Generated(null, "лестница остановлена — $it", tookMs, said) }
        ran.failure?.let { return Generated(null, "движок: ${it.message ?: it.javaClass.simpleName}", tookMs, said) }
        // Первая попытка оборвана (сторож, сбой) — ответа нет: оборванный текст
        // показывает не то, что модель хотела сказать.
        if (ran.generationEnd != GenerationEnd.COMPLETED) return Generated(null, engine.getGenerationEndReport(), tookMs, said)
        ran.metrics?.let { speeds[job.tallyKey] = String.format(Locale.ROOT, "%.1f", it.tokensPerSecond) }
        val text = TgWords.cut(chosen.orEmpty())
        if (text.isEmpty()) {
            return Generated(null, if (ran.glanced) "глянул на приборы, после доски ответа нет" else "модель не выдала ни знака", tookMs, said)
        }
        return Generated(text, null, tookMs, said, recall.records)
    }

    /**
     * Записи памяти хода: сообщение агента для модели (null — подавать
     * нечего), строка экрана врат и тексты поданных записей — они ложатся в
     * ленту чатов рядом с ответом (llm.ChatLine.recall).
     */
    private class Recall(val message: String?, val meter: String, val records: List<String> = emptyList())

    /** Своя речь хода: найденное к вопросу, лучшее первым, и часть строки экрана врат о ней. */
    private class OwnFound(val said: List<OwnSpeech.Said>, val serviceDropped: Int, val asked: Boolean, val meter: (fed: Int) -> String)

    /**
     * Своя речь агента для этого хода — по той же границе чата, что записи
     * (gate.ChatOwnSpeech): ответы в чатах, а владельцу в личке ещё и
     * сказанное на экране (архив ленты и открытая лента — в окне чата модель
     * их не видит). Ищется на вопрос к агенту или просьбу вспомнить, как на
     * экране. Не прочиталась — ход идёт без неё, строка говорит почему.
     * Поправки на дрейф показаний (nav.Drift) здесь нет.
     */
    private suspend fun ownSpeechFor(
        app: Context,
        job: Job,
        window: ChatFeed.Window,
        scope: ChatRecall.Scope,
        topic: String,
        asked: Boolean,
        owner: ChatPrefs.Owner?,
    ): OwnFound {
        if (!asked) return OwnFound(emptyList(), 0, false) { ChatOwnSpeech.meter(false, null, 0, 0, 0, 0) }
        return try {
            val objects = ProcessObjects.get(app)
            val lines = ChatDatabase.getInstance(app).chatLineDao().readLast(readRings, ChatOwnSpeech.READ_LINES)
                .asReversed().map(ChatPromises::lineOf)
            val viewer = ChatOwnSpeech.Viewer(
                scope, job.tallyKey, job.sender.id, window.lines.filter { it.isAgent }.map { it.text }.toSet(),
            )
            val inChats = ChatOwnSpeech.turns(lines, viewer, owner?.key?.id, ChatFeed.selfLines(objects.llmEngine.wallFor()))
            val onScreen = if (scope != ChatRecall.Scope.OWNER) emptyList() else {
                val store = objects.turns.store
                val archive = store.readArchive().orEmpty().map { row ->
                    OwnSpeech.Turn(
                        answer = row.agentContent,
                        question = row.question,
                        records = store.recordTexts(row),
                        at = Coordinates.turnTime(row.at, row.question, row.archivedAt) { null }.at,
                        serviceBlocks = store.serviceBlocks(row),
                    )
                }
                val open = objects.turns.journal.history().map { turn ->
                    OwnSpeech.Turn(
                        answer = turn.agentContent,
                        question = turn.question,
                        records = turn.records.map { it.text },
                        at = turn.at,
                        serviceBlocks = EchoCheck.serviceBlocks(turn),
                    )
                }
                ChatOwnSpeech.screenTurns(archive + open)
            }
            val sifted = OwnSpeech.sift(onScreen + inChats)
            val found = OwnSpeech.search(sifted.said, topic)
            val fromChats = sifted.said.count { it.where != ChatOwnSpeech.ON_SCREEN }
            OwnFound(found, sifted.serviceDropped, true) { fed ->
                ChatOwnSpeech.meter(true, null, sifted.said.size, fromChats, found.size, fed)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            OwnFound(emptyList(), 0, true) { ChatOwnSpeech.meter(true, t.javaClass.simpleName, 0, 0, 0, 0) }
        }
    }

    /**
     * Строка между каналами для этого хода (CrossChannel): текст модели (null —
     * нечего или не положено) и строка прибора. Чужому чату ленты не читаются
     * вовсе. Сбой чтения ход не роняет: ход идёт без строки, прибор говорит почему.
     */
    private suspend fun crossFor(
        app: Context,
        job: Job,
        owner: ChatPrefs.Owner?,
        now: Long,
        dictionary: LemmaDictionary?,
        agentNames: List<String>,
        installedAt: Long?,
    ): Pair<String?, String> {
        val viewer = if (ChatRecall.scope(job.place == Place.DIRECT, owner?.key == job.sender) == ChatRecall.Scope.OWNER)
            CrossChannel.Viewer.OWNER_DM else CrossChannel.Viewer.OTHER
        if (viewer == CrossChannel.Viewer.OTHER) return null to CrossChannel.meter(CrossChannel.Result.NotAllowed, null, ::clock)
        return try {
            val objects = ProcessObjects.get(app)
            val rows = ChatDatabase.getInstance(app).chatLineDao().readLast(readRings, ChatOwnSpeech.READ_LINES).asReversed()
            val chats = CrossChannel.chatPlaces(rows, owner?.key?.id)
            val store = objects.turns.store
            val screen = CrossChannel.screenPlace(
                store.readArchive().orEmpty().map { Triple(it.question, it.agentContent, it.at) } +
                    objects.turns.journal.history().map { Triple(it.question, it.agentContent, it.at) },
            )
            val hereLastAt = chats.firstOrNull { it.key == job.tallyKey }?.lines
                ?.lastOrNull { !it.isAgent && it.id < job.lineId }?.at
            val result = CrossChannel.find(viewer, job.tallyKey, hereLastAt, now, chats + screen, dictionary, agentNames, installedAt)
            val text = (result as? CrossChannel.Result.Fed)?.let { CrossChannel.text(viewer, it.seen, now) }
            text to CrossChannel.meter(result, null, ::clock)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null to CrossChannel.meter(null, t.javaClass.simpleName, ::clock)
        }
    }

    /**
     * Что память даёт этому ходу — по границе чата ([ChatRecall]): владельцу
     * в личке — круг, как на экране (тот же вызов и те же подписи,
     * memory.RecallLines); остальным — узкий отбор из дозволенного. Сбой
     * чтения ход не роняет: ответ идёт без записей.
     *
     * Запись из прошлого разговора чата подписывается так (номера разговоров
     * для подписи: 0 — до начала нынешнего, 1 — нынешний): начало разговора —
     * как у окна (ChatFeed, «РАЗГОВОР»).
     *
     * Ход владельца считается обращением к памяти, как ход на экране: записи
     * получают касание и прогрев (HourglassMemory.getContextWithSummary), и
     * строка «Касания:» экрана показывает этот ход, если он был последним.
     */
    private suspend fun recallFor(
        app: Context,
        job: Job,
        window: ChatFeed.Window,
        question: String,
        owner: ChatPrefs.Owner?,
        dictionary: LemmaDictionary?,
        chatName: String,
        now: Long,
    ): Recall = try {
        val scope = ChatRecall.scope(job.place == Place.DIRECT, owner?.key == job.sender)
        val conversationStart = window.opened?.at ?: window.lines.first().at
        val shown = ChatRecall.shownTexts(window.lines, owner?.key?.id)
        val asked = window.lines.takeLastWhile { !it.isAgent }
        val earlier = window.lines.dropLast(asked.size).filter { !it.isAgent }
        val address = Coordinates.addressInRibbon(earlier.map { it.text to it.at }, question, now)
        // Своя речь — на вопрос к агенту или просьбу вспомнить (gate.ChatOwnSpeech).
        val recallPlan = RecallRequest.plan(question, earlier.map { it.text }, dictionary, address)
        val own = ownSpeechFor(
            app, job, window, scope, RecallRequest.topicText(question, recallPlan),
            address == Coordinates.Address.AGENT || recallPlan != null, owner,
        )
        fun ownLines(seated: Int) = own.said.take(seated).map {
            ProvenanceLabels.ownSpeechForModel(it.sentence, it.at, now, where = it.where)
        }
        // Портрет собеседника — первым из записей хода, как на экране (gate.ChatPortrait).
        val portrait = portraitFor(app, scope, job, question, owner, dictionary, now, conversationStart, chatName)
        if (scope == ChatRecall.Scope.OWNER) {
            val found = ProcessObjects.get(app).mediator.getContextWithSummary(
                purpose = RetrievalPurpose.ANSWERING_USER,
                query = question,
                limit = DolmenCircle.circleSeats(portrait.lines.size),
                recentQuestions = earlier.map { it.text },
                excludedTexts = shown + portrait.records,
                previousAnswer = window.lines.lastOrNull { it.isAgent }?.text,
                address = address,
                seats = DolmenCircle.circleSeats(portrait.lines.size),
                ownSpeechFound = if (own.asked) own.said.size else null,
                ownSpeechServiceDropped = own.serviceDropped,
            )
            val lines = found.stickers.map {
                RecallLines.of(it, address, RetellHolder.table, now, if (it.createdAt < conversationStart) 0 else 1, 1).text
            }
            val mine = ownLines(found.ownSpeechSeated)
            Recall(
                ChatPortrait.message(portrait, lines + mine),
                ChatRecall.ownerMeter(chatName, lines.size, found.circle) + " · " + own.meter(mine.size) + "\n" + portrait.meter,
                portrait.texts + found.stickers.map { it.content },
            )
        } else {
            val dao = MemoryDatabase.getInstance(app).stickerDao()
            val pool = withContext(Dispatchers.IO) {
                if (scope == ChatRecall.Scope.PERSON) dao.saidBy(job.sender.id, ChatRecall.POOL) else dao.saidIn(job.tallyKey, ChatRecall.POOL)
            }.filter { ChatRecall.visible(scope, it, job.sender.id, job.tallyKey) && it.content !in portrait.records }
            val mine = ownLines(ChatOwnSpeech.NARROW_SEATS)
            val picked = ChatRecall.pick(question, pool, dictionary, shown, ChatRecall.NARROW_SEATS - mine.size)
            val lines = picked.map {
                ChatRecall.narrowLine(it, scope, now, it.createdAt < conversationStart, owner?.key?.id, owner?.name)
            }
            Recall(
                ChatPortrait.message(portrait, lines + mine),
                ChatRecall.narrowMeter(chatName, scope, pool.size, lines.size) + " · " + own.meter(mine.size) + "\n" + portrait.meter,
                portrait.texts + picked.map { it.content },
            )
        }
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Recall(null, ChatRecall.failedMeter(chatName, t.javaClass.simpleName))
    }

    /**
     * Портрет собеседника этого хода (gate.ChatPortrait) — по границе чата.
     * Сбой чтения памяти ход не роняет: ход идёт без портрета, строка экрана
     * врат говорит почему.
     */
    private suspend fun portraitFor(
        app: Context,
        scope: ChatRecall.Scope,
        job: Job,
        question: String,
        owner: ChatPrefs.Owner?,
        dictionary: LemmaDictionary?,
        now: Long,
        conversationStart: Long,
        chatName: String,
    ): ChatPortrait.Fed {
        if (ChatPortrait.personOf(scope, job.sender.id) == null) {
            return ChatPortrait.of(scope, job.sender.id, emptyList(), null, null, question, null, null, emptyMap(), now, conversationStart, chatName)
        }
        return try {
            // Таблица разворота — её грузит экран; служба после комы поднимается без экрана.
            withContext(Dispatchers.IO) { RetellHolder.load { app.assets.open(RetellHolder.ASSET) } }
            val db = MemoryDatabase.getInstance(app)
            val (records, night) = withContext(Dispatchers.IO) {
                val all = db.stickerDao().getAll()
                all to FactsReader.read(db, all)
            }
            val ownerKey = owner?.key?.id
            val ownerName = if (scope == ChatRecall.Scope.OWNER) NameClaim.of(records, ownerKey = ownerKey).found?.name else null
            ChatPortrait.of(
                scope, job.sender.id, records, ownerKey, ownerName, question, dictionary, RetellHolder.loaded, night.marks,
                now, conversationStart, chatName, night.outdated,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            ChatPortrait.Fed(emptyList(), emptyList(), emptyList(), Portrait.Reason.NONE, false, null, "Портрет ($chatName): не посчитался — ${t.javaClass.simpleName}")
        }
    }

    /** Загрузить последнюю выбранную модель, если её нет; null — готово, иначе причина. */
    private suspend fun ensureModel(app: Context, objects: ProcessObjects.Held): String? {
        val engine = objects.llmEngine
        if (engine.isLoaded) return null
        val uri = ModelPrefs.lastModelUri(app) ?: return "модель не выбрана"
        if (uri == failedUri) return "модель не загрузилась — снова попробую после выбора модели или комы"
        val parsed = Uri.parse(uri)
        if (!engine.loadModelFromUri(parsed)) {
            failedUri = uri
            return "модель не загрузилась"
        }
        objects.loadedModelName = DocumentFile.fromSingleUri(app, parsed)?.name ?: parsed.lastPathSegment
        return null
    }

    /**
     * Отправить [text] ответом на сообщение задания: ворота с согласием, пауза
     * чата, канал, строка ленты. null — ушло; иначе причина. [records] —
     * тексты записей памяти, поданных модели в этот ход.
     */
    private suspend fun send(
        app: Context,
        job: Job,
        text: String,
        provenance: ActionProvenance,
        records: List<String> = emptyList(),
    ): String? {
        val consent = ChatPrefs.consent(app)
        val verdict = GatedAction.evaluate(
            app,
            ActionRequest(
                type = ActionType.SEND_MESSAGE,
                requestedBy = REQUESTED_BY,
                confirmedBy = consent?.id,
                provenance = provenance,
                crossesDeviceBoundary = true,
                isReversible = false,
            ),
            consent,
        )
        if (verdict.result != GateResult.ALLOW) return "ворота: ${verdict.reason}"
        val since = System.currentTimeMillis() - (lastSent[job.chatId] ?: 0L)
        if (since < SEND_GAP_MS) delay(SEND_GAP_MS - since)
        val sent = GateLink.sendText(job.chatId, text, job.messageId, job.thread)
        sent.failure?.let { return it }
        val now = System.currentTimeMillis()
        lastSent[job.chatId] = now
        val meId = me?.id ?: return null
        ChatDatabase.getInstance(app).chatLineDao().insert(
            ChatLine(
                chat = job.chat, thread = job.thread, messageId = sent.messageId ?: 0L,
                sender = GateTelegram.userKey(meId).id, name = "агент", signature = "", isBot = true, isAgent = true,
                text = text, ring = null, reason = null, replyTo = job.sender.id, at = now, place = job.place.name,
                recall = ChatOwnSpeech.pack(records),
            )
        )
        return null
    }

    // --- Прибор ---

    /** Собрать строки экрана заново. Зовётся после каждого события. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val out = ArrayList<String>()
        val d = direct.get()
        val g = group.get()
        out += if (d + g == 0) "Слова: сообщений не было (с запуска службы)"
        else "Слова: личка $d · группы $g · последнее ${lastAt?.let(::clock) ?: "?"}"
        reviveLine?.let { out += it }
        out += ChatPrefs.consent(app, now)?.let { "Отвечать в телеграме: разрешено без срока (снять — кнопка «Отвечать»)" }
            ?: "Отвечать в телеграме: не разрешено (кнопка «Отвечать» вверху справа)"
        out += ChatPrefs.owner(app)?.let { "Владелец в телеграме: ${it.name} (${it.key.id})" }
            ?: "Владелец в телеграме: не отмечен (кнопка «Мой тг» вверху справа)"
        val names = ChatPrefs.names(app)
        out += if (names.isEmpty()) "Имена агента: не объявлены — в группах по имени не сортирую (кнопка «Имена»)"
        else "Имена агента: ${names.joinToString(", ")}"
        me?.let { m ->
            out += when (m.readsAllGroupMessages) {
                true -> "Приватность в группах: выключена — вижу все сообщения"
                false -> "Приватность в группах: включена — вижу только обращённое (выключить в @BotFather: /setprivacy)"
                null -> "Приватность в группах: телеграм не сказал"
            }
        }
        // Перехват порчи в ответах чата (AnswerLadder) стоит на этом детекторе:
        // не загружен — порча не проверяется, и это видно здесь, а не молчит.
        out += WordDamageHolder.meterLine()
        for ((key, v) in tallies.entries.sortedBy { it.key }) {
            out += v.second.meterLine(v.first, names)
            answers[key]?.let { out += it }
            evals[key]?.let { out += it }
            threads[key]?.let { out += it }
            recalls[key]?.let { out += it }
            points[key]?.let { out += it }
            saves[key]?.let { out += "Снимок чата (${v.first}): последняя запись — $it" }
        }
        _status.value = out
    }
}
