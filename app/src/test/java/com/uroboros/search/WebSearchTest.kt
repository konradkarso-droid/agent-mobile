package com.uroboros.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {
    private class Store : SearchHistory {
        var rows = emptyList<SearchEntry>()
        var writable = true

        override fun read() = rows
        override fun write(entries: List<SearchEntry>): Boolean {
            if (!writable) return false
            rows = entries
            return true
        }
    }

    private val stopped = MutableStateFlow(false)

    private fun engine(
        store: Store = Store(),
        allowed: Boolean = true,
        provider: SearchProvider,
    ) = WebSearch(store, stopped, { stopped.value }, { true }, { allowed }, provider)

    private val sample = SearchHit("Статья", "https://example.com/article", "Метка внешнего текста")

    @Test
    fun sharedStateAndRestoredHistoryContainNoExternalText() = runBlocking {
        val store = Store()
        val engine = engine(store) { SearchPage(listOf(sample)) }
        val reply = engine.search("пример", SearchChannel.TELEGRAM, "bot:chat:msg")
        assertTrue(reply.text.contains(sample.description))
        val shared = engine.snapshot()
        assertEquals(SearchChannel.TELEGRAM, shared.entries.single().channel)
        assertEquals(reply.text, shared.latestText())
        assertTrue(shared.conversationNote()!!.contains("пример"))
        assertFalse(shared.conversationNote()!!.contains(sample.description))
        assertFalse(shared.conversationNote()!!.contains(sample.url))
        assertFalse(store.rows.toString().contains(sample.description))
        assertFalse(store.rows.toString().contains(sample.url))

        val reopened = engine(store) { error("Просмотр истории не вызывает сеть") }.snapshot()
        assertEquals(shared.entries, reopened.entries)
        assertTrue(reopened.latestText().contains("Выдержки больше недоступны"))
        assertTrue(reopened.hits.isEmpty())
    }

    @Test
    fun conversationCanSeeTheLastSearchFromEachChannel() = runBlocking {
        val engine = engine { SearchPage(listOf(sample)) }
        engine.search("из Telegram", SearchChannel.TELEGRAM)
        engine.search("первый из приложения", SearchChannel.APP)
        engine.search("последний из приложения", SearchChannel.APP)
        val text = engine.snapshot().conversationNote()!!
        assertTrue(text.contains("из Telegram"))
        assertTrue(text.contains("последний из приложения"))
        assertFalse(text.contains("первый из приложения"))
        assertFalse(text.contains(sample.description))
    }

    @Test
    fun resumeAfterRestartMarksAnInterruptedRequestWithoutRetrying() = runBlocking {
        val store = Store().apply {
            rows = listOf(SearchEntry("id", "message-id", "тепло", SearchChannel.TELEGRAM, 0, SearchStatus.SEARCHING))
        }
        val engine = engine(store) { error("Повтор отправил бы ещё один запрос") }
        assertEquals(SearchStatus.STOPPED, engine.snapshot().entries.single().status)
        assertTrue(engine.search("тепло", SearchChannel.TELEGRAM, "message-id").duplicate)
    }

    @Test
    fun refusesCallsWithoutPermissionOrWritableHistory() = runBlocking {
        var called = false
        val provider = SearchProvider {
            called = true
            SearchPage(emptyList())
        }
        val denied = engine(allowed = false, provider = provider)
        denied.search("проверка", SearchChannel.APP)
        assertEquals(SearchStatus.DENIED, denied.snapshot().entries.single().status)

        val changedOwner = engine(provider = provider)
        changedOwner.search("проверка", SearchChannel.TELEGRAM, stillAllowed = { false })
        assertEquals(SearchStatus.DENIED, changedOwner.snapshot().entries.single().status)

        val broken = engine(Store().apply { writable = false }, provider = provider)
        assertEquals(SearchStatus.STORAGE_ERROR.label, broken.search("проверка", SearchChannel.APP).text)
        assertFalse(called)
    }

    @Test
    fun bothChannelsSeeRunningStatusAndStopCancelsProvider() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val store = Store()
        val engine = engine(store) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                closed.complete(Unit)
            }
        }
        val running = async { engine.search("первый", SearchChannel.APP) }
        started.await()
        assertEquals(SearchStatus.SEARCHING, engine.snapshot().entries.single().status)
        assertEquals(SearchStatus.BUSY.label, engine.search("второй", SearchChannel.TELEGRAM).text)
        stopped.value = true
        running.await()
        closed.await()
        assertEquals(SearchStatus.STOPPED, engine.snapshot().entries.single().status)
        assertEquals(SearchStatus.STOPPED, store.rows.single().status)
    }

    @Test
    fun emptyPageAndMissingUsableSnippetsHaveDifferentStates() = runBlocking {
        val empty = engine { SearchPage(emptyList()) }
        val missing = engine { SearchPage(emptyList(), reportedHits = true) }
        empty.search("нет совпадений", SearchChannel.APP)
        missing.search("выдержки пусты", SearchChannel.TELEGRAM)
        assertEquals(SearchStatus.EMPTY, empty.snapshot().entries.single().status)
        assertEquals(SearchStatus.INSUFFICIENT, missing.snapshot().entries.single().status)
    }

    @Test
    fun sourceUrlsAreDisplayOnlyAndRejectNonHttpsOrUserInfo() {
        assertNull(SearchHit.from("Документ", "file:///storage/emulated/0/data.pdf", "Текст"))
        assertNull(SearchHit.from("Документ", "https://name@example.com/private", "Текст"))
        val hit = SearchHit.from("A\u202eb", "https://example.com/", "строка\nтекста")!!
        assertEquals("Ab", hit.title)
        assertEquals("строка текста", hit.description)
    }

    @Test
    fun directOwnerWordsUseTheSameCommandsAsTheExplicitEntryPoints() {
        assertEquals(
            SearchCommand.Find("как отключить превью ссылки?"),
            SearchCommand.fromOwnerWords("Уроборос, пожалуйста, найди в интернете, как отключить превью ссылки?"),
        )
        assertEquals(SearchCommand.Find("инструкция для МГ-42"), SearchCommand.fromOwnerWords("Погугли инструкция для МГ-42"))
        assertEquals(SearchCommand.Find("новости"), SearchCommand.fromOwnerWords("Поищи, пожалуйста, в сети новости"))
        assertEquals(
            SearchCommand.Find("расписание?"),
            SearchCommand.fromOwnerWords("Можешь посмотреть онлайн расписание?"),
        )
        assertEquals(SearchCommand.Last, SearchCommand.fromOwnerWords("Что ты нашёл в интернете?"))
        assertEquals(SearchCommand.Last, SearchCommand.fromOwnerWords("Покажи последний поиск"))
        assertEquals(SearchCommand.History, SearchCommand.fromOwnerWords("Покажи историю веб-поисков"))
        assertEquals(SearchCommand.History, SearchCommand.fromOwnerWords("Покажи историю поиска"))
    }

    @Test
    fun requestsToSearchLaterHaveNoImmediateSearchCommand() {
        listOf(
            "Поищи через час",
            "Через час поищи в интернете расписание",
            "Поищи в интернете через час новости",
            "Поищи в интернете новости через час",
            "Поищи, пожалуйста, через два часа",
            "Проверь в интернете сегодня в 19:00 расписание",
            "Погугли новости, вечером",
        ).forEach { assertEquals(it, SearchCommand.Deferred, SearchCommand.fromOwnerWords(it)) }
        assertEquals(
            SearchCommand.Find("какая погода вечером?"),
            SearchCommand.fromOwnerWords("Посмотри в интернете, какая погода вечером?"),
        )
        assertEquals(
            SearchCommand.Find("фильм «Через час»"),
            SearchCommand.fromOwnerWords("Погугли фильм «Через час»"),
        )
        assertNull(SearchCommand.fromOwnerWords("Напомни о встрече вечером"))
        assertNull(SearchCommand.fromOwnerWords("Ты обещал поискать через час"))
    }

    @Test
    fun quotesPromisesAndOrdinaryConversationDoNotRequestNetworkAccess() {
        listOf(
            "Ты обещал поискать в интернете новости!",
            "Я сам погуглю новости.",
            "«Поищи в интернете новости»",
            "Он написал: погугли новости",
            "Не ищи в интернете новости",
            "Найди запись в памяти",
            "Как искать в интернете?",
            "Поищи в интернете: :",
        ).forEach { assertNull(it, SearchCommand.fromOwnerWords(it)) }
        assertFalse(SearchCommand.isOwnerDirect(true, "1", "1", false, false, false, false, quoted = true))
    }

    @Test
    fun commandAndHistoryAreForTheOwnerInDirectChat() {
        assertEquals(SearchCommand.Find("размер APK"), SearchCommand.parse("/search@mybot размер APK", "MyBot"))
        assertEquals(SearchCommand.History, SearchCommand.parse("/search_history", "MyBot"))
        assertEquals(SearchCommand.Last, SearchCommand.parse("/search_last", "MyBot"))
        assertNull(SearchCommand.parse("/search@other размер APK", "MyBot"))
        assertTrue(SearchCommand.isOwnerDirect(true, "1", "1", false, false, false, false))
        assertFalse(SearchCommand.isOwnerDirect(false, "1", "1", false, false, false, false))
        assertFalse(SearchCommand.isOwnerDirect(true, "1", "2", false, false, false, false))
        assertFalse(SearchCommand.isOwnerDirect(true, "1", "1", false, true, false, false))
        assertFalse(SearchCommand.isOwnerDirect(true, "1", "1", false, false, true, false))
    }
}
