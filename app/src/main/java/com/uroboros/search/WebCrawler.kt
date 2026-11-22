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
        val accepted = when (url.host) {
            "ru.wikipedia.org" -> parts.size == 2 && parts[0] == "wiki" && parts[1].isNotBlank() &&
                parts[1].none { it in ":/\\" || it.isISOControl() }
            "developer.mozilla.org" -> parts.size >= 3 && parts[0] in setOf("en-US", "ru") &&
                parts[1] == "docs" && parts[2] in setOf("Web", "Learn", "Learn_web_development") &&
                parts.none { it.contains('/') || it.contains('\\') }
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

internal data class CrawlReport(val loaded: Int, val attempted: Int, val status: IndexStatus)

internal class WebCrawler(private val index: LocalWebIndex) {
    private val transport = CrawlTransport()

    suspend fun refresh(terms: List<String>, stillAllowed: () -> Boolean, forceSeeds: Boolean): CrawlReport {
        index.seed(CrawlSources.seeds, forceSeeds)
        var attempted = 0
        var loaded = 0
        val session = Session(stillAllowed)
        withTimeoutOrNull(35_000) {
            while (attempted < 16 && stillAllowed()) {
                val due = index.nextDue(System.currentTimeMillis(), if (terms.isEmpty()) 1 else 180)
                val address = due.maxByOrNull { candidate ->
                    val path = CrawlSources.address(candidate)?.pathSegments?.joinToString(" ").orEmpty()
                    terms.count { path.contains(it.trimEnd('*'), ignoreCase = true) }
                } ?: break
                attempted++
                try {
                    val url = CrawlSources.address(address) ?: error("Stored address is outside the crawl")
                    val (result, gone) = session.download(url)
                    currentCoroutineContext().ensureActive()
                    if (!stillAllowed()) break
                    val page = result?.page
                    index.record(address, page, result?.links.orEmpty(), System.currentTimeMillis() +
                        if (gone) 7 * DAY else DAY, gone || result != null && page == null)
                    if (page != null) {
                        loaded++
                        index.checked(System.currentTimeMillis())
                    }
                } catch (_: IOException) {
                    if (stillAllowed()) index.record(address, null, emptyList(), System.currentTimeMillis() + RETRY)
                }
            }
        }
        return CrawlReport(loaded, attempted, index.status())
    }

    private inner class Session(private val stillAllowed: () -> Boolean) {
        private val rulesByHost = mutableMapOf<String, SimpleRobotRules?>()
        private val lastByHost = mutableMapOf<String, Long>()

        suspend fun download(start: HttpUrl): Pair<CrawledPage?, Boolean> {
            var url = start
            repeat(4) {
                val rules = rules(url)
                if (!rules.isAllowed(url.toString()) || rules.crawlDelay > 5_000) return null to true
                val response = fetch(url, rules.crawlDelay.coerceAtLeast(850))
                when (response.code) {
                    200 -> {
                        val charset = response.type.substringAfter("charset=", "").substringBefore(';').trim('"', ' ')
                            .takeIf { it.isNotBlank() && runCatching { java.nio.charset.Charset.isSupported(it) }.getOrDefault(false) }
                        return extractPage(url, response.body, response.directives, charset) to false
                    }
                    301, 302, 303, 307, 308 -> {
                        val destination = response.location?.let(url::resolve)?.toString()?.let(CrawlSources::address)
                        url = destination ?: return null to true
                    }
                    404, 410, 415 -> return null to true
                    else -> throw IOException("Source returned HTTP " + response.code)
                }
            }
            throw IOException("Redirect limit")
        }

        private suspend fun rules(url: HttpUrl): SimpleRobotRules {
            if (!rulesByHost.containsKey(url.host)) {
                val robots = url.newBuilder().encodedPath("/robots.txt").build()
                val response = fetch(robots, 850, robots = true)
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

        private suspend fun fetch(url: HttpUrl, pause: Long, robots: Boolean = false): CrawlResponse {
            lastByHost[url.host]?.let { last ->
                val elapsed = (System.nanoTime() - last) / 1_000_000
                if (elapsed < pause) delay(pause - elapsed)
            }
            if (!stillAllowed()) throw IOException("Crawl permission revoked")
            lastByHost[url.host] = System.nanoTime()
            return transport.fetch(url, robots)
        }
    }

    private companion object {
        const val RETRY = 5 * 60_000L
        const val DAY = 24 * 60 * 60_000L
    }
}
