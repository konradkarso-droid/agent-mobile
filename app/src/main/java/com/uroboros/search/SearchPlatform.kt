package com.uroboros.search

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.uroboros.memory.ActionProvenance
import com.uroboros.memory.ActionRequest
import com.uroboros.memory.ActionType
import com.uroboros.memory.EmergencyStop
import com.uroboros.memory.GateResult
import com.uroboros.memory.GatedAction
import com.uroboros.memory.StandingConsent
import com.uroboros.memory.nav.LemmaHolder
import com.uroboros.move.MoveState
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
    private val autoRefresh = WebRefreshSchedule(app)

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
        provider = SearchProvider { answer(it, lookup(it)) },
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

    suspend fun autoRefreshCommand(
        command: SearchCommand.AutoRefresh,
        ownerPresent: () -> Boolean = { false },
        stillAllowed: () -> Boolean = { true },
    ): SearchReply {
        val grant = if (command == SearchCommand.AutoRefresh.Enable) {
            withContext(Dispatchers.Main.immediate) {
                if (ownerPresent()) grantScheduledRefresh(System.currentTimeMillis()) else null
            }
        } else null
        return withContext(Dispatchers.IO) {
            if (!stillAllowed()) return@withContext SearchReply(SearchStatus.DENIED.label)
            try {
                when (command) {
                    SearchCommand.AutoRefresh.Enable -> if (grant == null) {
                        SearchReply("Включить автообновление можно в приложении после открытия замка.")
                    } else autoRefresh.enable(grant)
                    SearchCommand.AutoRefresh.Disable -> autoRefresh.disable()
                    SearchCommand.AutoRefresh.Status -> autoRefresh.status()
                }
            } catch (_: IllegalStateException) {
                SearchReply("Не удалось связаться с планировщиком Android.")
            } catch (_: SecurityException) {
                SearchReply("Android ограничил доступ к планировщику.")
            }
        }
    }

    suspend fun refreshScheduled(network: Network) {
        val grant = autoRefresh.consent() ?: return
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val allowed = {
            autoRefresh.consent()?.id == grant.id && !blocked() &&
                watchdog.zone.value < SafetyZone.FATIGUE && MoveState.holdReason(app) == null &&
                connectivity?.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        }
        val result = engine.background {
            if (!allowed()) {
                return@background SearchPage(emptyList(), status = SearchStatus.STOPPED,
                    detail = "Условия фонового обхода сейчас не выполнены.")
            }
            guardedRefresh(scheduledRefreshRequest(), allowed, grant) {
                // Сокеты и DNS привязаны к сети задания, а не к текущей сети приложения.
                WebCrawler(index, CrawlTransport(network)::fetch).refresh(emptyList(), allowed, forceSeeds = false)
            }
        }
        val message = if (result.status == SearchStatus.BUSY) {
            "Фоновая проверка уступила текущему поиску."
        } else result.detail ?: (result.status ?: SearchStatus.UNAVAILABLE).label
        autoRefresh.record(grant, message)
    }

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
                searchQuery(phrase, channel, allowed)
            }
        }
    }

    private suspend fun searchQuery(query: String, channel: SearchChannel, allowed: () -> Boolean): SearchPage {
        var matches = lookup(query)
        var update: SearchPage? = null
        if (allowed() && (matches.pages.isEmpty() || needsSitemapLookup(matches.pages, matches.terms))) {
            update = refresh(channel, matches.terms, allowed)
            if (allowed()) matches = lookup(query)
        }
        if (matches.pages.isEmpty() && matches.status.pages == 0 && update != null) return update
        if (matches.pages.isEmpty() && allowed()) {
            val alternative = withTimeoutOrNull(10_000) { reader.alternative(query) }
            if (alternative != null && allowed()) {
                val rewritten = lookup(alternative)
                if (rewritten.pages.isNotEmpty()) matches = rewritten
            }
        }
        if (!allowed()) return SearchPage(emptyList(), status = SearchStatus.DENIED)
        return answer(query, matches, update?.detail)
    }

    private suspend fun refresh(
        channel: SearchChannel, terms: List<String>, allowed: () -> Boolean, forceSeeds: Boolean = false,
    ): SearchPage {
        val request = ActionRequest(
            type = ActionType.NETWORK_CALL, requestedBy = "web-search/crawl/" + channel.name,
            provenance = ActionProvenance.USER, crossesDeviceBoundary = true, isReversible = false,
        )
        return guardedRefresh(request, allowed) { crawler.refresh(terms, allowed, forceSeeds) }
    }

    private suspend fun guardedRefresh(
        request: ActionRequest, allowed: () -> Boolean, consent: StandingConsent? = null,
        execute: suspend () -> CrawlReport,
    ): SearchPage {
        val verdict = GatedAction.evaluate(app, request, consent)
        if (!allowed() || verdict.result != GateResult.ALLOW) return SearchPage(emptyList(), status = SearchStatus.DENIED)
        val result = execute()
        val status = when {
            result.loaded + result.unchanged > 0 -> SearchStatus.UPDATED
            result.attempted > 0 -> SearchStatus.CRAWL_FAILED
            result.status.pages == 0 -> SearchStatus.INDEX_EMPTY
            else -> SearchStatus.UPDATED
        }
        val counts = if (result.loaded + result.unchanged > 0) {
            "Загружено страниц: ${result.loaded}. Подтверждено без повторной загрузки: ${result.unchanged}."
        } else if (result.attempted > 0) {
            "Источники не вернули доступных страниц."
        } else "Сейчас нет страниц, ожидающих обхода."
        val feeds = if (result.feedsAttempted > 0) {
            " Проверено лент: ${result.feedsChecked} из ${result.feedsAttempted}."
        } else ""
        return SearchPage(emptyList(), status = status, detail = "${indexLine(result.status)} $counts$feeds")
    }

    private data class Matches(val status: IndexStatus, val terms: List<String>, val pages: List<IndexedPage>)

    private fun lookup(query: String): Matches {
        val status = index.status()
        if (status.pages > 0) LemmaHolder.load { app.assets.open(LemmaHolder.ASSET) }
        val terms = searchTerms(query, LemmaHolder.loaded)
        val pages = if (status.pages == 0) emptyList() else index.find(terms, SEARCH_LIMIT)
        return Matches(status, terms, pages)
    }

    private suspend fun answer(query: String, matches: Matches, updateNote: String? = null): SearchPage {
        val hits = matches.pages.mapNotNull { page ->
            SearchHit.from(page.title, page.url, bestExcerpt(page, matches.terms))
        }
        val byUrl = matches.pages.associateBy { it.url }
        // Порядок чтения и нумерация выдачи совпадают, даже если часть страниц отфильтрована.
        val readable = hits.mapNotNull { byUrl[it.url] }
        val answer = if (readable.isEmpty()) null else withTimeoutOrNull(75_000) { reader.answer(query, readable) }
        return SearchPage(hits, answer = answer, detail = (updateNote ?: indexLine(matches.status)) +
            if (hits.isNotEmpty() && answer == null) " Ниже найдённые выдержки." else "")
    }

    private fun indexLine(status: IndexStatus): String {
        val date = status.checkedAt?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        }
        return "В локальном индексе страниц: ${status.pages}." + if (date == null) "" else " Последняя проверка: $date."
    }

    companion object {
        private const val HISTORY = "history"
    }
}
