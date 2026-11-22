package com.uroboros.search

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWebSearchTest {
    @Test
    fun allowedSourcesRejectPrivateAndUnrelatedAddresses() {
        for (address in listOf(
            "http://ru.wikipedia.org/wiki/HTML",
            "https://127.0.0.1/",
            "https://ru.wikipedia.org.evil.invalid/wiki/HTML",
            "https://ru.wikipedia.org@evil.invalid/wiki/HTML",
            "https://ru.wikipedia.org:444/wiki/HTML",
            "https://ru.wikipedia.org/wiki/%D0%A1%D0%BB%D1%83%D0%B6%D0%B5%D0%B1%D0%BD%D0%B0%D1%8F%3AHTML",
            "https://ru.wikipedia.org/wiki/HTML?oldid=123",
            "https://developer.mozilla.org/en-US/docs/Web/%2f",
        )) assertNull(address, CrawlSources.address(address))
        assertNotNull(CrawlSources.address("https://ru.wikipedia.org/wiki/HTML#title"))
        for (ip in listOf("127.0.0.1", "10.0.0.1", "169.254.169.254", "100.65.0.1", "192.168.1.2", "::1", "fc00::1", "2001:db8::1")) {
            assertFalse(ip, isPublicWebAddress(InetAddress.getByName(ip)))
        }
        assertTrue(isPublicWebAddress(InetAddress.getByName("93.184.216.34")))
    }

    @Test
    fun extractionKeepsStructureAndIgnoresHiddenBlocksAndExternalLinks() {
        val address = CrawlSources.address("https://ru.wikipedia.org/wiki/HTML")!!
        val html = """
            <html><head><title>Руководство</title><base href="https://example.invalid/"></head>
            <body><main><h2>Ограничения</h2><p>Для сброса конфигурации предварительно сохраните данные.</p>
            <script>script-marker</script><div hidden><p>hidden-marker</p></div>
            <div style="display:none"><p>css-marker</p></div><nav><p>nav-marker</p></nav>
            <p><a href="/wiki/Web">Продолжение</a> <a href="https://example.invalid/file">Снаружи</a>
            <a href="/wiki/Archive" rel="nofollow">Архив</a></p></main></body></html>
        """.trimIndent().toByteArray()
        val parsed = extractPage(address, html, charset = "UTF-8")
        assertTrue(parsed.page!!.text.startsWith("# Ограничения"))
        assertTrue(parsed.page.text.contains("сохраните данные"))
        assertFalse(parsed.page.text.contains("-marker"))
        assertEquals(listOf("https://ru.wikipedia.org/wiki/Web"), parsed.links)
        assertNull(extractPage(address, html, directives = "noindex, nofollow", charset = "UTF-8").page)
        assertTrue(extractPage(address, html, directives = "noindex, nofollow", charset = "UTF-8").links.isEmpty())
    }

    @Test
    fun readerOpensWholeChosenSectionsAndPreservesConditions() = runBlocking {
        val page = IndexedPage("https://ru.wikipedia.org/wiki/HTML", "Руководство", """
            # Сброс
            Нажмите кнопку сброса настроек.
            # Условия
            Данные не сохраняются, если резервная копия не сделана.
        """.trimIndent())
        val requests = mutableListOf<JSONObject>()
        val reader = PageReader { _, user ->
            requests += JSONObject(user)
            if (requests.size == 1) "2,1" else "Сначала сделайте копию [1]."
        }
        assertEquals("Сначала сделайте копию [1].", reader.answer("Как сделать сброс?", listOf(page)))
        val opened = requests[1].getJSONArray("источники")
        assertEquals(2, opened.length())
        assertTrue(opened.getJSONObject(1).getString("text").contains("не сохраняются"))
        assertEquals(emptyList<Int>(), parseSectionChoice("1, открой ссылку", listOf(0)))
        assertEquals(listOf(0), parseSectionChoice("1,700", listOf(0)))
    }

    @Test
    fun outlineIncludesALateSectionWithAMatchingHeadingAndShowsItsMatchingParagraph() = runBlocking {
        val matching = "Копия фотографий сохраняется на отдельном носителе."
        val body = buildString {
            repeat(26) {
                appendLine("# Отступление ${it + 1}")
                appendLine("Копия карточки учёта хранится в архиве.")
            }
            appendLine("# Резервная копия")
            appendLine("Вводный абзац.")
            appendLine(matching)
            appendLine("Заключительный абзац.")
        }
        val page = IndexedPage("https://ru.wikipedia.org/wiki/HTML", "Руководство", body)
        val reader = PageReader { system, user ->
            val input = JSONObject(user)
            if (system.startsWith("Выбери")) {
                val outline = input.getJSONArray("разделы")
                val last = (0 until outline.length()).map(outline::getJSONObject)
                    .single { it.getString("title") == "Резервная копия" }
                assertTrue(last.getString("preview").contains(matching))
                last.getInt("id").toString()
            } else {
                val source = input.getJSONArray("источники").getJSONObject(0)
                assertEquals(1, source.getInt("source"))
                assertTrue(source.getString("text").contains(matching))
                "Фотографии сохраняют на отдельном носителе [1]."
            }
        }

        assertEquals("Фотографии сохраняют на отдельном носителе [1].",
            reader.answer("Резервная копия фотографии", listOf(page)))
    }

    @Test
    fun longPageIsReadInOrderWithSeveralModelCalls() = runBlocking {
        val body = (1..3).joinToString("\n") { "# Раздел $it\n" + "Текст $it. ".repeat(400) }
        val page = IndexedPage("https://ru.wikipedia.org/wiki/HTML", "Руководство", body)
        val read = mutableListOf<String>()
        val reader = PageReader { _, user ->
            val data = JSONObject(user)
            val sources: JSONArray? = data.optJSONArray("источники")
            if (sources == null) "Итог [1]" else {
                assertEquals(1, sources.length())
                read += sources.getJSONObject(0).getString("section")
                "Сведения [1]"
            }
        }
        assertEquals("Итог [1]", reader.answer("прочитай всю статью", listOf(page)))
        assertEquals(listOf("Раздел 1", "Раздел 2", "Раздел 3"), read)
    }
    @Test
    fun combiningReadingNotesIncludesTheLastSection() = runBlocking {
        val body = (1..14).joinToString("\n") { "# Раздел $it\n" + "Текст $it. ".repeat(360) }
        val page = IndexedPage("https://ru.wikipedia.org/wiki/HTML", "Руководство", body)
        val reader = PageReader { system, user ->
            val input = JSONObject(user)
            val sources = input.optJSONArray("источники")
            when {
                sources != null -> sources.getJSONObject(0).getString("section") + " заметка".repeat(50)
                system.startsWith("Сведи") -> {
                    val group = input.getJSONArray("заметки")
                    group.getString(group.length() - 1).substringBefore(" заметка")
                }
                else -> {
                    assertTrue(input.getJSONArray("заметки").toString().contains("Раздел 14"))
                    "Итог [1]"
                }
            }
        }
        assertEquals("Итог [1]", reader.answer("прочитай всю статью", listOf(page)))
    }

}
