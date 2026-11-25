package com.uroboros.gate.link

import com.uroboros.gate.GateBotApi
import com.uroboros.memory.ActionGate
import com.uroboros.memory.ActionProvenance
import com.uroboros.memory.ActionRequest
import com.uroboros.memory.ActionType
import com.uroboros.memory.GateResult
import com.uroboros.memory.StandingConsent
import com.uroboros.search.LinkOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkFollowTest {

    // --- Матрица разрешений ---

    @Test
    fun `чат приложения разрешён всегда`() {
        assertEquals(LinkFollow.Verdict.Allow, LinkFollow.decide(LinkFollow.Where.APP, groupAllowed = false))
        assertEquals(LinkFollow.Verdict.Allow, LinkFollow.decide(LinkFollow.Where.APP, groupAllowed = true))
    }

    @Test
    fun `личка владельца разрешена всегда`() {
        assertEquals(LinkFollow.Verdict.Allow, LinkFollow.decide(LinkFollow.Where.OWNER_DIRECT, groupAllowed = false))
    }

    @Test
    fun `группа без разрешения владельца молчит`() {
        val verdict = LinkFollow.decide(LinkFollow.Where.GROUP, groupAllowed = false)
        assertTrue(verdict is LinkFollow.Verdict.Deny)
        assertTrue((verdict as LinkFollow.Verdict.Deny).why.contains("группах"))
    }

    @Test
    fun `группа с разрешения владельца открывает`() {
        assertEquals(LinkFollow.Verdict.Allow, LinkFollow.decide(LinkFollow.Where.GROUP, groupAllowed = true))
    }

    @Test
    fun `чужая личка не открывается ничем — даже разрешением групп`() {
        val verdict = LinkFollow.decide(LinkFollow.Where.STRANGER_DIRECT, groupAllowed = true)
        assertTrue(verdict is LinkFollow.Verdict.Deny)
    }

    // --- Ссылка из сообщения телеграма ---

    private fun message(text: String?, vararg entities: GateBotApi.Entity) = GateBotApi.Message(
        chatId = 1, privateChat = true, fromId = 2, fromUsername = null, senderChatId = null,
        edited = false, dateSec = 0, doc = null, text = text, entities = entities.toList(),
    )

    @Test
    fun `видимая ссылка читается срезом сущности`() {
        val text = "глянь https://example.com/a?page=1 тут"
        val m = message(text, GateBotApi.Entity("url", 6, "https://example.com/a?page=1".length))
        assertEquals("https://example.com/a?page=1", LinkFollow.telegramUrl(m))
    }

    @Test
    fun `ссылка без схемы от телеграма проходит как есть`() {
        val text = "nplus1.ru/news/2026/11/26/polyet"
        val m = message(text, GateBotApi.Entity("url", 0, text.length))
        assertEquals(text, LinkFollow.telegramUrl(m))
    }

    @Test
    fun `скрытая ссылка text_link берётся из сущности, не из текста`() {
        val m = message("вот статья", GateBotApi.Entity("text_link", 4, 6, url = "https://example.com/hidden"))
        assertEquals("https://example.com/hidden", LinkFollow.telegramUrl(m))
    }

    @Test
    fun `имена и команды ссылкой не считаются`() {
        val m = message("@agент /start", GateBotApi.Entity("mention", 0, 7), GateBotApi.Entity("bot_command", 8, 6))
        assertNull(LinkFollow.telegramUrl(m))
    }

    @Test
    fun `открывается первая ссылка, остальные игнорируются`() {
        val first = "https://example.com/first"
        val second = "https://example.com/second"
        val m = message("$first и $second", GateBotApi.Entity("url", 0, first.length), GateBotApi.Entity("url", first.length + 3, second.length))
        assertEquals(first, LinkFollow.telegramUrl(m))
    }

    @Test
    fun `сущность за краем текста не читается — молчит`() {
        val m = message("короткий текст", GateBotApi.Entity("url", 5, 100))
        assertNull(LinkFollow.telegramUrl(m))
    }

    // --- Ссылка из текста чата приложения ---

    @Test
    fun `явная ссылка со схемой находится`() {
        assertEquals("https://example.com/a", LinkFollow.plainTextUrl("прочитай https://example.com/a, пожалуйста"))
    }

    @Test
    fun `www-адрес находится, хвостовые знаки срезаются`() {
        assertEquals("www.example.com/x", LinkFollow.plainTextUrl("зайди на www.example.com/x."))
    }

    @Test
    fun `голый домен ссылкой не считается — молчит`() {
        assertNull(LinkFollow.plainTextUrl("зайди на example.com потом"))
        assertNull(LinkFollow.plainTextUrl("просто текст без адресов"))
    }

    // --- Вопрос читателю ---

    @Test
    fun `вопрос — сообщение без ссылки, пробелы схлопнуты`() {
        assertEquals("что тут написано", LinkFollow.question("что тут https://example.com/a написано", "https://example.com/a"))
        assertEquals("", LinkFollow.question("https://example.com/a", "https://example.com/a"))
    }

    // --- Ссылка не реплика: что остаётся для памяти ---

    @Test
    fun `видимая ссылка вырезается — остаётся реплика без адреса`() {
        val url = "https://example.com/a?page=1"
        val text = "глянь $url тут"
        val m = message(text, GateBotApi.Entity("url", text.indexOf(url), url.length))
        assertEquals("глянь тут", LinkFollow.withoutLinks(m))
    }

    @Test
    fun `несколько видимых ссылок вырезаются все`() {
        val first = "https://example.com/one"
        val second = "https://example.com/two"
        val text = "$first и ещё $second между"
        val m = message(
            text,
            GateBotApi.Entity("url", text.indexOf(first), first.length),
            GateBotApi.Entity("url", text.indexOf(second), second.length),
        )
        assertEquals("и ещё между", LinkFollow.withoutLinks(m))
    }

    @Test
    fun `сообщение из одной ссылки пусто — реплики нет`() {
        val url = "https://example.com/a"
        val m = message(url, GateBotApi.Entity("url", 0, url.length))
        assertEquals("", LinkFollow.withoutLinks(m))
    }

    @Test
    fun `скрытая ссылка text_link текст не трогает — адреса в нём нет`() {
        val m = message("вот статья", GateBotApi.Entity("text_link", 4, 6, url = "https://example.com/hidden"))
        assertEquals("вот статья", LinkFollow.withoutLinks(m))
    }

    @Test
    fun `без видимых ссылок текст возвращается как есть — до последнего пробела`() {
        val text = "строка первая\nстрока вторая  с  пробелами"
        val m = message(text, GateBotApi.Entity("mention", 0, 6))
        assertEquals(text, LinkFollow.withoutLinks(m))
    }

    @Test
    fun `сущность за краем текста не читается — текст цел`() {
        val m = message("короткий текст", GateBotApi.Entity("url", 5, 100))
        assertEquals("короткий текст", LinkFollow.withoutLinks(m))
    }

    // --- Ворота действий: ссылка из группы весит больше и требует разрешения ---

    private fun groupRequest() = ActionRequest(
        type = ActionType.NETWORK_CALL,
        requestedBy = LinkOrigin.GROUP.requestedBy,
        provenance = LinkOrigin.GROUP.provenance,
        crossesDeviceBoundary = true,
        isReversible = false,
    )

    @Test
    fun `сетевой вызов по ссылке из группы без разрешения воротами отклонён`() {
        assertEquals(GateResult.DENY, ActionGate.evaluate(groupRequest()).result)
    }

    @Test
    fun `разрешение владельца на ссылки в группах открывает ворота`() {
        val consent = StandingConsent.grantedByOwner(
            ActionType.NETWORK_CALL, LinkOrigin.GROUP.requestedBy, "тест",
            now = 1_000L, termMs = StandingConsent.NO_TERM,
        )
        assertEquals(GateResult.ALLOW, ActionGate.evaluate(groupRequest(), consent = consent).result)
    }

    @Test
    fun `чужое согласие — на ответы, не на ссылки — группу не открывает`() {
        val replyConsent = StandingConsent.grantedByOwner(
            ActionType.SEND_MESSAGE, "ChatLink: ответ в телеграме", "тест",
            now = 1_000L, termMs = StandingConsent.NO_TERM,
        )
        assertEquals(GateResult.DENY, ActionGate.evaluate(groupRequest(), consent = replyConsent).result)
    }

    @Test
    fun `ссылка владельца и из приложения идут пользовательским происхождением и без согласия`() {
        for (origin in listOf(LinkOrigin.APP, LinkOrigin.OWNER_DIRECT)) {
            val request = ActionRequest(
                type = ActionType.NETWORK_CALL, requestedBy = origin.requestedBy,
                provenance = origin.provenance, crossesDeviceBoundary = true, isReversible = false,
            )
            assertEquals(GateResult.ALLOW, ActionGate.evaluate(request).result)
        }
    }
}
