package com.uroboros.search

import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

internal data class WebFeedSource(
    val name: String,
    val feed: HttpUrl,
    val articleHosts: Set<String>,
    val articlePath: Regex,
    val topics: Regex,
    val aliases: List<String>,
    val general: Boolean = false,
) {
    fun accepts(url: HttpUrl): Boolean = url.host in articleHosts && articlePath.matches(url.encodedPath)
}

/** Редакция, лента и допустимые адреса статей задаются вместе. */
internal val webSources: List<WebFeedSource> = listOf(
    source("Интерфакс", "https://www.interfax.ru/rss", setOf("www.interfax.ru", "interfax.ru"),
        "/[a-z]+/[0-9]+/?", "политик|экономик|мир|росси", "Интерфакс", "interfax.ru", general = true),
    source("DW", "https://rss.dw.com/xml/rss-ru-all", setOf("www.dw.com", "dw.com"),
        "/ru/[^/]+/a-[0-9]+/?", "политик|экономик|мир|европ", "DW", "dw.com", general = true),
    source("РБК", "https://rssexport.rbc.ru/rbcnews/news/30/full.rss",
        setOf("www.rbc.ru", "rbc.ru", "quote.rbc.ru", "sport.rbc.ru"),
        "/(?:(?:rbcfreenews/)|(?:[a-z_-]+/[0-9]{2}/[0-9]{2}/[0-9]{4}/))[0-9a-f]{12,}/?",
        "экономик|рынок|бизнес|финанс|политик", "РБК", "rbc.ru", general = true),
    source("N+1", "https://nplus1.ru/rss", setOf("nplus1.ru"),
        "/(?:news|material|blog)/[0-9]{4}/[0-9]{2}/[0-9]{2}/[a-zA-Z0-9_-]+/?",
        "наук|космос|телескоп|физик|биологи|медицин", "N+1", "nplus1.ru", "Н+1"),
    source("Android Developers", "https://developer.android.com/blog/atom.xml", setOf("developer.android.com"),
        "/blog/posts/[a-zA-Z0-9_-]+/?", "android|андроид|kotlin|котлин|jetpack",
        "Android Developers", "developer.android.com"),
    source("iXBT", "https://www.ixbt.com/export/news/rss.xml", setOf("www.ixbt.com", "ixbt.com"),
        "/news/[0-9]{4}/[0-9]{2}/[0-9]{2}/[^/]+[.]html",
        "android|андроид|технолог|програм|разработ|софт|смартфон", "iXBT", "ixbt.com"),
)

private fun source(
    name: String, feed: String, hosts: Set<String>, path: String, topics: String,
    vararg aliases: String, general: Boolean = false,
) = WebFeedSource(name, feed.toHttpUrl(), hosts, Regex(path), Regex(topics, RegexOption.IGNORE_CASE),
    aliases.toList(), general)

internal val webFeeds: List<HttpUrl> = webSources.map { it.feed }

internal data class FeedArticle(val url: String, val title: String, val publishedAt: Long? = null)
internal data class FeedState(
    val due: Long = 0, val validators: PageValidators = PageValidators(),
    val checkedAt: Long? = null, val failed: Boolean = false,
)
internal data class FeedUpdate(
    val articles: List<FeedArticle>?, val validators: PageValidators, val clearHistory: Boolean = false,
)

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
    val source = webSources.firstOrNull { it.feed == feed } ?: throw IOException("Unknown feed")
    val atom = root.localName() == "feed"
    return entries.take(FEED_PARSE_LIMIT).mapNotNull { entry ->
        val link = if (atom) {
            entry.children().firstOrNull {
                it.localName() == "link" && it.attr("rel") in setOf("", "alternate") &&
                    it.attr("type") in setOf("", "text/html")
            }?.attr("href")
        } else entry.childNamed("link")?.text()
        val url = link?.takeIf { it.isNotBlank() }?.let(feed::resolve)?.let { address ->
            if (!source.accepts(address)) return@let null
            val clean = address.newBuilder()
            for (param in address.queryParameterNames) {
                if (param.startsWith("utm_") || (source.name == "DW" && param == "maca")) {
                    clean.removeAllQueryParameters(param)
                }
            }
            clean.build().toString().let(CrawlSources::address)
        } ?: return@mapNotNull null
        val title = entry.childNamed("title")?.text().orEmpty()
            .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
            .take(160).trim()
        val date = entry.childNamed(if (atom) "published" else "pubdate")?.text()
        val publishedAt = date?.takeIf { it.length <= 80 }?.let {
            runCatching {
                if (atom) OffsetDateTime.parse(it).toInstant().toEpochMilli()
                else ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
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
