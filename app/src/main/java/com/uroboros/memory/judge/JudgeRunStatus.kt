package com.uroboros.memory.judge

import android.content.Context

/** Начало и итог последнего разбора переживают перезапуск службы. */
internal object JudgeRunStatus {
    private const val PREFS = "last_judge_run"
    private const val STARTED = "started"
    private const val ENDED = "ended"
    private const val REASON = "reason"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(context: Context, at: Long) {
        prefs(context).edit().putLong(STARTED, at).remove(ENDED).remove(REASON).apply()
    }

    fun finish(context: Context, startedAt: Long, at: Long, reason: String) {
        val store = prefs(context)
        if (store.getLong(STARTED, 0L) != startedAt || store.contains(ENDED)) return
        store.edit().putLong(ENDED, at).putString(REASON, reason).apply()
    }

    fun line(context: Context, running: Boolean, clock: (Long) -> String): String? {
        val store = prefs(context)
        val start = store.getLong(STARTED, 0L).takeIf { it != 0L } ?: return null
        val end = store.getLong(ENDED, 0L).takeIf { it != 0L }
        val reason = store.getString(REASON, null)
        return when {
            end != null && reason != null -> "Последний суд · ${clock(end)}. $reason"
            running -> null
            else -> "Последний суд начался ${clock(start)} и прервался до записи итога. " +
                "Причина неизвестна. Сведения о перезапуске процесса — в разделе «Комы»."
        }
    }
}
