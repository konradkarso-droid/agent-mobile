package com.uroboros.gate

import com.uroboros.llm.ChatLine
import com.uroboros.llm.Silence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class CrossChannelTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val t0 = 1_000_000_000_000L
    private val zone = ZoneOffset.UTC

    private fun peer(text: String, sig: String, at: Long, id: Long = 0) = ChatFeed.Line(false, sig, text, true, id = id, at = at)
    private fun agent(text: String, at: Long) = ChatFeed.Line(true, "", text, true, at = at)

    private fun dm(key: String, owner: Boolean, vararg lines: ChatFeed.Line) = CrossChannel.Place(key, true, owner, lines.toList())

    private val praxis = dm(
        "tg:chat:2", false,
        peer("Поедем на море поездом", "Praxis", t0),
        agent("Поедем, море ждёт.", t0 + minute),
        peer("Кондуктор уже свистит, море близко", "Praxis", t0 + 2 * minute),
        agent("Слышу свисток.", t0 + 3 * minute),
    )

    private val screen = CrossChannel.screenPlace(
        listOf(
            Triple("Как твоя память сегодня?", "Помню вчерашнее.", t0 - hour),
            Triple("Память не подводит?", "Нет.", t0 - hour + minute),
        ),
    )

    private fun fed(r: CrossChannel.Result): CrossChannel.Result.Fed {
        assertTrue(r.toString(), r is CrossChannel.Result.Fed)
        return r as CrossChannel.Result.Fed
    }

    // --- Граница ---

    @Test
    fun `чужому чату не положено — и ничего не собирается`() {
        val r = CrossChannel.find(CrossChannel.Viewer.OTHER, "tg:chat:3", null, t0 + hour, listOf(praxis, screen), null)
        assertEquals(CrossChannel.Result.NotAllowed, r)
    }

    @Test
    fun `экран видит разговор с другим человеком в телеграме`() {
        val r = fed(CrossChannel.find(CrossChannel.Viewer.SCREEN, CrossChannel.SCREEN_KEY, t0 - hour + minute, t0 + hour, listOf(praxis, screen), null))
        assertEquals(1, r.seen.size)
        val s = r.seen.single()
        assertEquals("tg:chat:2", s.place.key)
        assertEquals(2, s.head.agentAnswers)
        assertEquals("слово двух ходов — первым", "море", s.head.words.first())
    }

    @Test
    fun `личка владельца видит экран и другого человека`() {
        val owner = dm("tg:chat:1", true, peer("Привет", "Иван", t0 - 2 * hour, id = 1), peer("Ну что?", "Иван", t0 + hour, id = 9))
        val r = fed(CrossChannel.find(CrossChannel.Viewer.OWNER_DM, "tg:chat:1", t0 - 2 * hour, t0 + hour, listOf(owner, praxis, screen), null))
        assertEquals("сначала недавние", listOf("tg:chat:2", CrossChannel.SCREEN_KEY), r.seen.map { it.place.key })
    }

    // --- Молчание ---

    @Test
    fun `своё место не называется`() {
        val r = CrossChannel.find(CrossChannel.Viewer.OWNER_DM, "tg:chat:2", null, t0 + hour, listOf(praxis), null)
        assertEquals(CrossChannel.Result.Nothing, r)
    }

    @Test
    fun `молчит, когда другой разговор был до прошлой реплики здесь`() {
        val r = CrossChannel.find(CrossChannel.Viewer.SCREEN, CrossChannel.SCREEN_KEY, t0 + 4 * minute, t0 + hour, listOf(praxis), null)
        assertEquals(CrossChannel.Result.Nothing, r)
    }

    @Test
    fun `молчит, когда агент в другом месте не отвечал`() {
        val unanswered = dm("tg:chat:2", false, peer("Ау, море", "Praxis", t0), peer("Ты тут?", "Praxis", t0 + minute))
        val r = CrossChannel.find(CrossChannel.Viewer.SCREEN, CrossChannel.SCREEN_KEY, null, t0 + hour, listOf(unanswered), null)
        assertEquals(CrossChannel.Result.Nothing, r)
    }

    @Test
    fun `молчит, когда в последнем разговоре там агент не отвечал`() {
        val later = dm(
            "tg:chat:2", false,
            peer("Поедем на море", "Praxis", t0), agent("Поедем.", t0 + minute),
            peer("Ты тут?", "Praxis", t0 + 5 * hour),
        )
        val r = CrossChannel.find(CrossChannel.Viewer.SCREEN, CrossChannel.SCREEN_KEY, null, t0 + 6 * hour, listOf(later), null)
        assertEquals(CrossChannel.Result.Nothing, r)
    }

    @Test
    fun `без прошлой реплики здесь смотрит назад не дальше объявленного`() {
        val far = t0 + CrossChannel.LOOKBACK_MS + 4 * minute
        assertEquals(CrossChannel.Result.Nothing, CrossChannel.find(CrossChannel.Viewer.SCREEN, CrossChannel.SCREEN_KEY, null, far, listOf(praxis), null))
        fed(CrossChannel.find(CrossChannel.Viewer.SCREEN, CrossChannel.SCREEN_KEY, null, t0 + CrossChannel.LOOKBACK_MS, listOf(praxis), null))
    }

    @Test
    fun `мест не больше объявленного`() {
        val many = (1..CrossChannel.PLACES + 2).map { i ->
            dm("tg:chat:$i", false, peer("Море $i", "Человек$i", t0 + i * minute), agent("Да.", t0 + i * minute + 1))
        }
        val r = fed(CrossChannel.find(CrossChannel.Viewer.SCREEN, CrossChannel.SCREEN_KEY, null, t0 + hour, many, null))
        assertEquals(CrossChannel.PLACES, r.seen.size)
        assertEquals("tg:chat:${CrossChannel.PLACES + 2}", r.seen.first().place.key)
    }

    // --- Материал ---

    @Test
    fun `ход экрана без времени не виден`() {
        val p = CrossChannel.screenPlace(listOf(Triple("Вопрос", "Ответ", null), Triple("Ещё", "Да", t0)))
        assertEquals(listOf("Ещё", "Да"), p.lines.map { it.text })
    }

    private fun row(id: Long, chat: String, isAgent: Boolean, text: String, at: Long, sender: String, ring: String? = "TO_AGENT", place: String = "DIRECT") =
        ChatLine(
            id = id, chat = chat, thread = null, messageId = id, sender = sender, name = "", signature = if (isAgent) "" else "Praxis",
            isBot = isAgent, isAgent = isAgent, text = text, ring = if (isAgent) ring else "TO_AGENT", reason = null,
            replyTo = null, at = at, place = place,
        )

    @Test
    fun `строка молчания агента ответом не считается`() {
        val rows = listOf(
            row(1, "tg:chat:2", false, "Ты тут?", t0, "tg:user:2"),
            row(2, "tg:chat:2", true, "МОЛЧУ: не мне", t0 + minute, "tg:user:me", ring = Silence.RING),
        )
        val places = CrossChannel.chatPlaces(rows, "tg:user:1")
        assertEquals(1, places.single().lines.size)
        assertEquals(CrossChannel.Result.Nothing, CrossChannel.find(CrossChannel.Viewer.SCREEN, CrossChannel.SCREEN_KEY, null, t0 + hour, places, null))
    }

    @Test
    fun `личка владельца узнаётся по его ключу`() {
        val rows = listOf(
            row(1, "tg:chat:1", false, "Привет", t0, "tg:user:1"),
            row(2, "tg:chat:2", false, "Привет", t0, "tg:user:2"),
            row(3, "tg:chat:9", false, "Привет", t0, "tg:user:1", place = "GROUP"),
        )
        val byKey = CrossChannel.chatPlaces(rows, "tg:user:1").associateBy { it.key }
        assertTrue(byKey.getValue("tg:chat:1").ownerChat)
        assertFalse(byKey.getValue("tg:chat:2").ownerChat)
        assertFalse("группа — не личка владельца", byKey.getValue("tg:chat:9").ownerChat)
        assertFalse(CrossChannel.chatPlaces(rows, null).any { it.ownerChat })
    }

    // --- Текст ---

    @Test
    fun `экран — где, когда, сколько ответил и слова, без цитат`() {
        val r = fed(CrossChannel.find(CrossChannel.Viewer.SCREEN, CrossChannel.SCREEN_KEY, null, t0 + hour, listOf(praxis), null))
        val text = CrossChannel.text(CrossChannel.Viewer.SCREEN, r.seen, t0 + hour, zone)
        assertTrue(text, text.startsWith("Перед этим, меньше часа назад, я говорил в телеграме, в личке с Praxis: я ответил 2 раза. Слова, что звучали чаще: море"))
        assertFalse("реплик людей дословно нет", text.contains("«"))
        assertFalse(text, text.contains("свисток"))
    }

    @Test
    fun `личка владельца — экран словами, без цитат`() {
        val r = fed(CrossChannel.find(CrossChannel.Viewer.OWNER_DM, "tg:chat:1", null, t0 + hour, listOf(praxis, screen), null))
        val text = CrossChannel.text(CrossChannel.Viewer.OWNER_DM, r.seen, t0 + hour, zone)
        assertFalse(text, text.contains("«"))
        assertTrue(text, text.contains("я говорил с тобой на экране телефона"))
        assertTrue(text, text.contains("в личке с Praxis"))
    }

    @Test
    fun `личка владельца с экрана называется «с тобой в телеграме»`() {
        val owner = dm("tg:chat:1", true, peer("Как сон?", "Иван", t0, id = 1), agent("Спал хорошо.", t0 + minute))
        val r = fed(CrossChannel.find(CrossChannel.Viewer.SCREEN, CrossChannel.SCREEN_KEY, null, t0 + hour, listOf(owner), null))
        val text = CrossChannel.text(CrossChannel.Viewer.SCREEN, r.seen, t0 + hour, zone)
        assertTrue(text, text.startsWith("Перед этим, меньше часа назад, я говорил с тобой в телеграме, в личке: я ответил 1 раз."))
    }

    // --- Прибор ---

    @Test
    fun `прибор различает все случаи`() {
        val clock: (Long) -> String = { "ЧЧ" }
        assertEquals("Между каналами: этому чату не положено", CrossChannel.meter(CrossChannel.Result.NotAllowed, null, clock))
        assertTrue(CrossChannel.meter(CrossChannel.Result.Nothing, null, clock).startsWith("Между каналами: нет — "))
        assertTrue(CrossChannel.meter(null, "IOException", clock).contains("не прочиталось (IOException)"))
        val r = CrossChannel.find(CrossChannel.Viewer.SCREEN, CrossChannel.SCREEN_KEY, null, t0 + hour, listOf(praxis), null)
        val m = CrossChannel.meter(r, null, clock)
        assertTrue(m, m.startsWith("Между каналами: подано — личка Praxis ЧЧ–ЧЧ, ответов 2, слова: море"))
    }
}
