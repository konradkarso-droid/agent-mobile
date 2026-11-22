package com.uroboros.search

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.uroboros.memory.ActionProvenance
import com.uroboros.memory.ActionRequest
import com.uroboros.memory.ActionType
import com.uroboros.memory.StandingConsent
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

internal const val WEB_REFRESH_JOB_ID = 40713
private const val REFRESH_COMPONENT = "web-search/crawl/scheduled"
private const val OWNER = "владелец в приложении"

internal fun scheduledRefreshRequest() = ActionRequest(
    type = ActionType.NETWORK_CALL,
    requestedBy = REFRESH_COMPONENT,
    provenance = ActionProvenance.CODE,
    crossesDeviceBoundary = true,
    isReversible = false,
)

internal fun grantScheduledRefresh(now: Long) = StandingConsent.grantedByOwner(
    ActionType.NETWORK_CALL, REFRESH_COMPONENT, OWNER, now, StandingConsent.NO_TERM,
)

/**
 * ОС хранит расписание, приложение хранит отзываемое согласие. Для запуска
 * нужны оба. Пропавшую задачу восстанавливает только новая команда владельца.
 */
internal class WebRefreshSchedule(private val app: Context) {
    private val prefs = app.getSharedPreferences("web_auto_refresh", Context.MODE_PRIVATE)
    private val scheduler = app.getSystemService(JobScheduler::class.java)

    fun consent(now: Long = System.currentTimeMillis()): StandingConsent? {
        val at = prefs.getLong(GRANTED_AT, 0L)
        if (at <= 0) return null
        return StandingConsent.restored(
            ActionType.NETWORK_CALL, REFRESH_COMPONENT, OWNER, at, StandingConsent.NO_TERM, now,
        )
    }

    @Synchronized
    fun enable(grant: StandingConsent): SearchReply {
        if (!grant.openEnded || grant.grantedBy != OWNER ||
            !grant.covers(ActionType.NETWORK_CALL, REFRESH_COMPONENT, System.currentTimeMillis())
        ) return SearchReply("Согласие не покрывает фоновое обновление веб-индекса.")
        val service = scheduler ?: return SearchReply("Android не предоставил планировщик автообновления.")
        if (consent() != null && service.getPendingJob(WEB_REFRESH_JOB_ID) != null) return status()
        val job = JobInfo.Builder(WEB_REFRESH_JOB_ID, ComponentName(app, WebRefreshJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setPeriodic(TimeUnit.DAYS.toMillis(1))
            .setPersisted(true)
            .build()
        if (service.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            return SearchReply("Android не поставил автообновление в расписание.")
        }
        if (!prefs.edit().putLong(GRANTED_AT, grant.grantedAt).commit()) {
            service.cancel(WEB_REFRESH_JOB_ID)
            return SearchReply("Согласие на автообновление не удалось сохранить.")
        }
        return status()
    }

    @Synchronized
    fun disable(): SearchReply {
        val removed = prefs.edit().remove(GRANTED_AT).commit()
        scheduler?.cancel(WEB_REFRESH_JOB_ID)
        return if (removed) status() else SearchReply(
            "Задача автообновления снята. Не удалось записать отзыв согласия в хранилище.",
        )
    }

    @Synchronized
    fun status(): SearchReply {
        val line = when {
            consent() == null -> "Автообновление источников выключено."
            scheduler?.getPendingJob(WEB_REFRESH_JOB_ID) == null ->
                "Android не держит задачу автообновления. Его можно заново включить в приложении."
            else -> "Автообновление включено. Android запускает его примерно раз в сутки " +
                "при зарядке и сети без тарификации, когда хватает места и заряда."
        }
        val at = prefs.getLong(LAST_AT, 0L)
        if (at == 0L) return SearchReply("$line Фоновых проверок ещё не было.")
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
        return SearchReply("$line\nПоследний фоновый запуск $date. ${prefs.getString(LAST_RESULT, "").orEmpty()}")
    }

    @Synchronized
    fun record(grant: StandingConsent, result: String) {
        // Устаревшая работа после отзыва согласия не меняет состояние новой.
        if (consent()?.id != grant.id) return
        prefs.edit().putLong(LAST_AT, System.currentTimeMillis()).putString(LAST_RESULT, result).apply()
    }

    private companion object {
        const val GRANTED_AT = "granted_at"
        const val LAST_AT = "last_at"
        const val LAST_RESULT = "last_result"
    }
}
