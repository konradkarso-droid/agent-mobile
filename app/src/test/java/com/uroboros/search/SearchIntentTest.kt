package com.uroboros.search

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIntentTest {
    private val now = ZonedDateTime.parse("2026-11-26T14:00:00+03:00")

    @Test
    fun conversationalQuestionsReachTheRightRoute() {
        val examples = listOf(
            "Что нового у Android?" to "Android",
            "А какие новости были вчера об Android?" to "Android",
            "Кстати, что там нового про телескопы за неделю?" to "телескопы",
            "Есть ли свежие новости об Андроиде?" to "Андроиде",
            "Расскажи, что произошло сегодня" to "",
            "Покажи, какие новости были вчера о телескопах?" to "телескопах",
            "А какие новости были вчера?" to "",
            "Какие сейчас новости об Android?" to "Android",
        )
        for ((question, subject) in examples) {
            val query = (SearchCommand.fromOwnerWords(question) as SearchCommand.Find).query
            val route = SearchRoute.parse(query, now)
            assertTrue(question, route.publication.news)
            assertEquals(question, subject, route.publication.subject)
            when {
                "вчера" in question -> assertEquals("вчера", route.publication.window?.label)
                "сегодня" in question -> assertEquals("сегодня", route.publication.window?.label)
                "за неделю" in question -> assertEquals("за неделю", route.publication.window?.label)
                else -> assertEquals("последние 24 часа", route.publication.window?.label)
            }
        }
    }

    @Test
    fun naturalQuestionsCanAskForSourcesOrCurrentFacts() {
        assertEquals(SearchCommand.Find("SQLite"), SearchCommand.fromOwnerWords("Где можно почитать про SQLite?"))
        assertEquals(SearchCommand.Find("SQLite"), SearchCommand.fromOwnerWords("Можно ли почитать про SQLite?"))
        assertEquals(SearchCommand.Find("телескопах"), SearchCommand.fromOwnerWords("Есть ли статьи о телескопах?"))
        assertEquals(SearchCommand.Find("Какая сейчас актуальная версия Android"),
            SearchCommand.fromOwnerWords("Какая сейчас актуальная версия Android?"))
        val command = SearchCommand.fromOwnerWords("А что нового у РБК сегодня?") as SearchCommand.Find
        val route = SearchRoute.parse(command.query, now)
        assertEquals("РБК", route.source?.name)
        assertEquals("сегодня", route.publication.window?.label)
    }

    @Test
    fun ordinarySpeechQuotesAndOfflineRequestsStayOutOfSearch() {
        listOf(
            "Что нового?",
            "А что случилось?",
            "Что нового у тебя сегодня?",
            "Что нового в нашей семье?",
            "Какие новости были в нашем чате?",
            "Как работает SQLite?",
            "Что известно о теореме Пифагора?",
            "Она спросила, что нового у Android?",
            "«Какие новости были вчера?»",
            "А что нового у Android? Только без интернета.",
            "Поищи новости об Android, но не ищи в сети",
            "Какие новости были вчера? https://example.org/news",
            "Свежие новости https://example.org/news",
            "Что нового у Android через час?",
            "А вчера?",
        ).forEach { assertNull(it, SearchCommand.fromOwnerWords(it)) }
        assertTrue(SearchIntent.forbidsSearch("Не открывай https://example.org/news"))
    }

    @Test
    fun followupChangesOnlyTheRequestedPeriod() {
        val command = SearchCommand.fromOwnerWords("А вчера?", "новости о телескопах из N+1 сегодня") as SearchCommand.Find
        val route = SearchRoute.parse(command.query, now)
        assertEquals("телескопах", route.publication.subject)
        assertEquals("N+1", route.source?.name)
        assertEquals("вчера", route.publication.window?.label)
        assertEquals(command, SearchCommand.fromOwnerWords("А какие новости были вчера?", "новости о телескопах из N+1 сегодня"))
        assertNull(SearchCommand.fromOwnerWords("Вчера было холодно", command.query))
    }

    @Test
    fun followupUsesOnlyTheSameBotAndChatAndRecentReply() {
        val start = 1_000_000_000L
        val prior = SearchIntent.RecentReply(1, 2, 42, "новости о космосе", start)
        assertEquals(prior.query, prior.topicFor(1, 2, 43, null, start + 1))
        assertEquals(prior.query, prior.topicFor(1, 2, 52, 42, start + 1))
        assertNull(prior.topicFor(1, 2, 45, null, start + 1))
        assertNull(prior.topicFor(1, 2, 43, 40, start + 1))
        assertNull(prior.topicFor(1, 3, 43, null, start + 1))
        assertNull(prior.topicFor(3, 2, 43, null, start + 1))
        assertNull(prior.topicFor(1, 2, 43, null, start + 16L * 60 * 1_000_000_000))
    }

    @Test
    fun indexMaintenanceUnderstandsQuestionsAndRequests() {
        val requests = listOf(
            "Можешь обновить индекс?" to SearchCommand.Refresh,
            "Кстати, освежи источники ещё раз" to SearchCommand.Refresh,
            "А когда индекс обновлялся?" to SearchCommand.AutoRefresh.Status,
            "Ты сам обновляешь индекс?" to SearchCommand.AutoRefresh.Status,
            "А сколько страниц в индексе?" to SearchCommand.AutoRefresh.Status,
            "Пусть индекс обновляется сам" to SearchCommand.AutoRefresh.Enable,
            "Перестань сам обновлять индекс" to SearchCommand.AutoRefresh.Disable,
            "А что мы уже искали?" to SearchCommand.History,
            "И чем закончился поиск?" to SearchCommand.Last,
        )
        for ((text, expected) in requests) assertEquals(text, expected, SearchCommand.fromOwnerWords(text))
    }

    @Test
    fun reportsAndQuotedWordsDoNotChangeTheRefreshSchedule() {
        listOf(
            "Индекс сам обновляется.",
            "Вчера ты обновлял индекс",
            "Он просил включить автообновление",
            "«Пусть индекс обновляется сам»",
            "Не обновляй индекс",
        ).forEach { assertNull(it, SearchCommand.fromOwnerWords(it)) }
    }
}
