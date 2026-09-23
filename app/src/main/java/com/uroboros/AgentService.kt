package com.uroboros

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.uroboros.memory.EmergencyStop
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.dream.DreamRunner
import com.uroboros.memory.dream.NightStart
import com.uroboros.memory.dream.SleepDecision
import com.uroboros.memory.dream.SleepPressure
import com.uroboros.memory.judge.JudgeLauncher
import com.uroboros.safety.SafetyZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * САМА СЛУЖБА НАЧИНАЕТ ТОЛЬКО СОН. Раз в минуту бодрствования она спрашивает,
 * пора ли спать (правила и пороги — в [SleepDecision]), и если пора — проходит
 * ночь сама, с отметкой «уснул сам». Сон не проходит через ворота действий и
 * аварийным стопом не останавливается: он только читает записи и считает.
 * Разбор памяти судьёй по-прежнему приходит только командой с экрана; служба,
 * поднятая заново после комы, прерванный прогон не продолжает — Android отдаёт
 * ей пустую команду, и она просто живёт. Выключить тело может только человек:
 * «Остановить» в настройках приложения Android.
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
 *  - Остановка во время сна (первые миллисекунды прогона) не даёт отчёта: сон
 *    так короток, что попасть в него нажатием почти нельзя.
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
        }
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

    /** Текст уведомления, пока разбор не идёт: жизнь, сон, последний сон сам. */
    private fun livingText(): String =
        LIVING_TEXT + "\n" + sleepLine + (lastSelfSleep?.let { "\n$it" } ?: "")

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
            // Второй запуск поверх идущего прогона идущий не трогает: ни его
            // состояние, ни уведомление.
            running -> Unit
            intent?.action == ACTION_JUDGE -> startRun(intent)
            // Команда «жить», пустая команда подъёма после комы и любая
            // незнакомая: служба просто живёт, ничего не начиная.
            else -> Unit
        }
        return START_STICKY
    }

    /** Ночь целиком: сначала сон, потом — если условия позволяют — судья. */
    private fun startRun(intent: Intent) {
        val objects = ProcessObjects.get(applicationContext)
        val modelIdentity = intent.getStringExtra(EXTRA_MODEL) ?: "модель неизвестна"
        val budgetMs = intent.getLongExtra(EXTRA_BUDGET_MS, 0L)

        job = scope.launch {
            // Сон идёт без блокировки сна и без пометки "идёт разбор": это
            // миллисекунды счёта, а не прогон, который надо сторожить.
            val dreamed = nightLock.withLock {
                DreamRunner.run(MemoryDatabase.getInstance(applicationContext), NightStart.BUTTON)
            }

            val refusal = whyCannotStart(applicationContext)
            if (refusal != null) {
                _state.value = RunState.Finished(dreamed + "\n\n" + refusal)
                finish()
                return@launch
            }

            val startedAt = System.currentTimeMillis()
            _state.value = RunState.Running(startedAt, done = 0, lastProgressAt = null)
            working = true
            showProgress(startedAt, done = 0, lastAt = null)

            acquireWakeLock(budgetMs)
            val report = try {
                JudgeLauncher(applicationContext, objects.llmEngine)
                    .runAndReport(modelIdentity, budgetMs) { done ->
                        val now = System.currentTimeMillis()
                        _state.value = RunState.Running(startedAt, done, now)
                        showProgress(startedAt, done, now)
                    }
            } catch (cancelled: CancellationException) {
                STOPPED_REPORT
            } finally {
                releaseWakeLock()
            }
            _state.value = RunState.Finished(dreamed + "\n\n" + report)
            finish()
        }
    }

    override fun onDestroy() {
        _alive.value = false
        releaseWakeLock()
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
        data class Running(val startedAt: Long, val done: Int, val lastProgressAt: Long?) : RunState()
        data class Finished(val report: String) : RunState()
    }

    companion object {
        private const val ACTION_LIVE = "com.uroboros.action.LIVE"
        private const val ACTION_JUDGE = "com.uroboros.action.JUDGE"
        private const val ACTION_STOP = "com.uroboros.action.STOP"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_BUDGET_MS = "budget_ms"
        private const val CHANNEL_ID = "agent_work"
        private const val NOTIFICATION_ID = 1
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

        /**
         * Почему СУДЬЯ сейчас не может начать, словами для экрана; null — может.
         *
         * Единственное место этих условий, и они только о судье: сон проходит
         * и при отказе (см. шапку класса). Сторож, не приславший ни одного
         * показания батареи, считается неработающим: прогон без живой остановки
         * по нагреву не начинается, сомнение решается в сторону отказа.
         */
        fun whyCannotStart(context: Context): String? {
            val objects = ProcessObjects.get(context)
            return when {
                state.value is RunState.Running -> "Разбор уже идёт."
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
