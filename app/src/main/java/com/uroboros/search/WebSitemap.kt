package com.uroboros.search

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.parser.Parser
import org.jsoup.parser.StreamParser

// MDN публикует эту английскую карту в своём sitemap.xml. Она лишь даёт адреса:
// саму страницу по-прежнему нужно пройти через robots.txt, HTTPS и фильтр HTML.
internal val mdnSitemap = "https://developer.mozilla.org/sitemaps/en-us/sitemap.xml.gz".toHttpUrl()
private val sitemapWord = Regex("[a-z][a-z0-9-]{2,47}")
internal fun sitemapTerms(terms: List<String>): List<String> =
    terms.map { it.trimEnd('*') }.filter(sitemapWord::matches).distinct()

private val words = Regex("[\\p{L}\\p{N}]+")
private const val MAX_SITEMAP_ENTRIES = 50_000
private const val CANDIDATE_LIMIT = 8

internal fun needsSitemapLookup(pages: List<IndexedPage>, terms: List<String>): Boolean {
    val keys = sitemapTerms(terms)
    if (keys.isEmpty()) return false
    return pages.none { page ->
        val seen = mutableSetOf<String>()
        for (part in sequenceOf(page.title, page.text)) {
            for (match in words.findAll(part.lowercase(Locale.ROOT))) {
                if (match.value in keys) seen.add(match.value)
                if (seen.size == keys.size) break
            }
        }
        seen.size == keys.size
    }
}

internal fun addressScore(url: HttpUrl, terms: List<String>): Int {
    val path = url.pathSegments.drop(if (url.host == mdnSitemap.host) 3 else 1)
    val pathWords = path.flatMap { piece -> words.findAll(piece.lowercase(Locale.ROOT)).map { it.value }.toList() }
    val last = words.findAll(path.lastOrNull().orEmpty().lowercase(Locale.ROOT)).map { it.value }.toList()
    fun List<String>.matches(term: String) = if (term.endsWith('*')) {
        any { it.startsWith(term.dropLast(1)) }
    } else term in this
    return terms.fold(0) { score, term ->
        score + when {
            last.matches(term) -> 3
            pathWords.matches(term) -> 1
            else -> 0
        }
    }
}

internal suspend fun sitemapCandidates(bytes: ByteArray, terms: List<String>): List<String> {
    if (terms.isEmpty()) return emptyList()
    val context = currentCoroutineContext()
    val raw = bytes.inputStream()
    val input = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
        GZIPInputStream(raw)
    } else raw
    data class Candidate(val url: HttpUrl, val score: Int)
    val byScore = compareByDescending<Candidate> { it.score }
        .thenBy { it.url.pathSegments.size }.thenBy { it.url.toString() }
    val best = PriorityQueue(byScore.reversed())
    val retained = mutableSetOf<HttpUrl>()
    LimitedSitemap(input).bufferedReader(Charsets.UTF_8).use { reader ->
        StreamParser(Parser.xmlParser()).use parsing@{ parser ->
            parser.parse(reader, mdnSitemap.toString())
            repeat(MAX_SITEMAP_ENTRIES) {
                context.ensureActive()
                val loc = parser.selectNext("url > loc") ?: return@parsing
                val url = CrawlSources.address(loc.text())
                // Освобождаем полностью прочитанную запись до перехода к следующей.
                loc.parent()?.remove()
                if (url == null || url.host != mdnSitemap.host || url.pathSegments.first() != "en-US") return@repeat
                val score = addressScore(url, terms)
                if (score <= 0 || !retained.add(url)) return@repeat
                best.add(Candidate(url, score))
                if (best.size > CANDIDATE_LIMIT) retained.remove(best.remove().url)
            }
        }
    }
    return best.sortedWith(byScore).map { it.url.toString() }
}

/** Предел для XML после распаковки, включая случай gzip без HTTP Content-Encoding. */
private class LimitedSitemap(input: InputStream) : FilterInputStream(input) {
    private var left = CrawlResource.SITEMAP.limit

    override fun read(): Int {
        val value = super.read()
        if (value != -1 && --left < 0) throw IOException("Sitemap too large")
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        val count = super.read(buffer, offset, minOf(size, left + 1))
        if (count > 0) left -= count
        if (left < 0) throw IOException("Sitemap too large")
        return count
    }
}
