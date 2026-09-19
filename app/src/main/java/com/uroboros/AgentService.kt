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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Служба переднего плана для долгих прогонов: пока она работает, Android не
 * выгружает процесс, а процессор не засыпает при погасшем экране.
 *
 * ДВЕ ЗАЩИТЫ, И ОНИ НЕ ЗАМЕНЯЮТ ДРУГ ДРУГА. Служба с уведомлением не даёт
 * системе убить процесс. Блокировка сна (WakeLock) не даёт уснуть процессору:
 * без неё процесс жив, уведомление висит, а вычисление стоит, потому что
 * телефон с погасшим экраном ушёл в глубокий сон. Каждая закрывает свою
 * смерть, и по отдельности они выглядят исправными.
 *
 * WakeLock берётся на время прогона, а не на жизнь службы, и отпускается в
 * finally. Держать процессор без работы значит сжигать заряд впустую.
 *
 * ЗАПУСКАЕТ ТОЛЬКО ЧЕЛОВЕК. Служба сама ничего не начинает: прогон приходит
 * командой с экрана. После гибели процесса служба не перезапускается
 * (START_NOT_STICKY) — перезапуск начал бы работу без человека.
 *
 * Условия запуска проверяются в одном месте, [whyCannotStart]: экран спрашивает
 * его же, чтобы показать отказ, и служба сверяется с ним ещё раз при старте.
 * Повторный запуск поверх идущего прогона ничего не меняет.
 *
 * Ход виден в уведомлении: сколько пар разобрано и когда была последняя. Если
 * время последней пары перестало двигаться, прогон стоит, хотя уведомление
 * висит; различить это по одному факту «уведомление есть» нельзя.
 *
 * ЧЕГО НЕ УМЕЕТ.
 *  - Не переживает перезагрузку телефона и не запускается по расписанию.
 *  - Не спасает от прошивки, которая убивает фоновые приложения сама: на MIUI
 *    приложению нужны «Автозапуск» и батарея «Без ограничений».
 *  - На Android 13 и новее без разрешения на уведомления работает, но в шторке
 *    не видна. Разрешение здесь не спрашивается.
 *  - Возит один вид работы — разбор памяти. Цикл TOTE по-прежнему живёт на
 *    экране и обрывается вместе с ним.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Текст уведомления сейчас; им же служба отвечает на каждый повторный запуск. */
    private var currentText = "Разбор памяти готовится"

    override fun onBind(intent: Intent?): IBinder? = null

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
                } else {
                    finish()
                }
            // Второй запуск поверх идущего прогона идущий не трогает: ни его
            // состояние, ни уведомление.
            running -> Unit
            else -> startJudgeRun(intent)
        }
        return START_NOT_STICKY
    }

    private fun startJudgeRun(intent: Intent?) {
        val refusal = whyCannotStart(applicationContext)
        if (refusal != null || intent?.action != ACTION_JUDGE) {
            _state.value = RunState.Finished(refusal ?: "Служба получила неизвестную команду.")
            finish()
            return
        }

        val objects = ProcessObjects.get(applicationContext)
        val modelIdentity = intent.getStringExtra(EXTRA_MODEL) ?: "модель неизвестна"
        val budgetMs = intent.getLongExtra(EXTRA_BUDGET_MS, 0L)
        val startedAt = System.currentTimeMillis()
        _state.value = RunState.Running(startedAt, done = 0, lastProgressAt = null)
        showProgress(startedAt, done = 0, lastAt = null)

        job = scope.launch {
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
            _state.value = RunState.Finished(report)
            finish()
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        // Служба уходит посреди прогона (её остановила система): экран не должен
        // навсегда остаться со словами «разбор идёт».
        if (_state.value is RunState.Running) _state.value = RunState.Finished(STOPPED_REPORT)
        scope.cancel()
        super.onDestroy()
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Агент разбирает память")
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
        private const val ACTION_JUDGE = "com.uroboros.action.JUDGE"
        private const val ACTION_STOP = "com.uroboros.action.STOP"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_BUDGET_MS = "budget_ms"
        private const val CHANNEL_ID = "agent_work"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_MARGIN_MS = 10L * 60 * 1000
        private const val STOPPED_REPORT =
            "Разбор остановлен до конца. Разобранное сохранено, остальное достанется следующему прогону."

        private val _state = MutableStateFlow<RunState>(RunState.Idle)
        val state: StateFlow<RunState> get() = _state

        /**
         * Почему прогон сейчас нельзя начать, словами для экрана; null — можно.
         *
         * Единственное место этих условий. Сторож, не приславший ни одного
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

        /** Запустить разбор памяти в службе. Проверку условий зовёт вызывающий. */
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
