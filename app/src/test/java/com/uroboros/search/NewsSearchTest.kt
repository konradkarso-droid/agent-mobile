package com.uroboros.search

import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsSearchTest {
    private val now = ZonedDateTime.parse("2026-11-26T14:00:00+03:00")
    private val clock = now.toInstant().toEpochMilli()
    private val science = webSources.single { it.name == "N+1" }
    private val android = webSources.single { it.name == "Android Developers" }
    private val interfax = webSources.single { it.name == "Интерфакс" }
    private val dw = webSources.single { it.name == "DW" }

    @Test
    fun explicitlyRequestedNewsUsesCommonCommandAndRollingDay() {
        val phrase = "Подбери свежие новости об Андроиде с кратким пересказом"
        val command = SearchCommand.fromOwnerWords(phrase) as SearchCommand.Find
        val route = SearchRoute.parse(command.query, now)
        assertEquals("Андроиде", route.publication.subject)
        assertEquals("Android", newsWords(route.publication.subject))
        assertTrue(route.publication.news)
        assertTrue(route.publication.summary)
        assertEquals(clock - 86_400_000, route.publication.window?.from)
        assertEquals(clock + 1, route.publication.window?.until)
        assertTrue(android in sourcesForNews(route.publication.subject))
        assertFalse(science in sourcesForNews(route.publication.subject))
    }

    @Test
    fun namedSourcesAndPeriodsKeepTheirScope() {
        val command = SearchCommand.fromOwnerWords("Найди на N+1 материалы о телескопах") as SearchCommand.Find
        val route = SearchRoute.parse(command.query, now)
        assertEquals(science, route.source)
        assertEquals("телескопах", route.publication.subject)
        assertNull(route.publication.window)
        assertEquals(listOf(science), sourcesForNews(route.publication.subject, route.source))
        val news = SearchRoute.parse("новости из DW сегодня", now)
        assertEquals(dw, news.source)
        assertEquals("сегодня", news.publication.window?.label)
        assertTrue(news.publication.subject.isBlank())
    }

    @Test
    fun aMentionAndAQuoteDoNotStartWebSearch() {
        assertNull(SearchCommand.fromOwnerWords("Я читала свежие новости утром"))
        assertNull(SearchCommand.fromOwnerWords("«Подбери свежие новости»"))
        assertTrue(SearchCommand.fromOwnerWords("Подбери свежие новости через час") is SearchCommand.Deferred)
        assertFalse(SearchCommand.isOwnerDirect(false, "1", "1", false, false, false, false))
    }

    @Test
    fun headlinesFilterByDateAndTopicAndSpreadDifferentStoriesAcrossSources() {
        fun item(source: WebFeedSource, id: Int, title: String, at: Long?) =
            NewsHeadline(source, FeedArticle("https://example.com/news/$id", title, at))
        val first = item(interfax, 1, "Android получил обновление системы безопасности", clock)
        val duplicate = item(dw, 2, "Android получил обновление системы безопасности", clock - 1)
        val second = item(dw, 3, "Android получит новый режим чтения", clock - 5)
        val extra = item(interfax, 4, "Android меняет интерфейс уведомлений", clock - 2)
        val old = item(android, 5, "Android в старых планшетах", clock - 90_000_000)
        val unknown = item(android, 6, "Android для машин", null)
        val unrelated = item(android, 7, "Выпущена библиотека SQLite", clock)
        val window = PublicationWindow(clock - 86_400_000, clock + 1, "последние 24 часа")
        assertEquals(listOf(first, second, extra), selectHeadlines(
            listOf(old, duplicate, extra, unrelated, unknown, first, second),
            SearchWords("Android"), window, 4,
        ))
    }

    @Test
    fun aFeedMayOfferArticlesOnlyFromItsConfiguredPublisher() {
        val title = "Новости редакции"
        val body = """
            <rss><channel>
                <item><title>$title</title><link>https://www.dw.com/ru/primer/a-8000?maca=rss</link></item>
                <item><title>Чужая</title><link>https://www.interfax.ru/russia/1234</link></item>
                <item><title>Не статья</title><link>https://www.dw.com/ru/search</link></item>
                <item><title>Запрос</title><link>https://www.dw.com/ru/primer/a-8001?search=123</link></item>
            </channel></rss>
        """.trimIndent().toByteArray()
        assertEquals(listOf(FeedArticle("https://www.dw.com/ru/primer/a-8000", title)), feedArticles(dw.feed, body))
        assertNull(CrawlSources.address("https://www.dw.com/ru/search"))
    }

    @Test
    fun shortSummaryUsesOneModelCallAndKeepsNumbersFromTheHeadlines() = runBlocking {
        var calls = 0
        val reader = PageReader { _, request ->
            calls++
            assertTrue(request.contains("\"source\":3"))
            assertTrue(request.contains("исходный абзац"))
            "Кратко [3]"
        }
        val words = SearchWords("исходный")
        assertNull(reader.answerBrief("кратко", emptyList(), words))
        assertEquals(0, calls)
        assertEquals("Кратко [3]", reader.answerBrief("кратко",
            listOf(3 to IndexedPage("https://example.org/a", "Статья", "исходный абзац статьи")), words))
        assertEquals(1, calls)
    }
}
