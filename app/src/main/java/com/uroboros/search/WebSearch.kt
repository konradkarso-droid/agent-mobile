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
)

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
    val resultId: String? = null,
    val hits: List<SearchHit> = emptyList(),
    val storageError: Boolean = false,
    val detail: String? = null,
    val answer: String? = null,
) {
    fun latestText(): String {
        if (storageError) return SearchStatus.STORAGE_ERROR.label
        val entry = entries.firstOrNull { !it.refresh } ?: return "Поисков ещё не было."
        return if (entry.id == resultId) resultText(entry, hits, detail, answer) else resultText(entry, emptyList())
    }

    fun historyText(): String {
        val searches = entries.filterNot { it.refresh }
        if (searches.isEmpty() || storageError) return latestText()
        return searches.take(5).joinToString("\n") {
            "• " + it.timeLabel() + " · " + it.channel.label + " · «" + it.query + "» · " + it.status.label
        }
    }

    /** Только сведения о действии. Выдержки и ссылки в обычный ход не передаются. */
    fun conversationNote(): String? {
        if (storageError) return SearchStatus.STORAGE_ERROR.label + "."
        val searches = entries.filterNot { it.refresh }
        if (searches.isEmpty()) return null
        return searches.distinctBy { it.channel }.joinToString("\n") { entry ->
            "Последний веб-поиск владельца (" + entry.timeLabel() + ", " + entry.channel.label + "): «" +
                entry.query.replace("]", "］").replace("»", "›") +
                "» — " + entry.status.label + (if (entry.count > 0) ", источников: " + entry.count else "") + "."
        }
    }
}

private fun SearchEntry.timeLabel(): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))

internal data class SearchReply(val text: String, val duplicate: Boolean = false)

private fun resultText(
    entry: SearchEntry, hits: List<SearchHit>, detail: String? = null, answer: String? = null,
): String = buildString {
    appendLine("«" + entry.query + "» · " + entry.channel.label + " · " + entry.status.label)
    detail?.let { appendLine(it) }
    answer?.let { appendLine("\n$it") }
    if (hits.isNotEmpty()) {
        appendLine(if (answer == null) "\nПо выдержкам из поиска" else "\nИсточники")
        hits.forEachIndexed { index, hit ->
            appendLine("\n" + (index + 1) + ". " + hit.title)
            if (answer == null) appendLine(hit.description)
            appendLine(hit.url)
        }
    } else if (entry.status == SearchStatus.FOUND) {
        append("Источников было " + entry.count + ". Выдержки больше недоступны.")
    }
}.trimEnd()

internal interface SearchHistory {
    fun read(): List<SearchEntry>
    fun write(entries: List<SearchEntry>): Boolean
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
 * Один активный запрос на оба канала. На диск попадают поручения и исходы.
 * Результат последнего успешного поиска живёт в процессе и заменяется следующим.
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

            var hits = emptyList<SearchHit>()
            var detail: String? = null
            var answer: String? = null
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
                            hits = found.hits.distinctBy { it.url }.take(SEARCH_LIMIT)
                            detail = found.detail
                            answer = found.answer
                            found.status ?: when {
                                hits.isNotEmpty() -> SearchStatus.FOUND
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
            val finished = entry.copy(status = status, count = hits.size)
            val stored = withContext(NonCancellable) { save(finished, hits, detail, answer) }
            if (!stored) return SearchReply(SearchStatus.STORAGE_ERROR.label)
            return SearchReply(resultText(finished, hits, detail, answer))
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
                if (it.status == SearchStatus.SEARCHING) it.copy(status = SearchStatus.STOPPED) else it
            })
            initialized = true
        } catch (_: Exception) {
            view.value = SearchView(storageError = true)
        }
    }

    private suspend fun save(
        entry: SearchEntry, hits: List<SearchHit> = emptyList(), detail: String? = null, answer: String? = null,
    ): Boolean = updates.withLock {
        load()
        if (!initialized) return@withLock false
        val before = view.value
        val next = (listOf(entry) + before.entries.filterNot { it.id == entry.id }).take(HISTORY_LIMIT)
        val stored = runCatching { history.write(next) }.getOrDefault(false)
        view.value = if (!stored) {
            SearchView(storageError = true)
        } else if (!entry.refresh && (hits.isNotEmpty() || detail != null || answer != null)) {
            SearchView(next, entry.id, hits, detail = detail, answer = answer)
        } else {
            before.copy(entries = next, storageError = false)
        }
        if (!stored) initialized = false
        stored
    }

    private class SearchStopped : Exception()

    companion object {
        private const val HISTORY_LIMIT = 24
    }
}
