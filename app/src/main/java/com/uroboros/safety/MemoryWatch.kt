package com.uroboros.safety

import android.app.ActivityManager
import android.content.Context
import com.uroboros.AgentService
import com.uroboros.ProcessObjects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Опрос памяти телефона и выгрузка модели по сигналу — исполнитель решений
 * [MemoryPressure] (там — зачем, когда и чего не умеет). Живёт в службе
 * агента: цикл [run] и сигнал [onTrim] от неё же.
 *
 * ЗАМЕР НА ДИСК. Каждый замер ложится в настройки, и при подъёме процесса
 * последний замер прошлой жизни перекладывается в «перед смертью»
 * ([keepBeforeDeath]) — иначе первый же замер новой жизни стёр бы его. По нему
 * строка комы говорит, было ли перед ней «мало» (MemoryPressure.beforeComa).
 *
 * ВЫГРУЗКА — ПОД ЗАМКОМ ХОДА (ConversationTurns.aside): ход экрана и ход
 * чата не начнутся посреди неё. Ход, который поднял модель и ждал замка,
 * проверяет её заново уже за замком (gate.link.ChatLink). Только что
 * поднятую модель ([FRESH_MS]) не выгружает: её подняли под дело, которое
 * ещё не взяло замок.
 */
object MemoryWatch {

    /** Замеры памяти этого телефона: переезд их не везёт (move.MoveAndroid). */
    internal const val PREFS = "uroboros_memory_pressure"
    private const val LAST = "last"
    private const val BEFORE_DEATH = "before_death"
    private const val UNLOADS = "unloads"
    private const val HOLD = "hold"

    /** Сколько держать только что поднятую модель. Объявленное число, не подобранное. */
    const val FRESH_MS = 60_000L

    /** Сколько последних выгрузок хранить — на счёт за сутки хватает с запасом. */
    private const val UNLOADS_KEPT = 50

    /** Открыт ли экран приложения (MainActivity.onStart / onStop). */
    @Volatile var screenShown = false

    /** Модель выгружена по сигналу и экран её ещё не поднимал (MainActivity.onStart). */
    @Volatile var unloadedBySignal = false

    @Volatile private var trim: Int? = null
    @Volatile private var keptThisProcess = false

    /** Сигнал onTrimMemory от службы: копится до ближайшего замера. */
    fun onTrim(level: Int) {
        trim = maxOf(trim ?: 0, level)
    }

    /** Раз за процесс, до первого замера: замер прошлой жизни — в «перед смертью». */
    fun keepBeforeDeath(context: Context) {
        if (keptThisProcess) return
        keptThisProcess = true
        val p = prefs(context)
        p.getString(LAST, null)?.let { p.edit().putString(BEFORE_DEATH, it).apply() }
    }

    /** Замер перед прошлой смертью процесса; null — не было. */
    fun beforeDeath(context: Context): MemoryPressure.Reading? = parse(prefs(context).getString(BEFORE_DEATH, null))

    /** Строка экрана (MemoryPressure.meterLine) по настройкам. */
    fun meterLine(context: Context): String {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val unloads = MemoryPressure.today(times(p.getString(UNLOADS, null)), now)
        return MemoryPressure.meterLine(parse(p.getString(LAST, null)), unloads, p.getString(HOLD, null), ::clock)
    }

    /** Цикл опроса, пока жива служба. Сорвавшийся замер — строка, а не смерть цикла. */
    suspend fun run(context: Context) {
        val app = context.applicationContext
        keepBeforeDeath(app)
        while (true) {
            try {
                once(app)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                prefs(app).edit().putString(HOLD, "замер сорвался — ${t.javaClass.simpleName}").apply()
            }
            delay(MemoryPressure.POLL_MS)
        }
    }

    private suspend fun once(app: Context) {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val reading = MemoryPressure.Reading(mi.availMem, mi.threshold, mi.lowMemory, System.currentTimeMillis())
        val p = prefs(app)
        p.edit().putString(LAST, format(reading)).apply()
        val level = trim
        trim = null
        val objects = ProcessObjects.get(app)
        val engine = objects.llmEngine
        val busy = when {
            objects.turns.busy.value -> "ответ"
            AgentService.state.value is AgentService.RunState.Running -> "суд или ночь"
            System.currentTimeMillis() - engine.loadedAtMs < FRESH_MS -> "модель только что поднята"
            else -> null
        }
        when (val d = MemoryPressure.decide(reading, level, engine.isLoaded, busy, screenShown)) {
            is MemoryPressure.Decision.Hold -> p.edit().putString(HOLD, "${d.why} (${clock(reading.at)})").apply()
            is MemoryPressure.Decision.Unload -> {
                // За замком — заново: пока ждали, мог начаться суд или открыться экран.
                val done = objects.turns.aside {
                    if (screenShown || AgentService.state.value is AgentService.RunState.Running) false
                    else engine.unloadForMemory()
                }
                if (done) {
                    unloadedBySignal = true
                    val all = (times(p.getString(UNLOADS, null)) + reading.at).takeLast(UNLOADS_KEPT)
                    p.edit().putString(UNLOADS, all.joinToString(",")).remove(HOLD).apply()
                }
            }
            MemoryPressure.Decision.Nothing -> Unit
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun format(r: MemoryPressure.Reading) = "${r.availBytes},${r.thresholdBytes},${r.low},${r.at}"

    private fun parse(s: String?): MemoryPressure.Reading? = s?.split(',')?.takeIf { it.size == 4 }?.let {
        runCatching { MemoryPressure.Reading(it[0].toLong(), it[1].toLong(), it[2].toBoolean(), it[3].toLong()) }.getOrNull()
    }

    private fun times(s: String?): List<Long> = s?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()

    private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
}
