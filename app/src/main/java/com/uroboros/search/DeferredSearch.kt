package com.uroboros.search

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import com.uroboros.ProcessObjects
import com.uroboros.gate.link.ChatLink
import com.uroboros.gate.link.GateLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject

internal data class DeferredSearchTelegram(
    val botId: Long,
    val chatId: Long,
    val messageId: Long,
    val consentId: String,
) {
    val requestId get() = "deferred:$botId:$chatId:$messageId"
}

/** Сохранённый разовый поиск и место, куда должен прийти ответ. */
internal data class DeferredSearch(
    val id: String,
    val query: String,
    val runAt: Long,
    val channel: SearchChannel,
    val telegram: DeferredSearchTelegram? = null,
    val reply: String? = null,
)

internal object DeferredSearchParser {
    private val relative = Regex(
        """через\s+(?:(\S+)\s+)?(секунд[уы]?|минут[уы]?|час(?:а|ов)?|день|дня|дней)""",
        RegexOption.IGNORE_CASE,
    )
    private val halfHour = Regex("""через\s+пол\s*часа""", RegexOption.IGNORE_CASE)
    private val clock = Regex(
        """(?:(сегодня|завтра)\s+)?(?:в|к)\s+(?:(\d{1,2}):(\d{2})|(\S+)\s+час(?:а|ов)?)""",
        RegexOption.IGNORE_CASE,
    )
    private val numbers = mapOf(
        "одну" to 1, "один" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "десять" to 10, "пятнадцать" to 15,
        "двадцать" to 20, "тридцать" to 30,
    )

    fun runAt(time: String, now: ZonedDateTime): Long? {
        if (halfHour.matches(time)) return now.toInstant().plusSeconds(1800).toEpochMilli()
        relative.matchEntire(time)?.let { match ->
            val amount = match.groupValues[1].takeIf { it.isNotEmpty() }?.let {
                number(it)?.takeIf { value -> value in 1..99 } ?: return null
            } ?: 1
            val seconds = when (match.groupValues[2].lowercase().first()) {
                'с' -> 1L
                'м' -> 60L
                'ч' -> 3600L
                else -> 86400L
            }
            return now.toInstant().plusSeconds(amount * seconds).toEpochMilli()
        }

        val match = clock.matchEntire(time) ?: return null
        val hour = number(match.groupValues[2].ifEmpty { match.groupValues[4] })?.takeIf { it in 0..23 }
            ?: return null
        val minute = match.groupValues[3].ifEmpty { "0" }.toInt().takeIf { it in 0..59 } ?: return null
        val day = match.groupValues[1].lowercase()
        var run = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (day == "завтра" || (day.isEmpty() && !run.isAfter(now))) run = run.plusDays(1)
        return run.takeIf { it.isAfter(now) }?.toInstant()?.toEpochMilli()
    }

    private fun number(value: String) = value.toIntOrNull() ?: numbers[value.lowercase()]
}

internal class DeferredSearchScheduler(private val app: Context) {
    private val prefs = app.getSharedPreferences("deferred_web_search", Context.MODE_PRIVATE)
    private val scheduler = app.getSystemService(JobScheduler::class.java)

    fun enqueue(request: DeferredSearch): Long? = synchronized(lock) {
        val service = scheduler ?: return null
        val rows = read()
        val existing = rows.firstOrNull { it.id == request.id }
        val pending = service.allPendingJobs
        val job = pending.firstOrNull { it.service.className == DeferredSearchJobService::class.java.name &&
            it.extras.getString(EXTRA_ID) == request.id }
        if (existing != null && job != null) return existing.runAt

        val row = existing ?: request
        val taken = pending.mapTo(HashSet()) { it.id }
        val jobId = job?.id ?: generateSequence(FIRST_JOB_ID) { it + 1 }.first { it !in taken }
        val info = JobInfo.Builder(jobId, ComponentName(app, DeferredSearchJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .setMinimumLatency((row.runAt - System.currentTimeMillis()).coerceAtLeast(0L))
            .setPersisted(true)
            .setExtras(PersistableBundle().apply { putString(EXTRA_ID, row.id) })
            .build()
        if (service.schedule(info) != JobScheduler.RESULT_SUCCESS) return null
        // JobService читает под тем же замком. После сбоя между шагами задача
        // без записи поручения завершится, а подтверждение ещё не было отправлено.
        if (!write(if (existing == null) rows + row else rows)) {
            service.cancel(jobId)
            if (existing == null) write(rows)
            return null
        }
        row.runAt
    }

    fun get(id: String): DeferredSearch? = synchronized(lock) { read().firstOrNull { it.id == id } }

    fun saveReply(id: String, reply: String): Boolean = synchronized(lock) {
        write(read().map { if (it.id == id) it.copy(reply = reply) else it })
    }

    fun remove(id: String): Boolean = synchronized(lock) { write(read().filterNot { it.id == id }) }

    private fun read(): List<DeferredSearch> {
        val array = JSONArray(prefs.getString(ROWS, "[]").orEmpty())
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val row = array.getJSONObject(index)
                val bot = (row.opt("botId") as? Number)?.toLong()
                val chat = (row.opt("chatId") as? Number)?.toLong()
                val message = (row.opt("replyMessageId") as? Number)?.toLong()
                val consent = row.optString("consentId")
                val telegram = if (bot != null && chat != null && message != null && consent.isNotEmpty()) {
                    DeferredSearchTelegram(bot, chat, message, consent)
                } else null
                DeferredSearch(
                    row.getString("id"), row.getString("query"), row.getLong("runAt"),
                    SearchChannel.valueOf(row.getString("channel")), telegram, row.opt("reply") as? String,
                )
            }.getOrNull()
        }
    }

    private fun write(rows: List<DeferredSearch>): Boolean {
        val array = JSONArray()
        rows.forEach { row ->
            array.put(JSONObject()
                .put("id", row.id).put("query", row.query).put("runAt", row.runAt)
                .put("channel", row.channel.name).put("reply", row.reply)
                .put("botId", row.telegram?.botId).put("chatId", row.telegram?.chatId)
                .put("replyMessageId", row.telegram?.messageId).put("consentId", row.telegram?.consentId))
        }
        return prefs.edit().putString(ROWS, array.toString()).commit()
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val ROWS = "rows"
        private const val FIRST_JOB_ID = 50_000
        private val lock = Any()
    }
}

/** Android запускает поиск по готовности сети и батареи после указанного срока. */
internal class DeferredSearchJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString(DeferredSearchScheduler.EXTRA_ID) ?: return false
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                retry = withContext(Dispatchers.IO) { execute(id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Отложенный поиск остановился: " + failure.javaClass.simpleName)
                GateLink.note("Отложенный поиск: работа прервана, причина в журнале Android")
            } finally {
                if (running[params.jobId] === coroutineContext[Job]) {
                    running.remove(params.jobId)
                    jobFinished(params, retry)
                }
            }
        }
        running[params.jobId] = task
        task.start()
        return true
    }

    private suspend fun execute(id: String): Boolean {
        val app = applicationContext
        val scheduler = DeferredSearchScheduler(app)
        val request = scheduler.get(id) ?: return false
        if (System.currentTimeMillis() < request.runAt) return true
        val search = ProcessObjects.get(app).search
        if (request.channel == SearchChannel.APP) {
            if (!search.runDeferred(request).historySaved) return true
            return !scheduler.remove(id)
        }
        val telegram = request.telegram
        if (telegram == null || !ChatLink.deferredSearchAllowed(app, telegram) || !search.deferredSearchAllowed()) {
            GateLink.note("Отложенный поиск отменён: владелец, разрешение или состояние устройства изменились")
            return !scheduler.remove(id)
        }
        if (GateLink.activeBotId() != telegram.botId) return retryDelivery(scheduler, request)

        val text = request.reply ?: run {
            val result = search.runDeferred(request) {
                ChatLink.deferredSearchAllowed(app, telegram) && GateLink.activeBotId() == telegram.botId
            }
            if (!result.historySaved || !scheduler.saveReply(id, result.text)) return true
            result.text
        }
        if (!ChatLink.deferredSearchAllowed(app, telegram) || !search.deferredSearchAllowed()) {
            GateLink.note("Отложенный поиск: результат остался в истории, отправка запрещена")
            return !scheduler.remove(id)
        }
        val sent = ChatLink.sendDeferredSearch(app, telegram, text, search::deferredSearchAllowed)
        return if (sent.failure == null) !scheduler.remove(id)
        else retryDelivery(scheduler, request, sent.failure)
    }

    private fun retryDelivery(
        scheduler: DeferredSearchScheduler, request: DeferredSearch, reason: String = "бот пока недоступен",
    ): Boolean {
        // При закрытом канале повторяем ограниченное время; выдача, если она
        // уже была, остаётся в истории поиска и при повторе сети не пересчитывается.
        val retry = System.currentTimeMillis() - request.runAt < TELEGRAM_RETRY_MS
        GateLink.note("Отложенный поиск: $reason. " +
            if (retry) "Android попробует снова." else "Срок повторных попыток истёк.")
        return retry || !scheduler.remove(request.id)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val task = running.remove(params.jobId) ?: return false
        task.cancel()
        return true
    }

    override fun onDestroy() {
        running.clear()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DeferredSearchJob"
        // Объявленное число: срок повторных попыток доставки в Telegram.
        const val TELEGRAM_RETRY_MS = 24 * 60 * 60_000L
    }
}
