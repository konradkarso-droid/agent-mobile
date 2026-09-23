package com.uroboros

import android.content.Context
import android.os.PowerManager
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
 * последующие — выход из комы, какой бы ни была её причина: причины отсюда не
 * видно, видно только, что жизнь прерывалась.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - не знает, сколько длилась кома: видно, когда агент поднялся, но не когда
 *    упал — упавший записать этого не успевает;
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

    private fun moment(millis: Long): String =
        SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(millis))

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "agent_life"
    private const val KEY_STARTS = "starts"
    private const val KEY_LAST_START_AT = "last_start_at"
}
