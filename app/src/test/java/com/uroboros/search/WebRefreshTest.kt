package com.uroboros.search

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRefreshTest {
    @Test
    fun sitemapFindsSpecificPagesAndKeepsOnlyPermittedDocuments() = runBlocking {
        val target = "https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Grid_layout/Subgrid"
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://developer.mozilla.org/en-US/docs/Web/CSS</loc><lastmod>2026-11-22</lastmod></url>
              <url><loc>${target}</loc></url>
              <url><loc>${target}#anchor</loc></url>
              <url><loc>https://ru.wikipedia.org/wiki/CSS_Subgrid</loc></url>
              <url><loc>https://developer.mozilla.org/api/CSS_Subgrid</loc></url>
              <url><loc>https://bad.invalid/CSS_Subgrid</loc></url>
              <url><loc>file:///etc/CSS_Subgrid</loc></url>
            </urlset>
        """.trimIndent()
        val zipped = ByteArrayOutputStream().also { stream ->
            GZIPOutputStream(stream).use { it.write(xml.toByteArray()) }
        }.toByteArray()

        assertEquals(listOf(target, "https://developer.mozilla.org/en-US/docs/Web/CSS"),
            sitemapCandidates(zipped, listOf("css", "subgrid")))
    }

    @Test
    fun revalidationSendsOnlyValidatorsBelongingToTheRequestedUrl() = runBlocking {
        val start = CrawlSources.address("https://developer.mozilla.org/en-US/docs/Web/Old")!!
        val target = CrawlSources.address("https://developer.mozilla.org/en-US/docs/Web/New")!!
        val previous = PageValidators("\"old\"", "Wed, 18 Nov 2026 08:10:29 GMT")
        val seen = mutableListOf<Pair<String, PageValidators>>()
        val session = CrawlSession(
            network = { url, resource, validators ->
                when {
                    resource == CrawlResource.ROBOTS -> robotsAllowed()
                    url == start -> {
                        seen += url.toString() to validators
                        CrawlResponse(302, "", byteArrayOf(), target.toString(), "")
                    }
                    else -> {
                        seen += url.toString() to validators
                        CrawlResponse(304, "", byteArrayOf(), null, "", PageValidators(etag = "W/\"new\""))
                    }
                }
            },
            stored = { if (it == target.toString()) previous else null },
            stillAllowed = { true },
        )
        assertEquals(CrawlOutcome.Unchanged(target.toString(), previous.copy(etag = "W/\"new\"")), session.download(start))
        assertEquals(listOf(start.toString() to PageValidators(), target.toString() to previous), seen)
    }

    @Test
    fun malformedValidatorsAndUnsolicitedNotModifiedAreRejected() = runBlocking {
        assertEquals(PageValidators(), PageValidators.from("\"foo\"\r\nbar", "tomorrow"))
        val target = CrawlSources.address("https://developer.mozilla.org/en-US/docs/Web/CSS")!!
        val session = CrawlSession(
            network = { _, kind, _ ->
                if (kind == CrawlResource.ROBOTS) robotsAllowed() else CrawlResponse(304, "", byteArrayOf(), null, "")
            },
            stored = { null },
            stillAllowed = { true },
        )
        assertTrue(runCatching { session.download(target) }.exceptionOrNull() is IOException)
    }

    private fun robotsAllowed() = CrawlResponse(
        200, "text/plain", "User-agent: *\nDisallow:\n".toByteArray(), null, "",
    )
}
