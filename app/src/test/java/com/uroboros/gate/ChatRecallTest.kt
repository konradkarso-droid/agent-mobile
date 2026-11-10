package com.uroboros.gate

import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.LemmaDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ChatRecallTest {
    private val praxis = "tg:user:8745908024"
    private val owner = "tg:user:412244782"
    private val group = "tg:chat:-100500"
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val utc = ZoneId.of("UTC")

    private fun record(id: Long, content: String, speaker: String? = null, chat: String? = null, at: Long = now - 3 * day) =
        Sticker(id = id, content = content, createdAt = at, speaker = speaker, chat = chat)

    @Test
    fun `граница — личка владельца, личка другого, группа`() {
        assertEquals(ChatRecall.Scope.OWNER, ChatRecall.scope(direct = true, senderIsOwner = true))
        assertEquals(ChatRecall.Scope.PERSON, ChatRecall.scope(direct = true, senderIsOwner = false))
        assertEquals(ChatRecall.Scope.GROUP, ChatRecall.scope(direct = false, senderIsOwner = false))
    }

    @Test
    fun `владелец в группе — всё равно группа, а не вся память`() {
        assertEquals(ChatRecall.Scope.GROUP, ChatRecall.scope(direct = false, senderIsOwner = true))
    }

    @Test
    fun `чужому собеседнику видны только его слова`() {
        val his = record(1, "Praxis: Тесто едет к морю.", speaker = praxis, chat = praxis)
        val screen = record(2, "По профессии я химик.")
        val ownersTg = record(3, "Я был у стоматолога.", speaker = owner, chat = owner)
        assertTrue(ChatRecall.visible(ChatRecall.Scope.PERSON, his, praxis, praxis))
        assertFalse("запись с экрана чужому не видна", ChatRecall.visible(ChatRecall.Scope.PERSON, screen, praxis, praxis))
        assertFalse("слова владельца из его лички чужому не видны", ChatRecall.visible(ChatRecall.Scope.PERSON, ownersTg, praxis, praxis))
    }

    @Test
    fun `группе видно только сказанное в ней — личка и экран молчат`() {
        val inGroup = record(1, "Praxis: Встречаемся в субботу.", speaker = praxis, chat = group)
        val inDirect = record(2, "Praxis: Это между нами.", speaker = praxis, chat = praxis)
        val screen = record(3, "По профессии я химик.")
        assertTrue(ChatRecall.visible(ChatRecall.Scope.GROUP, inGroup, praxis, group))
        assertFalse(ChatRecall.visible(ChatRecall.Scope.GROUP, inDirect, praxis, group))
        assertFalse(ChatRecall.visible(ChatRecall.Scope.GROUP, screen, praxis, group))
    }

    @Test
    fun `пустой ключ не открывает записи без ключа`() {
        val screen = record(1, "По профессии я химик.")
        assertFalse(ChatRecall.visible(ChatRecall.Scope.PERSON, screen.copy(speaker = ""), "", ""))
        assertFalse(ChatRecall.visible(ChatRecall.Scope.GROUP, screen.copy(chat = ""), "", ""))
    }

    @Test
    fun `владельцу видно всё`() {
        assertTrue(ChatRecall.visible(ChatRecall.Scope.OWNER, record(1, "По профессии я химик."), owner, owner))
    }

    @Test
    fun `узкий отбор находит по предмету вопроса`() {
        val pool = listOf(
            record(1, "Praxis: Тесто устраивает мешочек у окна и разворачивает билет.", speaker = praxis),
            record(2, "Praxis: Сегодня у меня тихий день, читаю заметки.", speaker = praxis),
        )
        val picked = ChatRecall.pick("Что там было про билет?", pool, dictionary, emptyList())
        assertEquals(listOf(1L), picked.map { it.id })
    }

    @Test
    fun `без словаря предмет не опознан — отбор молчит, а не гадает по одному слову`() {
        val pool = listOf(record(1, "Praxis: Тесто устраивает мешочек у окна и разворачивает билет.", speaker = praxis))
        assertTrue(ChatRecall.pick("Что там было про билет?", pool, null, emptyList()).isEmpty())
    }

    @Test
    fun `без якоря запись проходит по двум значимым словам вопроса`() {
        val pool = listOf(record(1, "Praxis: Завтра поедем смотреть старый маяк.", speaker = praxis))
        assertEquals(listOf(1L), ChatRecall.pick("Когда поедем смотреть?", pool, null, emptyList()).map { it.id })
    }

    @Test
    fun `узкий отбор молчит, когда к вопросу ничего не подходит`() {
        val pool = listOf(record(1, "Praxis: Сегодня у меня тихий день, читаю заметки.", speaker = praxis))
        assertTrue(ChatRecall.pick("Как настроение?", pool, dictionary, emptyList()).isEmpty())
        assertTrue(ChatRecall.pick("Хорошо, попробую", pool, dictionary, emptyList()).isEmpty())
    }

    @Test
    fun `одно общее слово без предмета — не повод подавать запись`() {
        val pool = listOf(record(1, "Praxis: Хорошо бы когда-нибудь увидеть море своими глазами.", speaker = praxis))
        assertTrue(ChatRecall.pick("Хорошо, тогда расскажи что-нибудь другое", pool, dictionary, emptyList()).isEmpty())
    }

    @Test
    fun `запись, которая и так в ленте, не подаётся`() {
        val pool = listOf(record(1, "Praxis: Тесто разворачивает билет до моря.", speaker = praxis))
        val shown = ChatRecall.shownTexts(
            listOf(ChatFeed.Line(false, "Praxis", "Тесто разворачивает билет до моря.", true, sender = praxis)), owner,
        )
        assertEquals(listOf("Praxis: Тесто разворачивает билет до моря."), shown)
        assertTrue(ChatRecall.pick("Что с билетом и морем?", pool, dictionary, shown).isEmpty())
    }

    @Test
    fun `слова владельца в ленте — без подписи, как лежат в памяти`() {
        val shown = ChatRecall.shownTexts(listOf(ChatFeed.Line(false, "Иван", "Я был у стоматолога.", true, sender = owner)), owner)
        assertEquals(listOf("Я был у стоматолога."), shown)
    }

    @Test
    fun `запись из одних вопросов не подаётся`() {
        val pool = listOf(record(1, "Praxis: А билет у тебя с собой?", speaker = praxis))
        assertTrue(ChatRecall.pick("Что там с билетом?", pool, dictionary, emptyList()).isEmpty())
    }

    @Test
    fun `не больше потолка, свежие при равном весе первыми`() {
        val pool = (1L..8L).map { record(it, "Praxis: Билет номер $it лежит в мешочке.", speaker = praxis, at = now - it * day) }
        val picked = ChatRecall.pick("Где билет?", pool, dictionary, emptyList())
        assertEquals(ChatRecall.NARROW_SEATS, picked.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), picked.map { it.id })
    }

    @Test
    fun `в личке запись подписана как слова собеседника`() {
        val line = ChatRecall.narrowLine(
            record(1, "Praxis: Тесто едет к морю.", speaker = praxis), ChatRecall.Scope.PERSON, now, past = true, owner, "Иван", utc,
        )
        assertTrue(line, line.startsWith("Твои слова в прошлом разговоре, "))
        assertTrue(line.endsWith("«Praxis: Тесто едет к морю.»."))
    }

    @Test
    fun `в группе подпись не называет лица, а слова владельца получают его имя`() {
        val foreign = ChatRecall.narrowLine(
            record(1, "Praxis: Встречаемся в субботу.", speaker = praxis, chat = group), ChatRecall.Scope.GROUP, now, past = true, owner, "Иван", utc,
        )
        assertTrue(foreign, foreign.startsWith("В этом чате, в прошлом разговоре, "))
        assertFalse(foreign.contains("Твои слова"))
        val owners = ChatRecall.narrowLine(
            record(2, "Суббота мне подходит.", speaker = owner, chat = group), ChatRecall.Scope.GROUP, now, past = false, owner, "Иван", utc,
        )
        assertTrue(owners, owners.endsWith("«Иван: Суббота мне подходит.»."))
    }

    @Test
    fun `сообщение с записями — строки по порядку, пусто — null`() {
        assertEquals("а\nб", ChatRecall.message(listOf("а", "б")))
        assertNull(ChatRecall.message(emptyList()))
    }

    @Test
    fun `строка экрана называет границу и причину молчания`() {
        assertTrue(ChatRecall.narrowMeter("Praxis", ChatRecall.Scope.PERSON, pool = 0, fed = 0).contains("только его слова · дозволенных 0 · подавать нечего"))
        assertTrue(ChatRecall.narrowMeter("Praxis", ChatRecall.Scope.PERSON, pool = 17, fed = 0).contains("к вопросу не подошла ни одна"))
        assertTrue(ChatRecall.narrowMeter("Чат", ChatRecall.Scope.GROUP, pool = 17, fed = 2).contains("группа — только сказанное в ней · дозволенных 17 · подано 2"))
        assertTrue(ChatRecall.ownerMeter("Иван", 3, "Круг: …").startsWith("Записи (Иван): владелец — вся память · подано 3"))
        assertTrue(ChatRecall.failedMeter("Иван", "IOException").contains("отвечаю без них"))
    }

    private companion object {
        /** Словарь приложения: тест читает тот же файл, что телефон. */
        private val dictionary: LemmaDictionary by lazy {
            val file = java.io.File("src/main/assets/" + com.uroboros.memory.nav.LemmaHolder.ASSET)
            file.bufferedReader(Charsets.UTF_8).useLines { LemmaDictionary.read(it) }
        }
    }
}
