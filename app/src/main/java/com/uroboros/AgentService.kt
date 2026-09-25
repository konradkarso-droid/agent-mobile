package com.uroboros

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.documentfile.provider.DocumentFile
import com.uroboros.initiative.CuriositySource
import com.uroboros.initiative.InitiativeDecision
import com.uroboros.initiative.InitiativeSource
import com.uroboros.llm.ConversationTimes
import com.uroboros.llm.ConversationTurns
import com.uroboros.llm.GenerationEnd
import com.uroboros.memory.ActionProvenance
import com.uroboros.memory.ActionRequest
import com.uroboros.memory.ActionType
import com.uroboros.memory.EmergencyStop
import com.uroboros.memory.GateResult
import com.uroboros.memory.GatedAction
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.dream.DreamRunner
import com.uroboros.memory.dream.Conclusion
import com.uroboros.memory.dream.ConclusionStep
import com.uroboros.memory.dream.Mirror
import com.uroboros.memory.dream.MirrorStep
import com.uroboros.memory.dream.NightStart
import com.uroboros.memory.dream.SelfLine
import com.uroboros.memory.dream.SelfLineStep
import com.uroboros.memory.dream.SleepDecision
import com.uroboros.memory.dream.SleepPressure
import com.uroboros.memory.judge.JudgeLauncher
import com.uroboros.memory.judge.SelfJudgeDecision
import com.uroboros.safety.SafetyZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Тело агента: служба переднего плана, в которой агент живёт постоянно, и в ней
 * же долгие прогоны. Пока она работает, Android не выгружает процесс.
 *
 * ЖИВЁТ ВСЕГДА. Служба поднимается экраном при каждом его показе, после
 * загрузки телефона и после установки новой сборки ([BootReceiver]), а убитая
 * системой — поднимается снова сама (START_STICKY). Смерть службы — кома
 * агента; сколько раз она прерывалась, считает [AgentLife].
 *
 * САМА СЛУЖБА НАЧИНАЕТ СОН И СУД. Раз в минуту бодрствования она спрашивает,
 * пора ли спать (правила и пороги — в [SleepDecision]), и если пора — проходит
 * ночь сама, с отметкой «уснул сам». Сон не проходит через ворота действий и
 * аварийным стопом не останавливается: он только читает записи и считает.
 *
 * Следом, той же минутой, — пора ли судить (условия — в [SelfJudgeDecision]).
 * Суд начинается строго: на зарядке, в тишине, когда есть что судить. Модели
 * нет — служба загружает последнюю выбранную сама: после комы экран может не
 * открыться до утра, а суд нужен именно ночью. Сняли с зарядки — суд
 * останавливается; человек написал — суд уступает разговору ([yield]).
 * Разобранное не теряется: прогон продолжаемый, пара без вердикта достанется
 * следующему. Отчёт самостоятельного прогона — строка в уведомлении, а не
 * итог на экране: экран в это время показывает разговор.
 *
 * САМА СЛУЖБА ЗАГОВАРИВАЕТ ПЕРВОЙ. Третьей проверкой той же минуты — пора ли
 * написать владельцу самой (условия — в [InitiativeDecision], что сказать —
 * у источника, [InitiativeSource]). Модель грузится так же, как для суда;
 * при включённой настройке «После комы продолжать разговор сам» следом
 * поднимается лента ([ConversationTurns.resumeSaved]). Ход — тот же
 * [ConversationTurns.run], что у экрана; сообщение уходит уведомлением в
 * отдельный канал через ворота действий. Итог — строка «Первым:» в шторке
 * экрана ([initiativeLine]).
 *
 * Выключить тело может только человек: «Остановить» в настройках приложения
 * Android.
 *
 * ДВЕ ЗАЩИТЫ, И ОНИ НЕ ЗАМЕНЯЮТ ДРУГ ДРУГА. Служба с уведомлением не даёт
 * системе убить процесс. Блокировка сна (WakeLock) не даёт уснуть процессору:
 * без неё процесс жив, уведомление висит, а вычисление стоит, потому что
 * телефон с погасшим экраном ушёл в глубокий сон. Каждая закрывает свою
 * смерть, и по отдельности они выглядят исправными.
 *
 * WakeLock берётся на время прогона, а не на жизнь службы, и отпускается в
 * finally. Держать процессор без работы значит сжигать заряд впустую: живая
 * служба при погасшем экране спит вместе с процессором, и это верно.
 *
 * ДВЕ РАБОТЫ ЗА ОДИН ЗАПУСК, И УСЛОВИЯ У НИХ РАЗНЫЕ. Сначала ночной проход сна
 * (см. [DreamRunner]), потом разбор памяти судьёй. Сну не нужны ни модель, ни
 * сторож, ни отсутствие аварийного стопа: он только читает записи и считает.
 * Поэтому [whyCannotStart] спрашивается ПОСЛЕ сна и решает только судьбу
 * судьи — иначе незагруженная модель отменяла бы и сон, которому она не нужна.
 *
 * С кнопки за судьёй идёт третий шаг — строка о себе ([SelfLineStep]). Условия
 * у него те же, что у судьи, кроме «разбор уже идёт» ([whyModelCannotRun]); не
 * стартовал судья — нет и шага. Итог шага дописывается в строку ночи. Следом
 * за ним, при тех же условиях, — зеркало ([MirrorStep]), за зеркалом — выводы
 * из снов ([ConclusionStep]).
 *
 * Отказ судьи не теряется: он приходит на экран итогом прогона вместе с тем,
 * что приснилось. Экран поэтому сам условия судьи не спрашивает — иначе ночь
 * без судьи проходила бы вовсе без сна.
 *
 * Повторный запуск поверх идущего прогона ничего не меняет.
 *
 * Ход виден в уведомлении: сколько пар разобрано и когда была последняя. Если
 * время последней пары перестало двигаться, прогон стоит, хотя уведомление
 * висит; различить это по одному факту «уведомление есть» нельзя.
 *
 * ЧЕГО НЕ УМЕЕТ.
 *  - Не запускается по расписанию и не будит процессор: всё, что служба
 *    делает сама, случается, когда телефон проснулся по своей причине. Минута
 *    между проверками сна — минута бодрствования процессора, а не часов на
 *    стене: при погасшем экране проверка может ждать долго, и агент уснёт
 *    позже, чем мог бы. Это промах в безвредную сторону.
 *  - Не спасает от прошивки, которая убивает фоновые приложения сама: на MIUI
 *    приложению нужны «Автозапуск» и батарея «Без ограничений».
 *  - На Android 13 и новее без разрешения на уведомления работает, но в шторке
 *    не видна. Разрешение здесь не спрашивается.
 *  - Цикл TOTE по-прежнему живёт на экране и обрывается вместе с ним.
 *  - Суд, уступивший разговору, начнётся снова только после новой тишины —
 *    на паузе живого разговора он не начинается.
 *  - Остановка во время сна (первые миллисекунды прогона) не даёт отчёта: сон
 *    так короток, что попасть в него нажатием почти нельзя.
 *  - Пока идёт ход первым, проверки сна и суда ждут: все три идут одним
 *    циклом, одна за другой.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Текст уведомления сейчас; им же служба отвечает на каждый повторный запуск. */
    private var currentText = LIVING_TEXT

    /** Идёт разбор — от него зависит заголовок уведомления. */
    private var working = false

    /** Когда поднялось это тело: от него считается тишина, если генераций не было. */
    private var bodyStartedAt = 0L

    /** Итог последней проверки сна словами — для уведомления. */
    private var sleepLine = "Сон: первая проверка через минуту."

    /**
     * Последний сон, начатый самим агентом, словами. Живёт в памяти процесса;
     * после комы его здесь нет, но ночь с отметкой «уснул сам» видна в «Снах».
     */
    private var lastSelfSleep: String? = null

    /**
     * Одна ночь за раз. Ручной разбор и самостоятельный сон идут в одной
     * службе, и два прохода разом записали бы две ночи над одной памятью.
     */
    private val nightLock = Mutex()

    /** Итог последней проверки суда словами — для уведомления. */
    private var judgeLine = "Суд: первая проверка через минуту."

    /** Итог последнего самостоятельного прогона словами; после комы его нет. */
    private var lastSelfJudge: String? = null

    /** До какого момента суд отдыхает после прошлого прогона, мс. */
    private var restUntil = 0L

    /**
     * Отпечаток памяти, при котором судить было нечего. Пока память та же,
     * проверка очереди не повторяется: она перебирает пары и не бесплатна.
     */
    private var nothingToJudgeAt: String? = null

    /** Идущий прогон начат самим агентом, а не кнопкой. */
    private var selfRun = false

    /** Идущий прогон обрывается, чтобы уступить разговору. */
    private var yielding = false

    /** Почему самостоятельный прогон оборван не по своим причинам; null — не оборван. */
    private var stopNote: String? = null

    /** Повод заговорить первым. Источник пока один — см. [InitiativeSource]. */
    private val initiativeSource: InitiativeSource by lazy { CuriositySource(applicationContext) }

    private val conversationTimes by lazy { ConversationTimes(applicationContext) }

    /**
     * Модель, которая не загрузилась, чтобы написать первым. Пока выбрана она
     * же, служба ради сообщения грузить её снова не пытается: загрузка —
     * гигабайты и десятки секунд, и повторять её раз в минуту значило бы греть
     * телефон впустую. Живёт в памяти процесса: выбор другой модели или кома
     * снимают запрет.
     */
    private var initiativeFailedUri: String? = null

    /**
     * Приписка о доставке последнего сообщения первым: когда написано и что не
     * так с уведомлением. Живёт в памяти процесса — после комы сообщение в
     * ленте, а судьба уведомления уже не важна.
     */
    private var deliveryNote: Pair<Long, String>? = null

    /** Блокировка сна на время хода первым; у разбора своя, см. [acquireWakeLock]. */
    private var turnWakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Отметка подъёма — до всего остального: если дальше что-то упадёт,
        // счёт комы всё равно покажет, что служба пыталась жить.
        AgentLife.recordStart(applicationContext)
        bodyStartedAt = System.currentTimeMillis()
        _alive.value = true
        scope.launch { sleepLoop() }
    }

    /**
     * Проверка сна раз в минуту бодрствования, пока живо тело. Сорвавшаяся
     * проверка — строка в уведомлении, а не смерть цикла: следующая попытка
     * через минуту.
     */
    private suspend fun sleepLoop() {
        while (true) {
            delay(SLEEP_CHECK_MS)
            try {
                checkSleep()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                showSleepLine("Проверка сна сорвалась: ${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}.")
            }
            // Отдельной попыткой: сорвавшийся сон не должен отменять суд, и
            // наоборот.
            try {
                checkJudge()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                showJudgeLine("Проверка суда сорвалась: ${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}.")
            }
            try {
                checkInitiative()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _initiativeLine.value = "Первым: не пишу — проверка сорвалась: " +
                    "${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}"
            }
        }
    }

    /** Всё, из чего решается «написать первым»: входы, повод и прочитанные времена. */
    private class InitiativeCheck(
        val inputs: InitiativeDecision.Inputs,
        val offer: InitiativeSource.Offer,
        val times: ConversationTimes.Snapshot?,
    ) {
        val refusal: String? = InitiativeDecision.refusal(inputs)
    }

    private suspend fun initiativeCheck(): InitiativeCheck {
        val objects = ProcessObjects.get(applicationContext)
        val engine = objects.llmEngine
        val watchdog = objects.watchdog
        val turns = objects.turns
        val times = runCatching { conversationTimes.read() }
        // Диск спрашивается только при пустой ленте в памяти: непустая заведомо
        // поднята. Счёт не прочитался — лента считается неподнятой: сомнение
        // решается в сторону молчания.
        val journalNotRaised = turns.journal.isEmpty && (turns.store.counts()?.active ?: 1) > 0
        val offer = runCatching { initiativeSource.offer() }.getOrElse {
            InitiativeSource.Offer.Silent("не прочиталось, что сказать (${it.javaClass.simpleName})")
        }
        val snapshot = times.getOrNull()
        return InitiativeCheck(
            InitiativeDecision.Inputs(
                running = working || job?.isActive == true || nightLock.isLocked,
                emergencyStop = EmergencyStop.isActive(),
                powerKnown = watchdog.zoneObservation().powerLastAtMs != null,
                zoneNormal = watchdog.zone.value == SafetyZone.COMFORT,
                watchdogRefusal = watchdog.longRunBlockReason(),
                // Замок хода — тоже занятость: экран может вести ход или
                // поднимать ленту, ещё не дойдя до движка.
                engineBusy = engine.activity.busy || turns.busy.value,
                journalNotRaised = journalNotRaised,
                autoContinue = ConversationPrefs.autoContinue(applicationContext),
                timesUnreadable = times.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
                ownerReplyAt = snapshot?.ownerReplyAt,
                lastInitiativeAt = snapshot?.initiative?.at,
                now = System.currentTimeMillis(),
                sourceRefusal = (offer as? InitiativeSource.Offer.Silent)?.reason,
                modelChosen = ModelPrefs.lastModelUri(applicationContext) != null,
            ),
            offer,
            snapshot,
        )
    }

    /** Показать строку «Первым:» по итогу проверки. */
    private fun showInitiative(refusal: String?, times: ConversationTimes.Snapshot?) {
        val last = times?.initiative
        _initiativeLine.value = InitiativeDecision.meter(
            refusal = refusal,
            lastInitiativeAt = last?.at,
            what = last?.what,
            ownerReplyAt = times?.ownerReplyAt,
            note = deliveryNote?.takeIf { it.first == last?.at }?.second,
        )
    }

    /**
     * Пора ли написать первым. Порядок — как у суда: условия, загрузка модели,
     * подъём ленты, и после них условия спрашиваются заново: загрузка шла
     * десятки секунд, и за это время владелец мог заговорить.
     */
    private suspend fun checkInitiative() {
        val first = initiativeCheck()
        first.refusal?.let {
            showInitiative(it, first.times)
            return
        }
        val modelUri = ModelPrefs.lastModelUri(applicationContext) ?: return
        val objects = ProcessObjects.get(applicationContext)
        val engine = objects.llmEngine

        if (!engine.isLoaded) {
            if (modelUri == initiativeFailedUri) {
                showInitiative("модель не загрузилась — снова попробую после выбора модели или комы", first.times)
                return
            }
            _initiativeLine.value = "Первым: загружаю модель, чтобы написать"
            val uri = Uri.parse(modelUri)
            if (!engine.loadModelFromUri(uri)) {
                initiativeFailedUri = modelUri
                showInitiative("модель не загрузилась", first.times)
                return
            }
            objects.loadedModelName = DocumentFile.fromSingleUri(applicationContext, uri)?.name
                ?: uri.lastPathSegment
        }

        // Лента поднимается следом за моделью: без загруженной модели
        // сохранённую ленту не с чем сверить (отпечаток загрузки). Настройка
        // выключена — решает владелец, и неподнятая лента уже названа отказом.
        if (objects.turns.journal.isEmpty && ConversationPrefs.autoContinue(applicationContext)) {
            val resumed = objects.turns.resumeSaved()
            if (resumed is ConversationTurns.Resume.Refused) {
                showInitiative("разговор с диска не поднят: ${resumed.reason}", first.times)
                return
            }
        }

        val second = initiativeCheck()
        second.refusal?.let {
            showInitiative(it, second.times)
            return
        }
        val say = second.offer as? InitiativeSource.Offer.Say ?: return
        speak(say, objects)
    }

    /**
     * Ход первым: строка источника вместо реплики владельца, пустой вопрос,
     * ответ — сообщение агента. Ход — только через [ConversationTurns.run].
     *
     * Время реплики владельца здесь НЕ ставится: реплики владельца не было, и
     * отметка сняла бы ожидание ответа сразу после вопроса. Автозаписи в
     * память у служебной строки тоже нет: это не речь владельца.
     */
    private suspend fun speak(say: InitiativeSource.Offer.Say, objects: ProcessObjects.Held) {
        _initiativeLine.value = "Первым: пишу — ${say.what}"
        var sentAt: Long? = null
        var sentFailure: String? = null
        acquireTurnWakeLock()
        val outcome = try {
            objects.turns.run(
                content = say.line,
                question = "",
                records = emptyList(),
                afterSend = {
                    val at = System.currentTimeMillis()
                    sentAt = at
                    runCatching { say.onSent(at) }.onFailure { sentFailure = it.javaClass.simpleName }
                },
            )
        } finally {
            releaseTurnWakeLock()
        }

        val ran = when (outcome) {
            ConversationTurns.Outcome.JournalFull -> {
                showInitiative("лента заполнена — закрыть её может только владелец", null)
                return
            }
            is ConversationTurns.Outcome.TooLong -> {
                showInitiative("строка не влезает в остаток ленты: ${outcome.contentChars} зн. из ${outcome.maxChars}", null)
                return
            }
            is ConversationTurns.Outcome.Ran -> outcome
        }
        if (!ran.appended) {
            showInitiative(
                "модель не выдала ни знака" + (ran.failure?.let { " (сбой: ${it.javaClass.simpleName})" } ?: ""),
                null,
            )
            return
        }

        val at = sentAt ?: System.currentTimeMillis()
        conversationTimes.noteInitiative(at, say.what)
        val appendFailure = runCatching { say.onAppended() }.exceptionOrNull()?.javaClass?.simpleName
        // Оборванное сообщение в ленте остаётся, как у экрана, но уведомлением
        // не уходит: обрывок, присланный на телефон, читался бы как целое.
        val delivery = if (ran.failure == null && ran.generationEnd == GenerationEnd.COMPLETED) {
            deliver(ran.answer)
        } else {
            "уведомления нет: ${objects.llmEngine.getGenerationEndReport()} Сообщение в ленте"
        }
        deliveryNote = listOfNotNull(
            delivery,
            sentFailure?.let { "отметка «спрошен» не записана — $it" },
            appendFailure?.let { "подхват ответа не настроен — $it" },
        ).takeIf { it.isNotEmpty() }?.let { at to it.joinToString(" · ") }
        showInitiative(null, runCatching { conversationTimes.read() }.getOrNull())
    }

    /**
     * Уведомление с сообщением агента — через ворота действий, чтобы в журнале
     * действий остался след: и разрешённого, и отказанного.
     *
     * Поля запроса задаёт код, а не текст модели: сообщение локальное (граница
     * устройства не пересекается), показанное уведомление не отменить
     * (необратимо), текст — вывод модели. Аварийный стоп ворота проверяют
     * первыми.
     *
     * @return приписка для прибора, если уведомление не ушло; null — ушло.
     */
    private suspend fun deliver(text: String): String? {
        val verdict = GatedAction.evaluate(
            applicationContext,
            ActionRequest(
                type = ActionType.SEND_MESSAGE,
                requestedBy = "AgentService: пишет первым",
                provenance = ActionProvenance.MODEL_OUTPUT,
                crossesDeviceBoundary = false,
                isReversible = false,
            ),
        )
        if (verdict.result != GateResult.ALLOW) return "уведомление не отправлено: ${verdict.reason}"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return "уведомления приложения выключены в системе — сообщение в ленте"
        return runCatching { nm.notify(FIRST_NOTIFICATION_ID, firstNotification(text)) }
            .exceptionOrNull()?.let { "уведомление не отправлено: ${it.javaClass.simpleName}" }
    }

    /**
     * Своё уведомление в своём канале, а не строка в «Работе агента»: там
     * сообщение утонуло бы в строках о сне и суде. Нажатие открывает экран с
     * лентой, где сообщение уже лежит.
     */
    private fun firstNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(FIRST_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(FIRST_CHANNEL_ID, "Агент пишет первым", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            this, FIRST_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, FIRST_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Агент")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }

    /**
     * Ход при погасшем экране без блокировки сна стоял бы: процессор уснул бы
     * посреди выдачи. Срок — страховка на случай, если finally не отработает.
     */
    private fun acquireTurnWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        turnWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uroboros:agent-first").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MARGIN_MS)
        }
    }

    private fun releaseTurnWakeLock() {
        turnWakeLock?.let { if (it.isHeld) it.release() }
        turnWakeLock = null
    }

    /**
     * Пора ли судить самому. Порядок — от дешёвого к дорогому: условия без
     * чтения памяти ([SelfJudgeDecision]), затем «есть ли что судить» (чтение
     * базы и перебор пар), и только потом загрузка модели.
     */
    private suspend fun checkJudge() {
        val objects = ProcessObjects.get(applicationContext)
        val engine = objects.llmEngine
        val watchdog = objects.watchdog
        val now = System.currentTimeMillis()
        val quietSince = maxOf(engine.activity.lastEndedAtMs ?: 0L, bodyStartedAt)
        val modelUri = ModelPrefs.lastModelUri(applicationContext)

        SelfJudgeDecision.refusal(
            SelfJudgeDecision.Inputs(
                running = working || job?.isActive == true,
                emergencyStop = EmergencyStop.isActive(),
                powerKnown = watchdog.zoneObservation().powerLastAtMs != null,
                charging = watchdog.power.value.charging,
                watchdogRefusal = watchdog.longRunBlockReason(),
                engineBusy = engine.activity.busy,
                quietMs = now - quietSince,
                restLeftMs = restUntil - now,
                modelChosen = modelUri != null,
            )
        )?.let {
            showJudgeLine("Не сужу: $it.")
            return
        }
        modelUri ?: return

        // Отпечаток грубый: число записей, последний номер, сколько на проверке
        // и отвергнуто. Новая запись, выход из карантина и отказ его меняют;
        // правка текста записи — нет, и тогда суд подождёт следующей перемены.
        val records = MemoryDatabase.getInstance(applicationContext).stickerDao().getAll()
        val memoryPrint = "${records.size}:${records.maxOfOrNull { it.id }}:" +
            "${records.count { it.reviewPending }}:${records.count { it.rejectedAt != null }}"
        if (memoryPrint == nothingToJudgeAt) {
            showJudgeLine("Не сужу: судить нечего — память с прошлой проверки не менялась.")
            return
        }
        if (!JudgeLauncher(applicationContext, engine).hasPending(modelUri)) {
            nothingToJudgeAt = memoryPrint
            showJudgeLine("Не сужу: судить нечего — все пары разобраны.")
            return
        }

        if (!engine.isLoaded) {
            showJudgeLine("Загружаю модель, чтобы судить.")
            val uri = Uri.parse(modelUri)
            if (!engine.loadModelFromUri(uri)) {
                restUntil = System.currentTimeMillis() + SelfJudgeDecision.REST_AFTER_RUN_MS
                showJudgeLine("Не сужу: модель не загрузилась, следующая попытка через полчаса.")
                return
            }
            objects.loadedModelName = DocumentFile.fromSingleUri(applicationContext, uri)?.name
                ?: uri.lastPathSegment
        }
        // Загрузка шла десятки секунд: за это время могли снять с зарядки или
        // заговорить с агентом. Переспрашиваются условия, которые могли
        // измениться, — остальное проверит служба при старте прогона.
        if (!watchdog.power.value.charging || engine.activity.busy || job?.isActive == true) {
            showJudgeLine("Не сужу: пока грузилась модель, условия изменились.")
            return
        }
        startNight(modelUri, SelfJudgeDecision.RUN_BUDGET_MS, dreamFirst = false, self = true)
    }

    private suspend fun checkSleep() {
        if (working || job?.isActive == true) {
            showSleepLine("Не сплю: идёт разбор памяти.")
            return
        }
        val activity = ProcessObjects.get(applicationContext).llmEngine.activity
        val now = System.currentTimeMillis()
        val quietSince = maxOf(activity.lastEndedAtMs ?: 0L, bodyStartedAt)
        SleepDecision.gate(now, activity.busy, quietSince)?.let {
            showSleepLine(it)
            return
        }

        val db = MemoryDatabase.getInstance(applicationContext)
        val records = db.stickerDao().getAll()
        val night = db.dreamDao().lastNight()
        val rows = night?.let { db.dreamDao().ofNight(it.nightAt) } ?: emptyList()
        // Пробное плетение — вне главного потока: при большой памяти это
        // заметный счёт, а на главном он подвешивал бы экран.
        val pressure = withContext(Dispatchers.Default) { SleepPressure.measure(records, night, rows) }
        SleepDecision.decide(pressure.changed)?.let {
            showSleepLine(it)
            return
        }
        SleepDecision.waitReason(pressure.changed, now - quietSince)?.let {
            showSleepLine(it)
            return
        }

        val report = nightLock.withLock { DreamRunner.run(db, NightStart.SELF) }
        lastSelfSleep = "Уснул сам в ${clock(now)}: " + report.lineSequence().first()
        showSleepLine("Выспался.")
    }

    /** Показать итог проверки сна; во время разбора уведомление не трогается. */
    private fun showSleepLine(line: String) {
        sleepLine = line
        if (working) return
        val text = livingText()
        if (text == currentText) return
        currentText = text
        updateNotificationText()
    }

    /** Показать итог проверки суда; во время разбора уведомление не трогается. */
    private fun showJudgeLine(line: String) {
        judgeLine = line
        if (working) return
        val text = livingText()
        if (text == currentText) return
        currentText = text
        updateNotificationText()
    }

    /** Текст уведомления, пока разбор не идёт: жизнь, сон и суд, последние сами. */
    private fun livingText(): String =
        LIVING_TEXT + "\n" + sleepLine + (lastSelfSleep?.let { "\n$it" } ?: "") +
            "\n" + judgeLine + (lastSelfJudge?.let { "\n$it" } ?: "")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground обязан прозвучать сразу после каждого запуска службы,
        // иначе система считает её зависшей и роняет приложение, — поэтому
        // раньше любых проверок, в том числе раньше отказа.
        startForeground(NOTIFICATION_ID, notification(currentText))

        val running = job?.isActive == true
        when {
            intent?.action == ACTION_STOP ->
                if (running) {
                    currentText = "Останавливаю разбор"
                    updateNotificationText()
                    job?.cancel()
                }
            intent?.action == ACTION_YIELD ->
                if (running) {
                    yielding = true
                    currentText = "Уступаю разговору"
                    updateNotificationText()
                    job?.cancel()
                }
            // Второй запуск поверх идущего прогона идущий не трогает: ни его
            // состояние, ни уведомление.
            running -> Unit
            intent?.action == ACTION_JUDGE -> startNight(
                modelIdentity = intent.getStringExtra(EXTRA_MODEL) ?: "модель неизвестна",
                budgetMs = intent.getLongExtra(EXTRA_BUDGET_MS, 0L),
                dreamFirst = true,
                self = false,
            )
            // Команда «жить», пустая команда подъёма после комы и любая
            // незнакомая: служба просто живёт, ничего не начиная.
            else -> Unit
        }
        return START_STICKY
    }

    /**
     * Прогон судьи в службе. С кнопки ([dreamFirst]) — ночь целиком: сначала
     * сон, потом судья, если условия позволяют. Сам ([self]) — только судья:
     * сон у агента свой, по давлению (см. шапку класса).
     */
    private fun startNight(modelIdentity: String, budgetMs: Long, dreamFirst: Boolean, self: Boolean) {
        val objects = ProcessObjects.get(applicationContext)
        selfRun = self
        yielding = false
        stopNote = null

        job = scope.launch {
            val db = MemoryDatabase.getInstance(applicationContext)
            // Начало ночи задаётся здесь, а не внутри сна: по нему шаг «строка
            // о себе» дописывает свой итог в строку этой ночи.
            val nightAt = System.currentTimeMillis()
            // Сон идёт без блокировки сна и без пометки "идёт разбор": это
            // миллисекунды счёта, а не прогон, который надо сторожить.
            val dreamed = if (dreamFirst) {
                nightLock.withLock { DreamRunner.run(db, NightStart.BUTTON, nightAt) }
            } else {
                null
            }

            val refusal = whyCannotStart(applicationContext)
            if (refusal != null) {
                // Судья не стартовал — строки о себе тоже нет, по той же причине.
                val selfLine = if (dreamFirst) {
                    noteSelfLine(db, nightAt, SelfLine.notOfferedOutcome(refusal))
                } else {
                    null
                }
                // И зеркала нет — по той же причине.
                val mirror = if (dreamFirst) {
                    noteMirror(db, nightAt, Mirror.silentOutcome(refusal))
                } else {
                    null
                }
                // И выводов нет — по той же причине.
                val conclusions = if (dreamFirst) {
                    noteConclusions(db, nightAt, Conclusion.silentOutcome(refusal))
                } else {
                    null
                }
                end(listOfNotNull(dreamed, refusal, selfLine, mirror, conclusions).joinToString("\n\n"))
                return@launch
            }

            val startedAt = System.currentTimeMillis()
            _state.value = RunState.Running(startedAt, done = 0, lastProgressAt = null, self = self)
            working = true
            showProgress(startedAt, done = 0, lastAt = null)

            // Самостоятельный суд — только на зарядке, и не только на старте:
            // сняли с зарядки посреди прогона — прогон останавливается.
            val runner = coroutineContext[Job]
            val unplugWatch = if (self) {
                scope.launch {
                    objects.watchdog.power.first { !it.charging }
                    stopNote = "сняли с зарядки"
                    runner?.cancel()
                }
            } else {
                null
            }

            acquireWakeLock(budgetMs)
            val report = try {
                val judged = JudgeLauncher(applicationContext, objects.llmEngine)
                    .runAndReport(modelIdentity, budgetMs) { done ->
                        val now = System.currentTimeMillis()
                        _state.value = RunState.Running(startedAt, done, now, self)
                        showProgress(startedAt, done, now)
                    }
                // Строка о себе — только ночью по кнопке и только после судьи
                // (см. SelfLineStep). Условия модели переспрашиваются перед
                // вызовом: судья шёл долго. «Разбор уже идёт» не в счёт — идёт наш.
                val selfLine = if (dreamFirst) {
                    val outcome = SelfLineStep.run(db, objects.mediator, objects.llmEngine) {
                        whyModelCannotRun(applicationContext)
                    }
                    noteSelfLine(db, nightAt, outcome)
                } else {
                    null
                }
                // Зеркало — там же и при тех же условиях, после строки о себе
                // (см. MirrorStep). Лента читается здесь, на главном потоке:
                // ход ложится в неё на нём же (ConversationTurns.run), так что
                // копия снимается без замка хода и без ожидания.
                val mirror = if (dreamFirst) {
                    val outcome = MirrorStep.run(db, objects.llmEngine, nightAt, objects.turns.journal.history()) {
                        whyModelCannotRun(applicationContext)
                    }
                    noteMirror(db, nightAt, outcome)
                } else {
                    null
                }
                // Выводы из снов — там же и при тех же условиях, после зеркала
                // (см. ConclusionStep). Условия модели переспрашиваются перед
                // каждым вызовом внутри шага.
                val conclusions = if (dreamFirst) {
                    val outcome = ConclusionStep.run(db, objects.llmEngine, nightAt) {
                        whyModelCannotRun(applicationContext)
                    }
                    noteConclusions(db, nightAt, outcome)
                } else {
                    null
                }
                listOfNotNull(judged, selfLine, mirror, conclusions).joinToString("\n\n")
            } catch (cancelled: CancellationException) {
                STOPPED_REPORT
            } finally {
                unplugWatch?.cancel()
                releaseWakeLock()
            }
            end(listOfNotNull(dreamed, report).joinToString("\n\n"))
        }
    }

    /**
     * Дописать итог шага «строка о себе» в строку ночи [nightAt] и вернуть
     * его для отчёта. Если сон сорвался и строки ночи нет, обновление ничего
     * не тронет — итог останется только в отчёте. Сбой записи отчёт не
     * роняет, а называется в нём: иначе прибор молча показывал бы прежнюю ночь.
     */
    private suspend fun noteSelfLine(db: MemoryDatabase, nightAt: Long, outcome: String): String =
        try {
            db.dreamDao().setSelfLineOutcome(nightAt, outcome)
            outcome
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            "$outcome\n(в строку ночи не записано: ${t.javaClass.simpleName})"
        }

    /**
     * Дописать итог зеркала в строку ночи [nightAt] и вернуть его для отчёта —
     * тем же способом и с той же оговоркой о сбое, что [noteSelfLine].
     */
    private suspend fun noteMirror(db: MemoryDatabase, nightAt: Long, outcome: String): String =
        try {
            db.dreamDao().setMirrorOutcome(nightAt, outcome)
            outcome
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            "$outcome\n(в строку ночи не записано: ${t.javaClass.simpleName})"
        }

    /**
     * Дописать итог выводов в строку ночи [nightAt] и вернуть его для отчёта —
     * тем же способом и с той же оговоркой о сбое, что [noteSelfLine].
     */
    private suspend fun noteConclusions(db: MemoryDatabase, nightAt: Long, outcome: String): String =
        try {
            db.conclusionDao().setOutcome(nightAt, outcome)
            outcome
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            "$outcome\n(в строку ночи не записано: ${t.javaClass.simpleName})"
        }

    /**
     * Прогон кончился. Итог кнопочного прогона идёт на экран; самостоятельного
     * и уступившего разговору — строкой в уведомление: экран в это время
     * показывает разговор, и отчёт поверх него стёр бы его с глаз.
     */
    private fun end(report: String) {
        val now = System.currentTimeMillis()
        val headline = report.lineSequence().firstOrNull { it.isNotBlank() } ?: "без отчёта"
        when {
            yielding -> {
                _state.value = RunState.Idle
                lastSelfJudge = "Суд уступил разговору в ${clock(now)}: $headline"
            }
            selfRun -> {
                _state.value = RunState.Idle
                restUntil = now + SelfJudgeDecision.REST_AFTER_RUN_MS
                lastSelfJudge = "Судил сам, кончил в ${clock(now)}" +
                    (stopNote?.let { " ($it)" } ?: "") + ": $headline"
            }
            else -> _state.value = RunState.Finished(report)
        }
        yielding = false
        selfRun = false
        finish()
    }

    override fun onDestroy() {
        _alive.value = false
        releaseWakeLock()
        releaseTurnWakeLock()
        // Служба уходит посреди прогона (её остановила система): экран не должен
        // навсегда остаться со словами «разбор идёт».
        if (_state.value is RunState.Running) _state.value = RunState.Finished(STOPPED_REPORT)
        scope.cancel()
        super.onDestroy()
    }

    /** Прогон кончился: служба остаётся жить, уведомление возвращается к жизни. */
    private fun finish() {
        working = false
        currentText = livingText()
        updateNotificationText()
    }

    /**
     * Срок у блокировки — страховка, а не расписание: если finally почему-то не
     * отработает, процессор отпустится сам, спустя бюджет прогона и запас.
     */
    private fun acquireWakeLock(budgetMs: Long) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uroboros:agent-run").apply {
            setReferenceCounted(false)
            acquire(budgetMs + WAKE_LOCK_MARGIN_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun showProgress(startedAt: Long, done: Int, lastAt: Long?) {
        currentText = "Разобрано пар: $done · начат в ${clock(startedAt)}" +
            (lastAt?.let { " · последняя в ${clock(it)}" } ?: " · первая пара ещё идёт")
        updateNotificationText()
    }

    private fun updateNotificationText() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification(currentText))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Работа агента", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (working) "Агент разбирает память" else "Агент жив"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /** Состояние прогона для экрана. Одно на процесс, как и служба. */
    sealed class RunState {
        object Idle : RunState()
        /** [self] — начат самим агентом: экран его ход не показывает, только уведомление. */
        data class Running(
            val startedAt: Long,
            val done: Int,
            val lastProgressAt: Long?,
            val self: Boolean = false,
        ) : RunState()
        data class Finished(val report: String) : RunState()
    }

    companion object {
        private const val ACTION_LIVE = "com.uroboros.action.LIVE"
        private const val ACTION_JUDGE = "com.uroboros.action.JUDGE"
        private const val ACTION_STOP = "com.uroboros.action.STOP"
        private const val ACTION_YIELD = "com.uroboros.action.YIELD"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_BUDGET_MS = "budget_ms"
        private const val CHANNEL_ID = "agent_work"
        private const val NOTIFICATION_ID = 1
        private const val FIRST_CHANNEL_ID = "agent_first"
        private const val FIRST_NOTIFICATION_ID = 2
        private const val WAKE_LOCK_MARGIN_MS = 10L * 60 * 1000
        private const val LIVING_TEXT = "Живу. Разбор памяти не идёт."

        /** Между проверками сна — минута бодрствования, см. шапку класса. */
        private const val SLEEP_CHECK_MS = 60_000L
        private const val STOPPED_REPORT =
            "Разбор остановлен до конца. Разобранное сохранено, остальное достанется следующему прогону."

        /**
         * Жива ли служба в этом процессе. Верно только внутри процесса: после
         * комы процесс новый, и здесь снова false, пока служба не поднимется.
         */
        private val _alive = MutableStateFlow(false)
        val alive: StateFlow<Boolean> get() = _alive

        /**
         * Поднять тело агента. Повторный вызов у живой службы ничего не
         * меняет. Возвращает null — команда отдана, или слова, почему система
         * её не приняла: запуск службы переднего плана из фона Android
         * запрещает, и зовущий должен это видеть, а не молча остаться без тела.
         */
        fun live(context: Context): String? = try {
            context.startForegroundService(
                Intent(context, AgentService::class.java).setAction(ACTION_LIVE)
            )
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}"
        }

        private val _state = MutableStateFlow<RunState>(RunState.Idle)
        val state: StateFlow<RunState> get() = _state

        private val _initiativeLine = MutableStateFlow("Первым: не пишу — первая проверка через минуту")

        /**
         * Строка «Первым:» для шторки экрана — итог последней проверки. Верна
         * внутри процесса: после комы — снова «первая проверка».
         */
        val initiativeLine: StateFlow<String> get() = _initiativeLine

        /**
         * Почему СУДЬЯ сейчас не может начать, словами для экрана; null — может.
         *
         * Единственное место этих условий, и они только о судье: сон проходит
         * и при отказе (см. шапку класса). Сторож, не приславший ни одного
         * показания батареи, считается неработающим: прогон без живой остановки
         * по нагреву не начинается, сомнение решается в сторону отказа.
         */
        fun whyCannotStart(context: Context): String? =
            if (state.value is RunState.Running) "Разбор уже идёт." else whyModelCannotRun(context)

        /**
         * Условия [whyCannotStart] без «разбор уже идёт» — для шага внутри
         * идущего разбора (строка о себе после судьи): разбор идёт наш.
         */
        fun whyModelCannotRun(context: Context): String? {
            val objects = ProcessObjects.get(context)
            return when {
                EmergencyStop.isActive() -> "Взведён аварийный стоп — снимите его в красной полосе вверху."
                !objects.llmEngine.isLoaded -> "Сначала загрузите модель."
                objects.watchdog.zoneObservation().powerLastAtMs == null ->
                    "Сторож ещё не получил показаний батареи — без них разбор не начинается."
                objects.watchdog.zone.value == SafetyZone.CRITICAL ->
                    "Телефон в критической зоне (нагрев или низкий заряд) — разбор не начинается."
                else -> null
            }
        }

        /**
         * Запустить ночь в службе: сон, а за ним разбор памяти. Условия судьи
         * проверяет сама служба, вызывающему спрашивать их не нужно.
         */
        fun startJudge(context: Context, modelIdentity: String, budgetMs: Long) {
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_JUDGE)
                .putExtra(EXTRA_MODEL, modelIdentity)
                .putExtra(EXTRA_BUDGET_MS, budgetMs)
            context.startForegroundService(intent)
        }

        /**
         * Экран показал итог — снять его, чтобы он не всплывал при каждом
         * открытии экрана. Идущий прогон не трогает.
         */
        fun acknowledgeFinished() {
            if (_state.value is RunState.Finished) _state.value = RunState.Idle
        }

        /**
         * Человек написал агенту — идущий прогон уступает разговору: обрывается
         * без аварийного стопа, итог уходит строкой в уведомление, экран не
         * трогается. Разобранные пары остаются в хранилище. Дождаться конца
         * прогона — забота зовущего: см. [state].
         */
        fun yieldToConversation(context: Context) {
            if (state.value !is RunState.Running) return
            context.startForegroundService(
                Intent(context, AgentService::class.java).setAction(ACTION_YIELD)
            )
        }

        /** Оборвать идущий прогон. Разобранные пары остаются в хранилище. */
        fun stop(context: Context) {
            if (state.value !is RunState.Running) return
            context.startForegroundService(
                Intent(context, AgentService::class.java).setAction(ACTION_STOP)
            )
        }

        private fun clock(ms: Long): String =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    }
}
