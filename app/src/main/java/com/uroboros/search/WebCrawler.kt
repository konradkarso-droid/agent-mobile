package com.uroboros.search

import crawlercommons.robots.SimpleRobotRules
import crawlercommons.robots.SimpleRobotRulesParser
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

internal object CrawlSources {
    private val year = Regex("[0-9]{4}")
    private val month = Regex("0[1-9]|1[0-2]")
    private val day = Regex("0[1-9]|[12][0-9]|3[01]")
    private val slug = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,199}")

    // Открытые источники первой версии. Ссылки расширяют обход внутри этих областей.
    val seeds = listOf(
        "https://ru.wikipedia.org/wiki/Информатика",
        "https://developer.mozilla.org/en-US/docs/Web",
        "https://ru.wikipedia.org/wiki/История",
        "https://ru.wikipedia.org/wiki/Астрономия",
        "https://ru.wikipedia.org/wiki/Электроника",
        "https://ru.wikipedia.org/wiki/Кулинария",
        "https://ru.wikipedia.org/wiki/Садоводство",
        "https://ru.wikipedia.org/wiki/Настольная_игра",
    ).map { checkNotNull(address(it)).toString() }

    fun address(value: String): HttpUrl? {
        val url = value.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedQuery != null || url.encodedPath.length > 440
        ) return null
        val parts = url.pathSegments
        if (parts.any { part -> part.any { it in "/\\" || it.isISOControl() } }) return null
        val accepted = when (url.host) {
            "ru.wikipedia.org" -> parts.size == 2 && parts[0] == "wiki" && parts[1].isNotBlank() &&
                ':' !in parts[1]
            "developer.mozilla.org" -> parts.size >= 3 && parts[0] in setOf("en-US", "ru") &&
                parts[1] == "docs" && parts[2] in setOf("Web", "Learn", "Learn_web_development")
            "nplus1.ru" -> parts.size == 5 && parts[0] in setOf("news", "material", "blog") &&
                parts[1].matches(year) && parts[2].matches(month) && parts[3].matches(day) && parts[4].matches(slug)
            "developer.android.com" -> parts.size == 3 && parts[0] == "blog" && parts[1] == "posts" &&
                parts[2].matches(slug)
            else -> false
        }
        return if (accepted) url.newBuilder().fragment(null).build() else null
    }
}

internal data class CrawledPage(val page: IndexedPage?, val links: List<String>)

private val paragraphs = setOf("h1", "h2", "h3", "h4", "h5", "h6", "p", "li", "pre", "dt", "dd", "th", "td", "blockquote")
private val invisibleStyle = Regex("""(?:^|;)\s*(?:display\s*:\s*none|visibility\s*:\s*hidden)(?:\s*!important)?\s*(?:;|$)""", RegexOption.IGNORE_CASE)
private val separators = Regex("[,\\s]+")
private fun directiveWords(text: String) = text.lowercase(Locale.ROOT).split(separators)
private fun clean(text: String) = text.filterNot {
    it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
}.trim()

internal fun extractPage(url: HttpUrl, body: ByteArray, directives: String = "", charset: String? = null): CrawledPage {
    val doc = Jsoup.parse(body.inputStream(), charset, url.toString())
    val rules = (directiveWords(directives) + doc.select("meta[name=robots], meta[name=uroboroslocalsearch]")
        .flatMap { directiveWords(it.attr("content")) }).toSet()
    doc.select("script, style, template, noscript, iframe, object, embed, svg, form, nav, footer, aside, [hidden], [aria-hidden=true], .mw-editsection").remove()
    for (element in doc.select("[style]")) {
        if (invisibleStyle.containsMatchIn(element.attr("style"))) element.remove()
    }
    val root = doc.selectFirst("#mw-content-text") ?: doc.selectFirst("main, article, [role=main]") ?: doc.body()
    val links = if ("none" in rules || "nofollow" in rules) emptyList() else
        root.select("a[href]").asSequence()
            .filter { "nofollow" !in directiveWords(it.attr("rel")) }
            // Ссылки разрешаются от фактического адреса, даже если HTML предлагает свой <base>.
            .mapNotNull { url.resolve(it.attr("href"))?.toString()?.let(CrawlSources::address)?.toString() }
            .filter { it != url.toString() }.distinct().take(60).toList()
    val text = buildString {
        for (block in root.select(paragraphs.joinToString(","))) {
            if (length >= 80_000) break
            if (block.children().any { it.normalName() in paragraphs }) continue
            val line = clean(block.text())
            if (line.isNotBlank()) {
                val marked = if (block.normalName() in setOf("h1", "h2", "h3", "h4", "h5", "h6")) "# " + line else line
                appendLine(marked.take(80_000 - length))
            }
        }
        if (isEmpty()) append(clean(root.text()).take(80_000))
    }.trim()
    val page = if (text.length >= 40 && "none" !in rules && "noindex" !in rules)
        IndexedPage(url.toString(), clean(doc.title()).take(140).ifBlank { url.host }, text) else null
    return CrawledPage(page, links)
}

internal sealed interface CrawlOutcome {
    val url: String

    data class Loaded(override val url: String, val content: CrawledPage, val validators: PageValidators) : CrawlOutcome
    data class Unchanged(override val url: String, val validators: PageValidators) : CrawlOutcome
    data class Removed(override val url: String) : CrawlOutcome
}

internal data class CrawlReport(
    val loaded: Int,
    val unchanged: Int,
    val attempted: Int,
    val status: IndexStatus,
    val feedsAttempted: Int = 0,
    val feedsChecked: Int = 0,
    val timedOut: Boolean = false,
)

private const val DAY = 24 * 60 * 60_000L
private const val RETRY = 5 * 60_000L
private const val CRAWL_ATTEMPTS = 16
private const val FEED_CHECK_INTERVAL = 6 * 60 * 60_000L
private const val FEED_TARGET_LIMIT = 6

private data class FeedDiscovery(val targets: List<String>, val attempted: Int, val checked: Int)


internal class WebCrawler(
    private val index: LocalWebIndex,
    private val network: suspend (HttpUrl, CrawlResource, PageValidators) -> CrawlResponse = CrawlTransport()::fetch,
) {
    private var sitemap: Pair<Long, ByteArray>? = null

    suspend fun refresh(
        terms: List<String>, stillAllowed: () -> Boolean, forceSeeds: Boolean, window: PublicationWindow? = null,
    ): CrawlReport {
        if (window == null) index.seed(CrawlSources.seeds, retryUnindexed = forceSeeds)
        val visited = mutableSetOf<String>()
        var attempted = 0
        var loaded = 0
        var unchanged = 0
        val session = CrawlSession(network, index::validators, stillAllowed)
        var feeds = FeedDiscovery(emptyList(), 0, 0)
        val completed = withTimeoutOrNull(35_000) {
            val sitemapTargets = if (window == null) {
                withTimeoutOrNull(10_000) { discoverFromSitemap(session, terms) }.orEmpty()
            } else emptyList()
            feeds = discoverFromFeeds(session, terms, stillAllowed, window)
            val targets = (feeds.targets + sitemapTargets).distinct()
            while (attempted < CRAWL_ATTEMPTS && stillAllowed()) {
                val now = System.currentTimeMillis()
                fun revisit() = index.nextDue(if (forceSeeds) now + DAY else now, visited.size + 1, indexed = true)
                    .firstOrNull { it !in visited }
                fun discover(): String? = targets.firstOrNull { it !in visited }
                    ?: index.nextDue(now, visited.size + if (terms.isEmpty()) 1 else 180, indexed = false)
                        .asSequence().filterNot { it in visited }.maxByOrNull { address ->
                            CrawlSources.address(address)?.let { addressScore(it, terms) } ?: 0
                        }
                val address = when {
                    window != null -> targets.firstOrNull { it !in visited }
                    attempted % 3 == 0 -> revisit() ?: discover()
                    else -> discover() ?: revisit()
                }
                if (address == null) break
                visited.add(address)
                attempted++
                try {
                    val url = CrawlSources.address(address) ?: error("Stored address is outside the crawl")
                    val outcome = session.download(url)
                    currentCoroutineContext().ensureActive()
                    if (!stillAllowed()) break
                    visited.add(outcome.url)
                    val at = System.currentTimeMillis()
                    index.record(address, outcome, at + if (outcome is CrawlOutcome.Removed) 7 * DAY else DAY, at)
                    when (outcome) {
                        is CrawlOutcome.Loaded -> if (outcome.content.page != null) loaded++
                        is CrawlOutcome.Unchanged -> unchanged++
                        is CrawlOutcome.Removed -> Unit
                    }
                } catch (_: IOException) {
                    if (stillAllowed()) index.defer(address, System.currentTimeMillis() + RETRY)
                }
            }
            true
        } ?: false
        return CrawlReport(loaded, unchanged, attempted, index.status(), feeds.attempted, feeds.checked, timedOut = !completed)
    }

    private suspend fun discoverFromFeeds(
        session: CrawlSession, terms: List<String>, stillAllowed: () -> Boolean, window: PublicationWindow?,
    ): FeedDiscovery {
        var attempted = 0
        var checked = 0
        for (feed in webFeeds) {
            if (!stillAllowed()) break
            val key = feed.toString()
            val previous = index.feedState(key)
            if (previous.due > System.currentTimeMillis()) continue
            attempted++
            val update = try {
                withTimeoutOrNull(5_000) { session.feed(feed, previous.validators) }
            } catch (_: IOException) {
                null
            }
            currentCoroutineContext().ensureActive()
            if (!stillAllowed()) break
            val at = System.currentTimeMillis()
            if (update == null) {
                index.recordFeed(key, FeedUpdate(null, previous.validators), at + RETRY)
            } else {
                checked++
                index.recordFeed(key, update, at + FEED_CHECK_INTERVAL)
            }
        }
        val now = System.currentTimeMillis()
        if (!stillAllowed()) return FeedDiscovery(emptyList(), attempted, checked)
        val urls = index.feedCandidates(now)
            .filter { window == null || window.contains(it.publishedAt) }
            .map { article -> article to feedScore(article, terms) }
            .filter { window != null || terms.isEmpty() || it.second > 0 }
            .sortedWith(compareByDescending<Pair<FeedArticle, Int>> { it.second }.thenByDescending {
                if (window == null) 0L else it.first.publishedAt ?: 0L
            })
            .map { it.first.url }.distinct().take(FEED_TARGET_LIMIT)
        return FeedDiscovery(index.queueFeedTargets(urls, now), attempted, checked)
    }

    private suspend fun discoverFromSitemap(session: CrawlSession, terms: List<String>): List<String> {
        val technical = sitemapTerms(terms)
        if (technical.isEmpty()) return emptyList()
        return try {
            val now = System.currentTimeMillis()
            val bytes = sitemap?.takeIf { now - it.first in 0 until DAY }?.second ?: session.sitemap()
                ?: return emptyList()
            val candidates = sitemapCandidates(bytes, technical)
            if (sitemap?.second !== bytes) sitemap = now to bytes
            index.seed(candidates)
            index.dueTargets(candidates, now)
        } catch (_: IOException) {
            emptyList()
        }
    }
}

internal class CrawlSession(
    private val network: suspend (HttpUrl, CrawlResource, PageValidators) -> CrawlResponse,
    private val stored: (String) -> PageValidators?,
    private val stillAllowed: () -> Boolean,
) {
    private val rulesByHost = mutableMapOf<String, SimpleRobotRules?>()
    private val lastByHost = mutableMapOf<String, Long>()

    suspend fun download(start: HttpUrl): CrawlOutcome {
        var url = start
        repeat(4) {
            val rules = rules(url)
            if (!rules.isAllowed(url.toString())) return CrawlOutcome.Removed(url.toString())
            if (rules.crawlDelay > 5_000) throw IOException("Source requests a longer pause than one search allows")
            val cached = stored(url.toString()) ?: PageValidators()
            val response = fetch(url, rules.crawlDelay.coerceAtLeast(850), CrawlResource.PAGE, cached)
            when (response.code) {
                200 -> {
                    val charset = response.type.substringAfter("charset=", "").substringBefore(';').trim('"', ' ')
                        .takeIf { it.isNotBlank() && runCatching { java.nio.charset.Charset.isSupported(it) }.getOrDefault(false) }
                    val page = extractPage(url, response.body, response.directives, charset)
                    return CrawlOutcome.Loaded(url.toString(), page, response.validators)
                }
                304 -> {
                    if (!cached.present) throw IOException("Not modified without a cached page")
                    if (directiveWords(response.directives).any { it == "noindex" || it == "none" }) {
                        return CrawlOutcome.Removed(url.toString())
                    }
                    return CrawlOutcome.Unchanged(url.toString(), cached.updatedBy(response.validators))
                }
                301, 302, 303, 307, 308 -> {
                    val destination = response.location?.let(url::resolve)?.toString()?.let(CrawlSources::address)
                    url = destination ?: return CrawlOutcome.Removed(url.toString())
                }
                404, 410, 415 -> return CrawlOutcome.Removed(url.toString())
                else -> throw IOException("Source returned HTTP " + response.code)
            }
        }
        throw IOException("Redirect limit")
    }

    suspend fun feed(url: HttpUrl, cached: PageValidators): FeedUpdate {
        require(url in webFeeds)
        val rules = rules(url)
        if (!rules.isAllowed(url.toString()) || rules.crawlDelay > 5_000) {
            throw IOException("Feed outside the permitted crawl")
        }
        val response = fetch(url, rules.crawlDelay.coerceAtLeast(850), CrawlResource.FEED, cached)
        val follow = directiveWords(response.directives).none { it in setOf("none", "nofollow") }
        return when (response.code) {
            200 -> FeedUpdate(if (follow) feedArticles(url, response.body) else emptyList(), response.validators)
            304 -> {
                if (!cached.present) throw IOException("Not modified without a cached feed")
                FeedUpdate(if (follow) null else emptyList(), cached.updatedBy(response.validators))
            }
            else -> throw IOException("Feed returned HTTP " + response.code)
        }
    }

    suspend fun sitemap(): ByteArray? {
        val rules = rules(mdnSitemap)
        if (!rules.isAllowed(mdnSitemap.toString()) || rules.crawlDelay > 5_000) return null
        val result = fetch(mdnSitemap, rules.crawlDelay.coerceAtLeast(850), CrawlResource.SITEMAP)
        return result.body.takeIf { result.code == 200 && it.isNotEmpty() }
    }

    private suspend fun rules(url: HttpUrl): SimpleRobotRules {
        if (!rulesByHost.containsKey(url.host)) {
            val robots = url.newBuilder().encodedPath("/robots.txt").build()
            val response = fetch(robots, 850, CrawlResource.ROBOTS)
            val parser = SimpleRobotRulesParser()
            rulesByHost[url.host] = when (response.code) {
                200 -> parser.parseContent(
                    robots.toString(), response.body, response.type, listOf(CRAWLER_NAME.lowercase(Locale.ROOT)),
                )
                404, 410 -> parser.failedFetch(404)
                else -> null
            }
        }
        return rulesByHost[url.host] ?: throw IOException("Robots rules unavailable")
    }

    private suspend fun fetch(
        url: HttpUrl, pause: Long, resource: CrawlResource, validators: PageValidators = PageValidators(),
    ): CrawlResponse {
        lastByHost[url.host]?.let { last ->
            val elapsed = (System.nanoTime() - last) / 1_000_000
            if (elapsed < pause) delay(pause - elapsed)
        }
        if (!stillAllowed()) throw IOException("Crawl permission revoked")
        lastByHost[url.host] = System.nanoTime()
        return network(url, resource, validators)
    }
}
