package com.uroboros.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Выученный из присланых ссылок белый список обхода (CrawlSources): сайт, чью
 * страницу агент открыл и прочёл, становится источником целиком. Границы
 * закреплены тестами на молчание — случайная «починка» их сломает заметно:
 * невыученный сайт молчит, общие проверки (запросы, порты, пароли, длина
 * пути) не ослабляются и для выученных, правила первых источников тоже.
 */
class CrawlSourcesLearnTest {

    @Before
    fun clean() = CrawlSources.restore(emptySet())

    @After
    fun cleanAfter() = CrawlSources.restore(emptySet())

    // --- Выучивание ---

    @Test
    fun `невыученный сайт обходу не адрес — молчит`() {
        assertNull(CrawlSources.address("https://habr.com/ru/articles/123"))
    }

    @Test
    fun `выученный сайт принимает любой путь`() {
        assertTrue(CrawlSources.learn("habr.com"))
        assertNotNull(CrawlSources.address("https://habr.com/ru/articles/123"))
        assertNotNull(CrawlSources.address("https://habr.com/anything/at/all"))
    }

    @Test
    fun `повторный хост не нов, регистр сводится к нижнему`() {
        assertTrue(CrawlSources.learn("habr.com"))
        assertFalse(CrawlSources.learn("HABR.com"))
        assertEquals(setOf("habr.com"), CrawlSources.learned())
    }

    @Test
    fun `restore заменяет выученное целиком`() {
        CrawlSources.learn("a.ru")
        CrawlSources.learn("b.ru")
        CrawlSources.restore(setOf("c.ru"))
        assertEquals(setOf("c.ru"), CrawlSources.learned())
        assertNotNull(CrawlSources.address("https://c.ru/x"))
        assertNull(CrawlSources.address("https://a.ru/x"))
    }

    // --- Общие проверки не ослабляются и для выученных ---

    @Test
    fun `выученному сайту запрос, порт, пароль и http по-прежнему не адрес`() {
        CrawlSources.learn("example.org")
        assertNull(CrawlSources.address("https://example.org/page?utm=1"))
        assertNull(CrawlSources.address("https://example.org:8443/"))
        assertNull(CrawlSources.address("https://user:pass@example.org/"))
        assertNull(CrawlSources.address("http://example.org/"))
    }

    @Test
    fun `длинный путь и на выученном сайте не проходит`() {
        CrawlSources.learn("example.org")
        assertNull(CrawlSources.address("https://example.org/" + "a".repeat(441)))
    }

    // --- Правила первых источников выучиванием не ослабляются ---

    @Test
    fun `правила первых источников выучиванием не ослабляются`() {
        assertTrue(CrawlSources.learn("ru.wikipedia.org"))
        assertNotNull(CrawlSources.address("https://ru.wikipedia.org/wiki/HTML"))
        assertNull(CrawlSources.address("https://ru.wikipedia.org/special/Book"))
        CrawlSources.learn("nplus1.ru")
        assertNull(CrawlSources.address("https://nplus1.ru/whatever/path"))
    }
}
