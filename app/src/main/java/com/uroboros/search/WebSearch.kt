package com.uroboros.search

import java.net.URI
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class SearchChannel(val label: String) {
    APP("приложение"), TELEGRAM("Telegram"),
}

internal enum class SearchStatus(val label: String) {
    SEARCHING("ищу"), FOUND("источники найдены"), EMPTY("в проиндексированных источниках совпадений нет"),
    INSUFFICIENT("выдержек недостаточно"),
    NOT_CONFIGURED("прежний поиск не был настроен"),
    INDEX_EMPTY("локальный индекс пуст"), UPDATED("обход завершён"),
    CRAWL_FAILED("источники не удалось обновить"),
    UNAVAILABLE("локальный поиск недоступен"), DENIED("сетевой запрос отклонён"),
    STOPPED("поиск остановлен"), TIMED_OUT("истекло время поиска"),
    STORAGE_ERROR("история поиска недоступна"), BUSY("уже идёт поиск или обновление источников"),
    INVALID("нужна непустая поисковая фраза до $MAX_QUERY_LENGTH знаков"),
}

internal const val MAX_QUERY_LENGTH = 300
internal const val SEARCH_LIMIT = 4

internal data class SearchEntry(
    val id: String,
    val requestId: String,
    val query: String,
    val channel: SearchChannel,
    val at: Long,
    val status: SearchStatus,
    val count: Int = 0,
    val refresh: Boolean = false,
    val result: SearchResult? = null,
)

internal data class SearchResult(val hits: List<SearchHit>, val detail: String?, val answer: String?) {
    companion object {
        fun from(hits: List<SearchHit>, detail: String?, answer: String?): SearchResult? {
            val sources = hits.mapNotNull { SearchHit.from(it.title, it.url, it.description) }
                .distinctBy { it.url }.take(SEARCH_LIMIT)
            val note = detail?.take(1_800)?.trimEnd { it.isHighSurrogate() }?.ifBlank { null }
            val summary = answer?.take(1_100)?.trimEnd { it.isHighSurrogate() }?.ifBlank { null }
            if (sources.isEmpty() && note == null && summary == null) return null
            return SearchResult(sources, note, summary)
        }
    }
}

internal data class SearchHit(val title: String, val url: String, val description: String) {
    companion object {
        fun from(title: String, url: String, description: String): SearchHit? {
            if (url.length > 480) return null
            val address = runCatching { URI(url) }.getOrNull() ?: return null
            if (!address.scheme.equals("https", ignoreCase = true) || address.host == null ||
                address.rawUserInfo != null || address.port !in listOf(-1, 443)
            ) return null

            val heading = plainText(title, 140).ifBlank { address.host }
            val snippet = plainText(description, 260)
            if (snippet.isBlank()) return null
            return SearchHit(heading, address.toASCIIString().takeIf { it.length <= 480 } ?: return null, snippet)
        }

        private fun plainText(value: String, limit: Int): String = buildString {
            for (character in value) {
                if (length >= limit) break
                when {
                    character.isWhitespace() -> if (isNotEmpty() && last() != ' ') append(' ')
                    character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt() -> Unit
                    else -> append(character)
                }
            }
            if (isNotEmpty() && last().isHighSurrogate()) deleteCharAt(lastIndex)
        }.trim()
    }
}

internal data class SearchView(
    val entries: List<SearchEntry> = emptyList(),
    val storageError: Boolean = false,
) {
    fun latestReply(): SearchReply {
        if (storageError) return SearchReply(SearchStatus.STORAGE_ERROR.label)
        val entry = entries.firstOrNull { !it.refresh } ?: return SearchReply("Поисков ещё не было.")
        return resultReply(entry)
    }

    fun latestText(): String = latestReply().text

    fun historyText(): String {
        val searches = entries.filterNot { it.refresh }
        if (searches.isEmpty() || storageError) return latestText()
        return searches.take(5).joinToString("\n") {
            "• " + it.timeLabel() + " · " + it.channel.label + " · «" + it.query + "» · " + it.status.label
        }
    }

}

private fun SearchEntry.timeLabel(): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))

internal data class SearchReply(
    val text: String,
    val duplicate: Boolean = false,
    val chatText: String = text,
    val sourceUrls: List<String> = emptyList(),
)

private fun resultReply(entry: SearchEntry): SearchReply {
    val result = entry.result
    val hits = result?.hits.orEmpty()
    val answer = result?.answer
    val body = buildString {
        answer?.let { appendLine(it) }
        if (hits.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine(if (answer == null) "По выдержкам из поиска" else "Источники")
            hits.forEachIndexed { index, hit ->
                appendLine("\n" + (index + 1) + ". " + hit.title)
                if (answer == null) appendLine(hit.description)
                appendLine(hit.url)
            }
        } else if (entry.status == SearchStatus.FOUND) {
            appendLine("Источников было " + entry.count + ". Выдержки больше недоступны.")
        }
        result?.detail?.let {
            if (isNotEmpty()) appendLine()
            appendLine(it)
        }
    }.trimEnd()
    val heading = "«${entry.query}» · ${entry.channel.label} · ${entry.status.label}"
    return SearchReply(
        text = listOf(heading, body).filter { it.isNotEmpty() }.joinToString("\n"),
        chatText = body.ifEmpty { entry.status.label },
        sourceUrls = hits.map { it.url },
    )
}

internal data class SearchPage(
    val hits: List<SearchHit>,
    val reportedHits: Boolean = hits.isNotEmpty(),
    val status: SearchStatus? = null,
    val detail: String? = null,
    val answer: String? = null,
)

internal fun interface SearchProvider {
    suspend fun search(query: String): SearchPage
}

/**
 * Один активный запрос на оба канала. На диск попадают поручения, исходы и
 * выдача последнего поиска. Обновление индекса сохраняет эту выдачу.
 * Лимиты объявлены для коротких выдержек. Они не оценивают пригодность статьи.
 */
internal class WebSearch(
    private val history: SearchHistory,
    private val stopped: Flow<Boolean>,
    private val blocked: () -> Boolean,
    private val available: () -> Boolean,
    private val authorize: suspend (SearchChannel) -> Boolean,
    private val provider: SearchProvider,
    private val requestTimeoutMs: Long = 15_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val active = Mutex()
    private val updates = Mutex()
    private val view = MutableStateFlow(SearchView())
    private var initialized = false
    val state: StateFlow<SearchView> get() = view

    suspend fun snapshot(): SearchView = updates.withLock { load(); view.value }

    suspend fun search(
        query: String,
        channel: SearchChannel,
        requestId: String = UUID.randomUUID().toString(),
        stillAllowed: () -> Boolean = { true },
        onAccepted: () -> Unit = {},
        refresh: Boolean = false,
        operation: (suspend () -> SearchPage)? = null,
    ): SearchReply {
        val phrase = query.trim()
        if (phrase.isEmpty() || phrase.length > MAX_QUERY_LENGTH ||
            phrase.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        ) return SearchReply(SearchStatus.INVALID.label)

        if (!active.tryLock()) {
            val duplicate = snapshot().entries.any { it.channel == channel && it.requestId == requestId }
            return SearchReply(if (duplicate) "" else SearchStatus.BUSY.label, duplicate)
        }
        try {
            val entry = SearchEntry(UUID.randomUUID().toString(), requestId, phrase, channel, now(), SearchStatus.SEARCHING, refresh = refresh)
            val previous = snapshot()
            if (previous.entries.any { it.channel == channel && it.requestId == requestId }) {
                return SearchReply("", duplicate = true)
            }
            if (!save(entry)) return SearchReply(SearchStatus.STORAGE_ERROR.label)
            onAccepted()

            var result: SearchResult? = null
            val status = try {
                when {
                    !authorize(channel) -> if (blocked()) SearchStatus.STOPPED else SearchStatus.DENIED
                    blocked() -> SearchStatus.STOPPED
                    !stillAllowed() -> SearchStatus.DENIED
                    !available() -> SearchStatus.NOT_CONFIGURED
                    else -> {
                        val found = withTimeoutOrNull(requestTimeoutMs) {
                            untilStopped { operation?.invoke() ?: provider.search(phrase) }
                        }
                        if (blocked()) SearchStatus.STOPPED
                        else if (!stillAllowed()) SearchStatus.DENIED
                        else if (found == null) SearchStatus.TIMED_OUT
                        else {
                            result = SearchResult.from(found.hits, found.detail, found.answer)
                            found.status ?: when {
                                result?.hits?.isNotEmpty() == true -> SearchStatus.FOUND
                                found.reportedHits -> SearchStatus.INSUFFICIENT
                                else -> SearchStatus.EMPTY
                            }
                        }
                    }
                }
            } catch (_: SearchStopped) {
                SearchStatus.STOPPED
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { save(entry.copy(status = SearchStatus.STOPPED)) }
                throw cancelled
            } catch (_: Exception) {
                SearchStatus.UNAVAILABLE
            }
            val finished = entry.copy(status = status, count = result?.hits?.size ?: 0, result = result)
            val stored = withContext(NonCancellable) { save(finished) }
            if (!stored) return SearchReply(SearchStatus.STORAGE_ERROR.label)
            return resultReply(finished)
        } finally {
            active.unlock()
        }
    }

    /** Одно общее окно для поиска и обхода, без записи фоновой работы в историю запросов. */
    suspend fun background(operation: suspend () -> SearchPage): SearchPage {
        if (!active.tryLock()) return SearchPage(emptyList(), status = SearchStatus.BUSY)
        try {
            if (blocked()) return SearchPage(emptyList(), status = SearchStatus.STOPPED)
            return untilStopped(operation)
        } catch (_: SearchStopped) {
            return SearchPage(emptyList(), status = SearchStatus.STOPPED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SearchPage(emptyList(), status = SearchStatus.UNAVAILABLE)
        } finally {
            active.unlock()
        }
    }

    private suspend fun untilStopped(operation: suspend () -> SearchPage): SearchPage = coroutineScope {
        val request = async { operation() }
        val stop = async { stopped.first { it } }
        try {
            select<SearchPage?> {
                stop.onAwait { null }
                request.onAwait { it }
            } ?: throw SearchStopped()
        } finally {
            request.cancel()
            stop.cancel()
        }
    }

    private fun load() {
        if (initialized) return
        try {
            view.value = SearchView(entries = history.read().take(HISTORY_LIMIT).map {
                if (it.status == SearchStatus.SEARCHING) it.copy(status = SearchStatus.STOPPED, result = null) else it
            })
            initialized = true
        } catch (_: Exception) {
            view.value = SearchView(storageError = true)
        }
    }

    private suspend fun save(entry: SearchEntry): Boolean = updates.withLock {
        load()
        if (!initialized) return@withLock false
        val current = if (entry.refresh) entry.copy(result = null) else entry
        val previous = view.value.entries.filterNot { it.id == entry.id }.map {
            if (entry.refresh || it.result == null) it else it.copy(result = null)
        }
        val next = (listOf(current) + previous).take(HISTORY_LIMIT)
        val stored = runCatching { history.write(next) }.getOrDefault(false)
        view.value = if (stored) SearchView(entries = next) else SearchView(storageError = true)
        if (!stored) initialized = false
        stored
    }

    private class SearchStopped : Exception()

    companion object {
        private const val HISTORY_LIMIT = 24
    }
}
