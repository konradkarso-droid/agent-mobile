package com.uroboros.safety

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.uroboros.AgentService
import com.uroboros.ProcessObjects
import com.uroboros.llm.EvalCores
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Поток отметок и запись простоев — исполнитель [StallMeter] (там — зачем,
 * как и чего не умеет). Запускает служба агента при создании; один поток на
 * процесс.
 *
 * ОТДЕЛЬНЫЙ ПОТОК, А НЕ КОРУТИНА. Корутины службы идут на главном потоке, а он
 * бывает занят нашим же кодом; отметка, опоздавшая из-за него, была бы
 * простоем, которого у процесса не было. Поток отметок спит и больше ничего не
 * ждёт.
 *
 * ПРОСТОИ — В НАСТРОЙКАХ, а не в памяти процесса: простой, после которого
 * процесс умер, иначе пропал бы вместе с ним.
 */
object StallWatch {

    /** Простои этого телефона: переезд их не везёт (move.MoveAndroid). */
    internal const val PREFS = "uroboros_stall_watch"
    private const val EPISODES = "episodes"
    private const val SINCE = "since"

    private val started = AtomicBoolean(false)

    /** Начать отметки; второй вызов в том же процессе ничего не делает. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val p = prefs(app)
        if (!p.contains(SINCE)) p.edit().putLong(SINCE, System.currentTimeMillis()).apply()
        Thread({ loop(app) }, "uroboros-stall-watch").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop(app: Context) {
        var prev = beat(app)
        while (true) {
            try {
                Thread.sleep(StallMeter.BEAT_MS)
            } catch (stop: InterruptedException) {
                return
            }
            // Сбой одной отметки — не смерть потока: следующая через BEAT_MS.
            try {
                val now = beat(app)
                StallMeter.episode(prev, now)?.let { record(app, it) }
                prev = now
            } catch (t: Throwable) {
                // Отметка не снялась: прежняя остаётся опорой. Простой, если он
                // был, посчитается от неё до следующей удачной.
            }
        }
    }

    private fun beat(app: Context) = StallMeter.Beat(
        wallMs = System.currentTimeMillis(),
        realtimeMs = SystemClock.elapsedRealtime(),
        uptimeMs = SystemClock.uptimeMillis(),
        cpuMs = Process.getElapsedCpuTime(),
        group = group(),
        working = working(app),
    )

    /** Группа процесса словом системы («top-app», «h-background»); пусто — не прочиталась. */
    private fun group(): String = runCatching {
        File("/proc/self/cpuset").readText().trim().trimStart('/')
    }.getOrDefault("")

    /** Шла ли работа: прогон суда или ночи, ход, занятый движок. Сбой чтения — «нет». */
    private fun working(app: Context): Boolean = runCatching {
        val objects = ProcessObjects.get(app)
        AgentService.state.value is AgentService.RunState.Running ||
            objects.turns.busy.value ||
            objects.llmEngine.activity.busy
    }.getOrDefault(false)

    private fun record(app: Context, e: StallMeter.Episode) {
        val p = prefs(app)
        val kept = (read(p.getString(EPISODES, null)) + e).sortedBy { it.fromMs }.takeLast(StallMeter.KEPT)
        p.edit().putString(EPISODES, kept.joinToString(";") { StallMeter.encode(it) }).apply()
    }

    private fun read(s: String?): List<StallMeter.Episode> =
        s?.split(';')?.mapNotNull { StallMeter.decode(it) }.orEmpty()

    /** Строки экрана ([StallMeter.meter]) по настройкам. */
    fun meterLine(context: Context): String {
        val p = prefs(context)
        val since = if (p.contains(SINCE)) p.getLong(SINCE, 0L) else null
        return StallMeter.meter(read(p.getString(EPISODES, null)), since, ::clock, EvalCores::where)
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clock(ms: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(ms))
}
