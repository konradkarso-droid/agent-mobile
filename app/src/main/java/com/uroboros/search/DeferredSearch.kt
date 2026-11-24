package com.uroboros.search

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import java.time.ZonedDateTime
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Сохранённый разовый поиск и место, куда у него должен прийти ответ. */
internal data class DeferredSearch(
    val id: String,
    val query: String,
    val runAt: Long,
    val channel: SearchChannel,
    val chatId: Long? = null,
    val replyMessageId: Long? = null,
    val original: String,
)

/** Извлекает тему и расчётное время поручения из прямой реплики владельца. */
internal object DeferredSearchParser {
    private val relative = Regex(
        """через\s+(пол\s*часа|(?:одну|один|два|две|три|четыре|пять|десять|пятнадцать|двадцать|тридцать|[1-9][0-9]?)\s*(секунд[уы]?|минут[уы]?|час(?:а|ов)?|дн(?:я|ей)|день))""",
        RegexOption.IGNORE_CASE,
    )
    private val clockTime = Regex("(?:сегодня|завтра)?\\s*(?:в|к)\\s*(\\d{1,2})(?:[:.]([0-5]?\\d))?", RegexOption.IGNORE_CASE)

    fun parse(text: String, now: ZonedDateTime): DeferredSearchRequest? {
        val phrase = text.trim()
        val relativeMatch = relative.find(phrase)
        if (relativeMatch != null) {
            val amountText = relativeMatch.groupValues[1].replace(Regex("\\s+"), " ").lowercase()
            val unit = relativeMatch.groupValues[2].lowercase()
            val amount = when {
                amountText.contains("пол") -> 30L
                amountText.contains("одну") || amountText.contains("один") -> 1L
                amountText.contains("два") || amountText.contains("две") -> 2L
                amountText.contains("три") -> 3L
                amountText.contains("четыре") -> 4L
                amountText.contains("пять") -> 5L
                amountText.contains("десять") -> 10L
                amountText.contains("пятнадцать") -> 15L
                amountText.contains("двадцать") -> 20L
                amountText.contains("тридцать") -> 30L
                else -> Regex("\\d+").find(amountText)?.value?.toLongOrNull() ?: 1L
            }
            val millis = when {
                unit.startsWith("сек") -> amount * 1_000L
                unit.starts("мин") -> amount * 60_000L
                unit.starts("час") -> amount * 3_600_000L
                unit.starts("день") || unit.starts("дн") -> amount * 86_400_000L
                else -> return null
            }
            val runAt = now.toInstant().toEpochMilli() + millis
            val query = removeDelayAndCommand(phrase, relativeMatch.range)
            return if (query.isNotBlank()) DeferredSearchRequest(query, runAt) else null
        }

        val clock = clockTime.find(phrase) ?: return null
        val hour = clock.groupValues[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = clock.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        val tomorrow = Regex("^завтра|\\sзавтра", RegexOption.IGNORE_CASE).containsMatchIn(phrase)
        var run = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (tomorrow) run = run.plusDays(1)
        if (!tomorrow && run.toInstant().toEpochMilli() <= now.toInstant().toEpochMilli()) run = run.plusDays(1)
        val query = removeDelayAndCommand(phrase, clock.range)
        return if (query.isNotBlank()) DeferredSearchRequest(query, run.toInstant().toEpochMilli()) else null
    }

    private fun removeDelayAndCommand(text: String, delayRange: IntRange): String {
        var value = text.removeRange(delayRange).trim()
        value = value.replace(Regex("^(?:пожалуйста[,!:\\s]*)?(?:поищи(?:те)?|найди(?:те)?|посмотри(?:те)?|проверь(?:те)?|можешь\\s+(?:найти|поискать|посмотреть|проверить)|погугли(?:те)?)(?:\\s+в\\s+(?:интернете|сети)|онлайн)?[,\\s:—–-]*", RegexOption.IGNORE_CASE), "")
        value = value.replace(Regex("[,\\s:—–-]+$"), "").trim()
        return value
    }
}

internal data class DeferredSearchRequest(val query: String, val runAt: Long)

internal class DeferredSearchStore(private val app: Context) {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(request: DeferredSearch): Boolean {
        val rows = read().filterNot { it.id == request.id } + request
        val array = JSONArray()
        rows.forEach { row ->
            array.put(JSONObject()
                .put("id", row.id)
                .put("query", row.query)
                .put("runAt", row.runAt)
                .put("channel", row.channel.name)
                .put("chatId", row.chatId ?: JSONObject.NULL)
                .put("replyMessageId", row.replyMessageId ?: JSONObject.NULL)
                .put("original", row.original))
        }
        return prefs.edit().putString(ROWS, array.toString()).commit()
    }

    @Synchronized
    fun read(): List<DeferredSearch> = try {
        val array = JSONArray(prefs.getString(ROWS, "[]").orEmpty())
        (0 until array.length()).mapNotNull { index ->
            val row = array.getJSONObject(index)
            val channel = runCatching { SearchChannel.valueOf(row.getString("channel")) }.getOrNull() ?: return@mapNotNull null
            DeferredSearch(
                id = row.getString("id"),
                query = row.getString("query"),
                runAt = row.getLong("runAt"),
                channel = channel,
                chatId = (row.opt("chatId") as? Number)?.toLong(),
                replyMessageId = (row.opt("replyMessageId") as? Number)?.toLong(),
                original = row.optString("original"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val rows = read().filterNot { it.id == id }
        val array = JSONArray()
        rows.forEach { row ->
            array.put(JSONObject()
                .put("id", row.id).put("query", row.query).put("runAt", row.runAt)
                .put("channel", row.channel.name).put("chatId", row.chatId ?: JSONObject.NULL)
                .put("replyMessageId", row.replyMessageId ?: JSONObject.NULL).put("original", row.original))
        }
        return prefs.edit().putString(ROWS, array.toString()).commit()
    }

    private companion object {
        const val PREFS = "deferred_web_search"
        const val ROWS = "rows"
    }
}

internal class DeferredSearchScheduler(private val app: Context) {
    private val store = DeferredSearchStore(app)
    private val scheduler = app.getSystemService(JobScheduler::class.java)

    fun restore() {
        val now = System.currentTimeMillis()
        store.read().forEach { schedule(it, now) }
    }

    fun add(request: DeferredSearch): Boolean {
        if (!store.add(request)) return false
        val now = System.currentTimeMillis()
        return schedule(request, now)
    }

    private fun schedule(request: DeferredSearch, now: Long): Boolean {
        val service = scheduler ?: return false
        val delay = (request.runAt - now).coerceAtLeast(0L)
        val extras = PersistableBundle().apply { putString(EXTRA_ID, request.id) }
        val job = JobInfo.Builder(jobId(request.id), ComponentName(app, DeferredSearchJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay + 60_000L)
            .setPersisted(true)
            .setExtras(extras)
            .build()
        return service.schedule(job) == JobScheduler.RESULT_SUCCESS
    }

    fun jobId(id: String): Int = 50_000 + (UUID.nameUUIDFromBytes(id.toByteArray()).leastSignificantBits and 0x7fffffff) % 10_000

    private companion object {
        const val EXTRA_ID = "id"
    }
}
