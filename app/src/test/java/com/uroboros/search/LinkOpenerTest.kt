package com.uroboros.search

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkOpenerTest {

    // --- Приём адреса ---

    @Test
    fun `принимаются https-адреса с запросами и без схемы`() {
        assertNotNull(LinkAdmission.admit("https://example.com/page?utm=1&id=2"))
        val bare = LinkAdmission.admit("nplus1.ru/news/2026/11/26/polyet")
        assertEquals("https://nplus1.ru/news/2026/11/26/polyet", bare?.toString())
        // Хост нормализуется в нижний регистр, путь сохраняется как написан.
        assertEquals("https://example.com/Path", LinkAdmission.admit("HTTPS://EXAMPLE.com/Path")?.toString())
    }

    @Test
    fun `не-https, пароли, порты и чужие схемы не принимаются — молчат`() {
        for (address in listOf(
            "http://example.com/page",
            "https://user:pass@example.com/",
            "https://example.com:8443/",
            "mailto:someone@example.com",
            "tg://chat",
            "localhost:8080/admin",
            "",
            "   ",
            "https://example.com/a b",
            "https://example.com/" + "a".repeat(LinkAdmission.MAX_URL),
        )) assertNull(address, LinkAdmission.admit(address))
    }

    @Test
    fun `якорь срезается, путь сохраняется`() {
        val url = LinkAdmission.admit("https://example.com/page#section")
        assertEquals("https://example.com/page", url?.toString())
    }

    // --- Открытие страницы ---

    private fun html(title: String, vararg paragraphs: String) = (
        "<html><head><title>$title</title></head><body><main>" +
            paragraphs.joinToString("") { "<p>$it</p>" } + "</main></body></html>"
        ).toByteArray()

    private fun response(
        code: Int, body: ByteArray = ByteArray(0), location: String? = null,
        type: String = "text/html; charset=utf-8", directives: String = "",
    ) = CrawlResponse(code, type, body, location, directives)

    private class Ledger(vararg rules: Pair<String, CrawlResponse>) {
        val asked = mutableListOf<String>()
        private val remaining = rules.toMutableList()
        suspend fun fetch(url: HttpUrl, resource: CrawlResource, validators: PageValidators): CrawlResponse {
            asked += url.toString()
            val rule = remaining.indexOfFirst { it.first == url.toString() }
            if (rule >= 0) return remaining.removeAt(rule).second
            throw IOException("нет правила для $url")
        }
    }

    @Test
    fun `страница открывается с адресом, заголовком и текстом`() = runBlocking {
        val opener = LinkOpener { url, _, _ ->
            response(200, html("Пример", "Достаточно длинный абзац страницы, чтобы разбор его принял.", "Второй абзац для полноты."))
                .also { assertEquals("https://example.com/a", url.toString()) }
        }
        val opened = opener.open(LinkAdmission.admit("https://example.com/a")!!) { true }
        assertTrue(opened.toString(), opened is LinkOpener.Open.Page)
        opened as LinkOpener.Open.Page
        assertEquals("https://example.com/a", opened.url)
        assertEquals("Пример", opened.title)
        assertTrue(opened.text.contains("Достаточно длинный абзац"))
    }

    @Test
    fun `переадресация идёт по шагам и заканчивается страницей`() = runBlocking {
        val ledger = Ledger(
            "https://example.com/a" to response(301, location = "https://example.com/b"),
            "https://example.com/b" to response(302, location = "/c"),
            "https://example.com/c" to response(200, body = html("Финал", "Итоговая страница с достаточным для разбора текстом.")),
        )
        val opened = LinkOpener(ledger::fetch).open(LinkAdmission.admit("https://example.com/a")!!) { true }
        assertTrue(opened.toString(), opened is LinkOpener.Open.Page)
        assertEquals("https://example.com/c", (opened as LinkOpener.Open.Page).url)
        assertEquals(listOf("https://example.com/a", "https://example.com/b", "https://example.com/c"), ledger.asked)
    }

    @Test
    fun `переадресация за пределы правил открытия не проходит`() = runBlocking {
        val opener = LinkOpener { _, _, _ -> response(301, location = "http://example.com/unsafe") }
        val opened = opener.open("https://example.com/a".toHttpUrl()) { true }
        assertTrue((opened as LinkOpener.Open.Refused).why.contains("переадресация"))
    }

    @Test
    fun `кольцо переадресаций обрывается отказом`() = runBlocking {
        val opener = LinkOpener { url, _, _ -> response(301, location = url.toString()) }
        val opened = opener.open("https://example.com/a".toHttpUrl()) { true }
        assertTrue((opened as LinkOpener.Open.Refused).why.contains("переадресаций"))
    }

    @Test
    fun `нет страницы и не-страница — отказ с причиной`() = runBlocking {
        val missing = LinkOpener { _, _, _ -> response(404) }
        assertTrue((missing.open("https://example.com/a".toHttpUrl()) { true } as LinkOpener.Open.Refused).why.contains("404"))
        val notHtml = LinkOpener { _, _, _ -> response(415) }
        assertTrue((notHtml.open("https://example.com/a".toHttpUrl()) { true } as LinkOpener.Open.Refused).why.contains("HTML"))
    }

    @Test
    fun `закрытая от чтения страница отказом`() = runBlocking {
        val opener = LinkOpener { _, _, _ ->
            response(200, html("Закрыто", "Текста достаточно, но страница просит себя не читать."), directives = "noindex, nofollow")
        }
        val opened = opener.open("https://example.com/a".toHttpUrl()) { true }
        assertTrue((opened as LinkOpener.Open.Refused).why.contains("закрыта"))
    }

    @Test
    fun `снятое разрешение останавливает открытие до сети`() = runBlocking {
        var asked = 0
        val opener = LinkOpener { _, _, _ -> asked++; response(200, html("Не должно было дойти", "Этот текст не должен читаться.")) }
        val opened = opener.open("https://example.com/a".toHttpUrl()) { false }
        assertTrue((opened as LinkOpener.Open.Refused).why.contains("разрешение"))
        assertEquals(0, asked)
    }

    @Test
    fun `сбой сети — отказ, а не тишина`() = runBlocking {
        val opener = LinkOpener { _, _, _ -> throw IOException("нет маршрута") }
        val opened = opener.open("https://example.com/a".toHttpUrl()) { true }
        assertTrue((opened as LinkOpener.Open.Refused).why.contains("не достучался"))
    }

    // --- Бюджеты прямого открытия ---

    @Test
    fun `весь вызов прямого открытия под пределом одной ссылки`() {
        // Объявленная граница: один запрос не занимает всё время открытия целиком —
        // за переадресацией должно остаться место в пределе SearchPlatform.OPEN_LIMIT_MS.
        assertTrue(FetchBudgets.DIRECT.callMs < SearchPlatform.OPEN_LIMIT_MS)
    }

    @Test
    fun `обход не разжат — его бюджеты остаются короче прямого открытия`() {
        // Тишина: растяжение бюджетов прямого открытия не должно расширить
        // вежливый обход — шесть секунд обхода остаются короче восемнадцати прямых.
        assertTrue(FetchBudgets.CRAWL.callMs < FetchBudgets.DIRECT.callMs)
        assertTrue(!FetchBudgets.CRAWL.retryConnection)
        assertTrue(FetchBudgets.DIRECT.retryConnection)
    }

    // --- Выдержка при недоступной модели ---

    @Test
    fun `выдержка берёт первые строки без заголовков`() {
        val text = "# Заголовок\n\nПервый абзац.\nВторой абзац.\n\n# Ещё заголовок\nТретий."
        assertEquals("Первый абзац.\nВторой абзац.\nТретий.", pageExcerpt(text))
    }

    @Test
    fun `модель недоступна с причиной — причина названа в строке`() {
        val text = modelUnavailableText(
            why = "модель не загружена", title = "Заголовок",
            text = "# Заголовок\nПервый абзац.\nВторой абзац.", url = "https://example.com/a",
        )
        assertTrue(text, text.startsWith("Ответ модели недоступен — модель не загружена."))
        assertTrue(text.contains("Открыл страницу «Заголовок»"))
        assertTrue(text.contains("Первый абзац."))
        assertTrue(text.contains("Источник: https://example.com/a"))
    }

    @Test
    fun `модель недоступна без причины — прежнее начало без тире`() {
        val text = modelUnavailableText(
            why = null, title = "Заголовок",
            text = "# Заголовок\nПервый абзац.", url = "https://example.com/a",
        )
        assertTrue(text.startsWith("Ответ модели недоступен. Открыл страницу «Заголовок»"))
    }
}
