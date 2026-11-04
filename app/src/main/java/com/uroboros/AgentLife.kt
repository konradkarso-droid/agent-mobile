package com.uroboros

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Прибор жизни агента: жив ли он сейчас, с какого момента и сколько раз
 * поднимался заново.
 *
 * ЗАЧЕМ. Агент живёт в службе ([AgentService]), и её смерть — кома: процесс
 * выгрузила система, убила прошивка, перезагрузился телефон, поставилась новая
 * сборка. Служба поднимается снова сама, и снаружи непрерывная жизнь и жизнь с
 * провалами выглядят одинаково — уведомление висит в обоих случаях. Различает
 * их только счёт подъёмов, и он хранится ВНЕ процесса, иначе умирал бы вместе
 * с тем, что считает.
 *
 * ПОДЪЁМ — ЭТО СОЗДАНИЕ СЛУЖБЫ. Первый подъём после установки — рождение, все
 * последующие — выход из комы, какой бы ни была её причина: счёт видит только,
 * что жизнь прерывалась. Когда и отчего — записи Android ([comas], ComaCause).
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - сам не знает, когда агент упал — упавший записать этого не успевает; это
 *    знает только Android, если сохранил ([comas]);
 *  - «Автозапуск» и ограничения батареи прошивки MIUI у системы не спросить;
 *    строка показывает только оптимизацию батареи самого Android, а настройки
 *    MIUI проверяются руками;
 *  - счёт живёт в настройках приложения: очистка данных приложения его
 *    обнуляет вместе со всем остальным.
 */
object AgentLife {

    /** Счёт подъёмов на момент чтения. */
    data class Record(
        /** Сколько раз служба создавалась с установки; 0 — ни разу. */
        val starts: Int,
        /** Когда поднялась в последний раз, мс; null — ни разу. */
        val lastStartAt: Long?,
    ) {
        /** Подъёмы после первого — выходы из комы. */
        val revivals: Int get() = if (starts > 0) starts - 1 else 0
    }

    /** Отметить подъём службы. Зовёт только сама служба, при создании. */
    fun recordStart(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        prefs.edit()
            .putInt(KEY_STARTS, prefs.getInt(KEY_STARTS, 0) + 1)
            .putLong(KEY_LAST_START_AT, now)
            .apply()
    }

    fun read(context: Context): Record {
        val prefs = prefs(context)
        val starts = prefs.getInt(KEY_STARTS, 0)
        return Record(
            starts = starts,
            lastStartAt = if (starts > 0) prefs.getLong(KEY_LAST_START_AT, 0L) else null,
        )
    }

    /**
     * Оптимизирует ли Android батарею приложения: true — да, то есть может
     * усыплять и выгружать; null — спросить не удалось.
     */
    fun batteryOptimized(context: Context): Boolean? = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName).not()
    } catch (t: Throwable) {
        null
    }

    /**
     * Строка для экрана. Отдельно от чтения, чтобы проверяться без Android.
     *
     * @param alive служба жива в этом процессе прямо сейчас;
     * @param failure почему последняя попытка поднять службу не удалась, если
     *        не удалась. Печатается, только когда служба не жива: у живой
     *        неудачный повторный вызов ничего не значит, а у мёртвой это и есть
     *        ответ, почему она мертва.
     */
    fun line(alive: Boolean, record: Record, batteryOptimized: Boolean?, failure: String?): String =
        buildString {
            when {
                alive -> append("Жив с ").append(record.lastStartAt?.let { moment(it) } ?: "?")
                failure != null -> append("Агент не поднялся: ").append(failure)
                else -> append("Агент не жив — служба не запущена")
            }
            append(" · подъёмов после комы: ").append(record.revivals)
            append(" · оптимизация батареи: ")
            append(
                when (batteryOptimized) {
                    true -> "вкл (может усыплять)"
                    false -> "выкл"
                    null -> "?"
                }
            )
        }

    /**
     * Прошлые комы по записям Android ([ComaCause]). Читается один раз за жизнь
     * процесса: концы, случившиеся до его подъёма, за его жизнь не меняются, а
     * своего конца процесс не увидит. Только главный процесс приложения.
     */
    fun comas(context: Context): ComaCause.Reading {
        comas?.let { return it }
        val read = readComas(context)
        // Сбой запомнить нельзя: следующий показ экрана спросит снова.
        if (read !is ComaCause.Reading.Failed) comas = read
        return read
    }

    @Volatile
    private var comas: ComaCause.Reading? = null

    /** Строки прошлых ком для экрана — со временем в той же записи, что «Жив с». */
    fun comaLines(context: Context): String = ComaCause.lines(comas(context), ::moment)

    private fun readComas(context: Context): ComaCause.Reading =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) ComaCause.Reading.TooOld else exitsOf(context)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitsOf(context: Context): ComaCause.Reading {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val name = context.packageName
            val list = am.getHistoricalProcessExitReasons(name, 0, 0)
                .filter { it.processName == name }
                .sortedByDescending { it.timestamp }
                .map { ComaCause.Exit(it.timestamp, kindOf(it.reason), it.status, it.description, it.rss) }
            ComaCause.Reading.Exits(list)
        } catch (t: Throwable) {
            ComaCause.Reading.Failed(t.javaClass.simpleName)
        }
    }

    /** Соответствие причин Android словам [ComaCause] — по именам, а не по числам. */
    private fun kindOf(reason: Int): ComaCause.Kind = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> ComaCause.Kind.EXIT_SELF
        ApplicationExitInfo.REASON_SIGNALED -> ComaCause.Kind.SIGNALED
        ApplicationExitInfo.REASON_LOW_MEMORY -> ComaCause.Kind.LOW_MEMORY
        ApplicationExitInfo.REASON_CRASH -> ComaCause.Kind.CRASH
        ApplicationExitInfo.REASON_CRASH_NATIVE -> ComaCause.Kind.CRASH_NATIVE
        ApplicationExitInfo.REASON_ANR -> ComaCause.Kind.ANR
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ComaCause.Kind.INITIALIZATION_FAILURE
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> ComaCause.Kind.PERMISSION_CHANGE
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ComaCause.Kind.EXCESSIVE_RESOURCE_USAGE
        ApplicationExitInfo.REASON_USER_REQUESTED -> ComaCause.Kind.USER_REQUESTED
        ApplicationExitInfo.REASON_USER_STOPPED -> ComaCause.Kind.USER_STOPPED
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> ComaCause.Kind.DEPENDENCY_DIED
        ApplicationExitInfo.REASON_FREEZER -> ComaCause.Kind.FREEZER
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> ComaCause.Kind.PACKAGE_STATE_CHANGE
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> ComaCause.Kind.PACKAGE_UPDATED
        ApplicationExitInfo.REASON_OTHER -> ComaCause.Kind.OTHER
        else -> ComaCause.Kind.UNKNOWN
    }

    private fun moment(millis: Long): String =
        SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(millis))

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "agent_life"
    private const val KEY_STARTS = "starts"
    private const val KEY_LAST_START_AT = "last_start_at"
}
