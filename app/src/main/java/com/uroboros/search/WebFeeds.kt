package com.uroboros.search

import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** Ленты объявлены в коде. Из каждой принимаются ссылки на статьи её сайта. */
internal val webFeeds: List<HttpUrl> = listOf(
    "https://nplus1.ru/rss".toHttpUrl(),
    "https://developer.android.com/blog/atom.xml".toHttpUrl(),
)

internal data class FeedArticle(val url: String, val title: String, val publishedAt: Long? = null)
internal data class FeedState(val due: Long = 0, val validators: PageValidators = PageValidators())
internal data class FeedUpdate(val articles: List<FeedArticle>?, val validators: PageValidators)

internal const val FEED_ARTICLE_LIMIT = 24
private const val FEED_PARSE_LIMIT = 100

/** Читает метаданные XML локально. Тело статьи берётся обычным обходчиком. */
internal fun feedArticles(feed: HttpUrl, body: ByteArray): List<FeedArticle> {
    if (body.isEmpty() || body.size > CrawlResource.FEED.limit) throw IOException("Invalid feed size")
    val doc = Jsoup.parse(body.inputStream(), null, feed.toString(), Parser.xmlParser())
    val root = doc.children().firstOrNull() ?: throw IOException("Empty web feed")
    val entries = when (root.localName()) {
        "rss" -> root.childNamed("channel")?.children()?.asSequence()?.filter { it.localName() == "item" }
        "feed" -> root.children().asSequence().filter { it.localName() == "entry" }
        else -> null
    } ?: throw IOException("Unrecognized RSS or Atom feed")
    val atom = root.localName() == "feed"
    return entries.take(FEED_PARSE_LIMIT).mapNotNull { entry ->
        val link = if (atom) {
            entry.children().firstOrNull {
                it.localName() == "link" && it.attr("rel") in setOf("", "alternate") &&
                    it.attr("type") in setOf("", "text/html")
            }?.attr("href")
        } else entry.childNamed("link")?.text()
        val url = link?.takeIf { it.isNotBlank() }?.let(feed::resolve)?.toString()?.let(CrawlSources::address)
        if (url == null || url.host != feed.host) return@mapNotNull null
        val title = entry.childNamed("title")?.text().orEmpty()
            .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
            .take(160).trim()
        val date = if (atom) {
            entry.childNamed("published")?.text() ?: entry.childNamed("updated")?.text()
        } else {
            entry.childNamed("pubdate")?.text() ?: entry.childNamed("date")?.text()
        }
        val publishedAt = date?.takeIf { it.length <= 80 }?.let {
            runCatching {
                if (atom) OffsetDateTime.parse(it).toInstant().toEpochMilli()
                else runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                    .getOrElse { _ -> Instant.parse(it).toEpochMilli() }
            }.getOrNull()
        }
        FeedArticle(url.toString(), title, publishedAt)
    }.distinctBy { it.url }.take(FEED_ARTICLE_LIMIT).toList()
}

private fun Element.localName(): String = normalName().substringAfter(':')
private fun Element.childNamed(name: String): Element? = children().firstOrNull { it.localName() == name }

internal fun feedScore(article: FeedArticle, terms: List<String>): Int {
    val url = CrawlSources.address(article.url) ?: return 0
    val title = searchTerms(article.title)
    return addressScore(url, terms) + 3 * terms.count { term ->
        if (term.endsWith('*')) title.any { it.startsWith(term.dropLast(1)) }
        else term in title
    }
}
