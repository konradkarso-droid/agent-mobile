package com.uroboros.search

import android.content.Context
import com.uroboros.memory.ActionProvenance
import com.uroboros.memory.ActionRequest
import com.uroboros.memory.ActionType
import com.uroboros.memory.EmergencyStop
import com.uroboros.memory.GateResult
import com.uroboros.memory.GatedAction
import com.uroboros.memory.nav.LemmaHolder
import com.uroboros.safety.DeviceSafetyWatchdog
import com.uroboros.safety.SafetyZone
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal class SearchPlatform(
    private val app: Context,
    private val watchdog: DeviceSafetyWatchdog,
    private val processScope: CoroutineScope,
    infer: suspend (String, String) -> String?,
) {
    private val preferences = app.getSharedPreferences("web_search", Context.MODE_PRIVATE)
    private val index = LocalWebIndex(app)
    private val crawler = WebCrawler(index)
    private val reader = PageReader(infer)

    private fun blocked() = EmergencyStop.isActive() || watchdog.zone.value == SafetyZone.CRITICAL

    private val engine = WebSearch(
        history = object : SearchHistory {
            override fun read(): List<SearchEntry> {
                val entries = JSONArray(preferences.getString(HISTORY, "[]"))
                return (0 until entries.length()).map { index ->
                    val item = entries.getJSONObject(index)
                    SearchEntry(
                        id = item.getString("id"),
                        requestId = item.getString("request"),
                        query = item.getString("query"),
                        channel = SearchChannel.valueOf(item.getString("channel")),
                        at = item.getLong("at"),
                        status = SearchStatus.valueOf(item.getString("status")),
                        count = item.getInt("count"),
                        refresh = item.optBoolean("refresh"),
                    )
                }
            }

            override fun write(entries: List<SearchEntry>): Boolean {
                val rows = JSONArray()
                for (entry in entries) rows.put(JSONObject()
                    .put("id", entry.id)
                    .put("request", entry.requestId)
                    .put("query", entry.query)
                    .put("channel", entry.channel.name)
                    .put("at", entry.at)
                    .put("status", entry.status.name)
                    .put("count", entry.count)
                    .put("refresh", entry.refresh))
                return preferences.edit().putString(HISTORY, rows.toString()).commit()
            }
        },
        stopped = combine(EmergencyStop.active, watchdog.zone) { stop, zone -> stop || zone == SafetyZone.CRITICAL },
        blocked = ::blocked,
        available = { true },
        authorize = { true }, // Чтение индекса локально. Обход отдельно проходит Врата.
        provider = SearchProvider { find(it) },
        requestTimeoutMs = 125_000,
    )

    val state get() = engine.state

    suspend fun fromApp(command: SearchCommand): SearchReply =
        processScope.async(Dispatchers.IO) { perform(command, SearchChannel.APP) }.await()

    suspend fun fromTelegram(
        command: SearchCommand,
        messageId: String,
        stillOwner: () -> Boolean,
        onAccepted: () -> Unit,
    ): SearchReply = withContext(Dispatchers.IO) {
        perform(command, SearchChannel.TELEGRAM, messageId, stillOwner, onAccepted)
    }

    suspend fun snapshot(): SearchView = withContext(Dispatchers.IO) { engine.snapshot() }

    private suspend fun perform(
        command: SearchCommand, channel: SearchChannel, requestId: String = UUID.randomUUID().toString(),
        stillOwner: () -> Boolean = { true }, onAccepted: () -> Unit = {},
    ): SearchReply {
        val phrase = when (command) {
            is SearchCommand.Find -> command.query
            SearchCommand.Refresh -> "Обновить источники"
            else -> error("Not a search operation")
        }
        return engine.search(phrase, channel, requestId, stillOwner, onAccepted, refresh = command == SearchCommand.Refresh) {
            val allowed = { stillOwner() && !blocked() }
            if (command == SearchCommand.Refresh) {
                refresh(channel, emptyList(), allowed, forceSeeds = true)
            } else {
                val local = find(phrase)
                if (local.hits.isNotEmpty() || !allowed()) local else {
                    val update = refresh(channel, searchTerms(phrase, LemmaHolder.loaded), allowed)
                    val result = when {
                        update.status == SearchStatus.UPDATED && allowed() -> find(phrase)
                        index.status().pages > 0 -> local.copy(detail = update.detail ?: local.detail)
                        else -> update
                    }
                    if (result.hits.isNotEmpty() || !allowed() || index.status().pages == 0) result else {
                        val alternative = withTimeoutOrNull(10_000) { reader.alternative(phrase) }
                        if (alternative == null || !allowed()) result
                        else find(phrase, alternative).takeIf { it.hits.isNotEmpty() } ?: result
                    }
                }
            }
        }
    }

    private suspend fun refresh(
        channel: SearchChannel, terms: List<String>, allowed: () -> Boolean, forceSeeds: Boolean = false,
    ): SearchPage {
        val verdict = GatedAction.evaluate(app, ActionRequest(
            type = ActionType.NETWORK_CALL, requestedBy = "web-search/crawl/" + channel.name,
            provenance = ActionProvenance.USER, crossesDeviceBoundary = true, isReversible = false,
        ))
        if (!allowed() || verdict.result != GateResult.ALLOW) return SearchPage(emptyList(), status = SearchStatus.DENIED)
        val result = crawler.refresh(terms, allowed, forceSeeds)
        val status = when {
            result.loaded > 0 -> SearchStatus.UPDATED
            result.attempted > 0 -> SearchStatus.CRAWL_FAILED
            result.status.pages == 0 -> SearchStatus.INDEX_EMPTY
            else -> SearchStatus.UPDATED
        }
        return SearchPage(emptyList(), status = status, detail = "${indexLine(result.status)} " +
            when {
                result.loaded > 0 -> "Прочитано за обход: ${result.loaded}."
                result.attempted > 0 -> "За этот обход страницы не загрузились."
                else -> "Сейчас нет страниц, ожидающих обхода."
            })
    }

    private suspend fun find(query: String, lookup: String = query): SearchPage {
        val status = index.status()
        if (status.pages == 0) return SearchPage(emptyList(), status = SearchStatus.INDEX_EMPTY, detail = indexLine(status))
        LemmaHolder.load { app.assets.open(LemmaHolder.ASSET) }
        val terms = searchTerms(lookup, LemmaHolder.loaded)
        val pages = index.find(terms, SEARCH_LIMIT)
        val hits = pages.mapNotNull { page -> SearchHit.from(page.title, page.url, bestExcerpt(page, terms)) }
        val byUrl = pages.associateBy { it.url }
        // Порядок чтения и нумерация выдачи совпадают, даже если часть страниц отфильтрована.
        val readable = hits.mapNotNull { byUrl[it.url] }
        val answer = if (readable.isEmpty()) null else withTimeoutOrNull(75_000) { reader.answer(query, readable) }
        return SearchPage(hits, answer = answer, detail = indexLine(status) + if (hits.isNotEmpty() && answer == null) {
            " Ниже найдённые выдержки."
        } else "")
    }

    private fun indexLine(status: IndexStatus): String {
        val date = status.checkedAt?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        }
        return "В локальном индексе страниц: ${status.pages}." + if (date == null) "" else " Последнее обновление: $date."
    }

    companion object {
        private const val HISTORY = "history"
    }
}
