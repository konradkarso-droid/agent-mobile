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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class SearchPlatform(
    private val app: Context,
    private val watchdog: DeviceSafetyWatchdog,
    private val processScope: CoroutineScope,
    infer: suspend (String, String) -> String?,
) {
    private val preferences = app.getSharedPreferences("web_search", Context.MODE_PRIVATE)
    private val index = LocalWebIndex(app)
    private val crawler = WebCrawler(index)
    private val linkOpener = LinkOpener()
    private val reader = PageReader(infer)
    private val autoRefresh = WebRefreshSchedule(app)

    private fun blocked() = EmergencyStop.isActive() || watchdog.zone.value == SafetyZone.CRITICAL

    private val engine = WebSearch(
        history = JsonSearchHistory(
            readJson = { preferences.getString(HISTORY, "[]").orEmpty() },
            writeJson = { preferences.edit().putString(HISTORY, it).commit() },
        ),
        stopped = combine(EmergencyStop.active, watchdog.zone) { stop, zone -> stop || zone == SafetyZone.CRITICAL },
        blocked = ::blocked,
        available = { true },
        authorize = { true },
        provider = SearchProvider { answer(it, lookup(it)) },
        requestTimeoutMs = 125_000,
    )

    suspend fun fromApp(
        command: SearchCommand,
        ownerPresent: () -> Boolean = { false },
        onReply: ((SearchReply) -> Unit)? = null,
    ): SearchReply = processScope.async(Dispatchers.IO) {
        val reply = when (command) {
            is SearchCommand.Find, SearchCommand.Refresh -> perform(command, SearchChannel.APP)
            is SearchCommand.AutoRefresh -> autoRefreshCommand(command, ownerPresent)
            SearchCommand.Last -> engine.snapshot().latestReply()
            SearchCommand.History -> SearchReply(engine.snapshot().historyText())
            SearchCommand.Deferred -> SearchReply(SearchCommand.DEFERRED_MESSAGE)
        }
        if (onReply != null) withContext(Dispatchers.Main.immediate) { onReply(reply) }
        reply
    }.await()

    suspend fun fromTelegram(
        command: SearchCommand,
        messageId: String,
        stillOwner: () -> Boolean,
        onAccepted: () -> Unit,
    ): SearchReply = withContext(Dispatchers.IO) {
        perform(command, SearchChannel.TELEGRAM, messageId, stillOwner, onAccepted)
    }

    suspend fun snapshot(): SearchView = withContext(Dispatchers.IO) { engine.snapshot() }

    /**
     * Прямое открытие присланой ссылки: приём адреса, ворота действий, загрузка,
     * чтение. Ответ — готовый текст для чата с источником; отказ тоже текстом,
     * чтобы отправитель видел причину, а не молчание.
     *
     * [origin] задаёт компонент и происхождение для ворот: ссылка из группы
     * идёт не-пользовательским происхождением и без отдельного разрешения
     * владельца ([consent]) ворота её отклоняют сами. В историю поиска
     * открытие не пишется: «что искал» и «какие страницы открывал по чужим
     * ссылкам» — разные вопросы.
     */
    suspend fun followLink(
        origin: LinkOrigin,
        address: String,
        question: String,
        consent: StandingConsent? = null,
        stillAllowed: () -> Boolean = { true },
    ): SearchReply = withContext(Dispatchers.IO) {
        fun refused(why: String) = SearchReply("Ссылку не открываю: $why")
        if (blocked()) return@withContext refused("аварийный стоп или критическая зона — сеть закрыта")
        val url = LinkAdmission.admit(address)
            ?: return@withContext refused("адрес не годится — не https, с паролем, с портом или слишком длинный")
        val verdict = GatedAction.evaluate(
            app,
            ActionRequest(
                type = ActionType.NETWORK_CALL, requestedBy = origin.requestedBy,
                provenance = origin.provenance, crossesDeviceBoundary = true, isReversible = false,
            ),
            consent,
        )
        if (verdict.result != GateResult.ALLOW) return@withContext refused("ворота действий отказали — причина в журнале действий")
        val opened = withTimeoutOrNull(OPEN_LIMIT_MS) { linkOpener.open(url) { stillAllowed() && !blocked() } }
            ?: return@withContext refused("не уложился по времени")
        when (opened) {
            is LinkOpener.Open.Refused -> refused(opened.why)
            is LinkOpener.Open.Page -> {
                val query = question.trim().ifEmpty { LinkOpener.SUMMARY_QUESTION }
                val answer = withTimeoutOrNull(READ_LIMIT_MS) {
                    reader.answer(query, listOf(IndexedPage(opened.url, opened.title, opened.text)))
                }
                val text = if (answer != null) "$answer\n\nИсточник: ${opened.url}"
                else "Ответ модели недоступен. Открыл страницу «${opened.title}».\n\n${pageExcerpt(opened.text)}\n\nИсточник: ${opened.url}"
                SearchReply(text, sourceUrls = listOf(opened.url))
            }
        }
    }

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
        val request = PublicationQuery.parse(query)
        val window = request.window
        var matches = lookup(request.subject, window)
        var update: SearchPage? = null
        if (allowed() && (window != null || matches.needsRefresh)) {
            update = refresh(channel, matches.words.terms, allowed, window = window)
            if (allowed()) matches = lookup(request.subject, window)
        }
        if (window == null && matches.indexed.isEmpty() && matches.status.pages == 0 && update != null) return update
        if (matches.indexed.isEmpty() && request.subject.isNotBlank() && allowed()) {
            val alternative = withTimeoutOrNull(10_000) { reader.alternative(request.subject) }
            if (alternative != null && allowed()) {
                val rewritten = lookup(alternative, window)
                if (rewritten.indexed.isNotEmpty()) matches = rewritten
            }
        }
        if (!allowed()) return SearchPage(emptyList(), status = SearchStatus.DENIED)
        val updateNote = update?.detail?.let { note ->
            if (update.status == SearchStatus.DENIED) "${indexLine(matches.status)} $note" else note
        }
        return answer(query, matches, updateNote, window)
    }

    private suspend fun refresh(
        channel: SearchChannel, terms: List<String>, allowed: () -> Boolean, forceSeeds: Boolean = false,
        window: PublicationWindow? = null,
    ): SearchPage {
        val request = ActionRequest(
            type = ActionType.NETWORK_CALL, requestedBy = "web-search/crawl/" + channel.name,
            provenance = ActionProvenance.USER, crossesDeviceBoundary = true, isReversible = false,
        )
        return guardedRefresh(request, allowed) { crawler.refresh(terms, allowed, forceSeeds, window) }
    }

    private suspend fun guardedRefresh(
        request: ActionRequest, allowed: () -> Boolean, consent: StandingConsent? = null,
        execute: suspend () -> CrawlReport,
    ): SearchPage {
        val verdict = GatedAction.evaluate(app, request, consent)
        if (!allowed()) return SearchPage(emptyList(), status = SearchStatus.DENIED)
        if (verdict.result != GateResult.ALLOW) {
            return SearchPage(emptyList(), status = SearchStatus.DENIED, detail = "Врата отклонили сетевой обход.")
        }
        return execute().toSearchPage()
    }

    private data class Matches(val status: IndexStatus, val words: SearchWords, val indexed: List<IndexedMatch>) {
        val needsRefresh get() = indexed.firstOrNull()?.missingWords?.isNotEmpty() ?: true
    }

    private fun lookup(query: String, window: PublicationWindow? = null): Matches {
        val status = index.status()
        if (status.pages > 0) LemmaHolder.load { app.assets.open(LemmaHolder.ASSET) }
        val words = SearchWords(query, LemmaHolder.loaded)
        val indexed = if (status.pages == 0) emptyList() else {
            diverseMatches(index.find(words, CANDIDATE_LIMIT, window), SEARCH_LIMIT)
        }
        return Matches(status, words, indexed)
    }

    private suspend fun answer(
        query: String, matches: Matches, updateNote: String? = null, window: PublicationWindow? = null,
    ): SearchPage {
        val sources = matches.indexed.mapNotNull { match ->
            val page = match.page
            val date = page.publishedAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DATE) }
            val title = if (date == null) page.title else "$date · ${page.title}"
            SearchHit.from(title, page.url, bestExcerpt(page, matches.words))?.let { it to match }
        }
        val readable = sources.map { it.second.page }
        val answer = if (readable.isEmpty()) null else withTimeoutOrNull(75_000) {
            reader.answer(query, readable, matches.words)
        }
        val missingWords = sources.firstOrNull()?.second?.missingWords.orEmpty()
        val detail = buildString {
            append(updateNote ?: "${indexLine(matches.status)} Поиск выполнен по локальному индексу.")
            window?.let { append(" Период по датам публикаций в RSS и Atom — ${it.label}.") }
            if (missingWords.isNotEmpty()) {
                append(" Совпадение частичное. На первой странице не найдены слова «")
                append(missingWords.joinToString("», «"))
                append("».")
            }
            if (sources.isNotEmpty() && answer == null) {
                append(" Ответ модели недоступен. Ниже найдённые выдержки.")
            }
        }
        return SearchPage(sources.map { it.first }, answer = answer, detail = detail)
    }

    companion object {
        private const val HISTORY = "history"
        private const val CANDIDATE_LIMIT = 12

        /** Пределы открытия одной ссылки и чтения её страниц. Объявленные числа, как у поиска. */
        private const val OPEN_LIMIT_MS = 20_000L
        private const val READ_LIMIT_MS = 75_000L
        private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}

internal fun diverseMatches(matches: List<IndexedMatch>, limit: Int): List<IndexedMatch> {
    if (matches.size <= limit) return matches
    val selected = mutableListOf<IndexedMatch>()
    val counts = mutableMapOf<String, Int>()
    for (match in matches) {
        val host = runCatching { java.net.URI(match.page.url).host?.lowercase() }.getOrNull()
            ?: match.page.url
        if ((counts[host] ?: 0) >= 2) continue
        selected += match
        counts[host] = (counts[host] ?: 0) + 1
        if (selected.size == limit) break
    }
    if (selected.size < limit) {
        for (match in matches) {
            if (match in selected) continue
            selected += match
            if (selected.size == limit) break
        }
    }
    return selected
}

internal fun CrawlReport.toSearchPage(): SearchPage {
    val verifiedPages = loaded + unchanged
    val outcome = when {
        timedOut -> SearchStatus.TIMED_OUT
        verifiedPages > 0 -> SearchStatus.UPDATED
        attempted > 0 || (feedsAttempted > 0 && feedsChecked == 0) -> SearchStatus.CRAWL_FAILED
        status.pages == 0 -> SearchStatus.INDEX_EMPTY
        else -> SearchStatus.UPDATED
    }
    val detail = buildString {
        append(indexLine(status))
        when {
            verifiedPages > 0 -> append(" Загружено страниц: $loaded. Подтверждено без повторной загрузки: $unchanged.")
            timedOut -> Unit
            attempted > 0 -> append(" Источники не вернули доступных страниц.")
            else -> append(" Сейчас нет страниц, ожидающих обхода.")
        }
        if (feedsAttempted > 0) append(" Проверено лент: $feedsChecked из $feedsAttempted.")
        if (timedOut) append(" Достигнут лимит времени сетевого обхода.")
    }
    return SearchPage(emptyList(), status = outcome, detail = detail)
}

private fun indexLine(status: IndexStatus): String {
    val date = status.checkedAt?.let {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
    }
    return "В локальном индексе страниц: ${status.pages}." + if (date == null) "" else " Последняя проверка: $date."
}
