package com.uroboros.search

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFeedTest {
    private val nPlusOne = webFeeds.first { it.host == "nplus1.ru" }
    private val androidBlog = webFeeds.first { it.host == "developer.android.com" }
    private val scienceArticle = "https://nplus1.ru/news/2026/11/20/sea-life"
    private val androidArticle = "https://developer.android.com/blog/posts/jetpack-compose-release"

    @Test
    fun rssUsesItemLinksAndRejectsOtherOriginsAndPaths() {
        val body = """
            <rss version="2.0"><channel>
              <link>https://nplus1.ru/</link>
              <item><title>Морские моллюски</title><link>$scienceArticle</link>
                <description>&lt;a href="https://example.invalid/file"&gt;вложение&lt;/a&gt;</description>
              </item>
              <item><title>Повтор</title><link>$scienceArticle#fragment</link></item>
              <item><title>Чужой сайт</title><link>$androidArticle</link></item>
              <item><title>Без перехода</title><link>https://nplus1.ru/rss</link></item>
              <item><title>Не статья</title><link>https://nplus1.ru/material-preview/file</link></item>
              <item><title>Локальный адрес</title><link>https://127.0.0.1/news/2026/11/20/test</link></item>
              <item><title>С подменой пути</title><link>https://nplus1.ru/news/2026/11/20/a%2fb</link></item>
            </channel></rss>
        """.trimIndent().toByteArray()

        assertEquals(listOf(FeedArticle(scienceArticle, "Морские моллюски")), feedArticles(nPlusOne, body))
        assertTrue(feedScore(FeedArticle(scienceArticle, "Морские моллюски"), listOf("моллюск*")) > 0)
    }

    @Test
    fun atomUsesAlternateHtmlAndKeepsBoundedOrderedOffers() {
        val entries = (1..32).joinToString("") { n ->
            """<a:entry><a:title>Запись $n</a:title>
                <a:link rel="enclosure" href="$androidArticle-file"/>
                <a:link rel="alternate" type="text/html" href="/blog/posts/jetpack-compose-release-$n"/>
            </a:entry>"""
        }
        val body = """<a:feed xmlns:a="http://www.w3.org/2005/Atom">$entries</a:feed>""".toByteArray()

        val result = feedArticles(androidBlog, body)
        assertEquals(FEED_ARTICLE_LIMIT, result.size)
        assertEquals(FeedArticle("$androidArticle-1", "Запись 1"), result.first())
        assertEquals(FeedArticle("$androidArticle-${FEED_ARTICLE_LIMIT}", "Запись $FEED_ARTICLE_LIMIT"), result.last())
        assertNull(CrawlSources.address(androidBlog.toString()))
    }

    @Test
    fun datesUsePublicationTimeFromTheFeed() {
        val rss = """
            <rss><channel><item><title>Морская фауна</title><link>$scienceArticle</link>
            <pubDate>Fri, 20 Nov 2026 18:46:00 +0300</pubDate></item></channel></rss>
        """.trimIndent().toByteArray()
        val atom = """
            <feed><entry><title>Библиотека Android</title><link href="$androidArticle"/>
              <published>2026-11-20T16:46:00+01:00</published><updated>2026-11-21T10:00:00Z</updated>
            </entry><entry><title>Правка</title><link href="$androidArticle-update"/>
              <updated>2026-11-21T10:00:00Z</updated>
            </entry></feed>
        """.trimIndent().toByteArray()
        val expected = Instant.parse("2026-11-20T15:46:00Z").toEpochMilli()
        assertEquals(expected, feedArticles(nPlusOne, rss).single().publishedAt)
        val publications = feedArticles(androidBlog, atom)
        assertEquals(expected, publications.first().publishedAt)
        assertNull(publications.last().publishedAt)
    }

    @Test
    fun feedChecksRobotsBeforeMakingARequest() = runBlocking {
        val touched = mutableListOf<CrawlResource>()
        val session = CrawlSession(
            network = { _, resource, _ ->
                touched += resource
                CrawlResponse(200, "text/plain", "User-agent: *\nDisallow: /rss\n".toByteArray(), null, "")
            },
            stored = { null },
            stillAllowed = { true },
        )
        assertTrue(runCatching { session.feed(nPlusOne, PageValidators()) }.exceptionOrNull() is IOException)
        assertEquals(listOf(CrawlResource.ROBOTS), touched)
    }

    @Test
    fun unchangedFeedKeepsItsOffersAndForwardsCachedValidators() = runBlocking {
        val validators = PageValidators(etag = "\"feed-version\"")
        val updated = PageValidators.from(null, "Fri, 20 Nov 2026 17:30:00 GMT")
        var sent: PageValidators? = null
        val session = CrawlSession(
            network = { _, kind, cached ->
                if (kind == CrawlResource.ROBOTS) CrawlResponse(
                    200, "text/plain", "User-agent: *\nDisallow:\n".toByteArray(), null, "",
                ) else {
                    assertEquals(CrawlResource.FEED, kind)
                    sent = cached
                    CrawlResponse(304, "", byteArrayOf(), null, "", updated)
                }
            },
            stored = { null },
            stillAllowed = { true },
        )
        val result = session.feed(nPlusOne, validators)
        assertEquals(validators, sent)
        assertNull(result.articles)
        assertEquals(PageValidators(validators.etag, updated.modified), result.validators)
    }

    @Test
    fun attachmentsAndNonXmlAreOutsideTheFeedProtocol() {
        assertTrue(CrawlResource.FEED.accepts("application/atom+xml"))
        assertTrue(CrawlResource.FEED.accepts("application/rss+xml"))
        assertEquals(false, CrawlResource.FEED.accepts("text/html"))
        assertEquals(false, CrawlResource.FEED.accepts("application/pdf"))
        assertTrue(runCatching {
            feedArticles(nPlusOne, "<html>other content</html>".toByteArray())
        }.exceptionOrNull() is IOException)
    }
}
