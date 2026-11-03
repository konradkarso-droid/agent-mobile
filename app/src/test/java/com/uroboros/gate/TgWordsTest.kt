package com.uroboros.gate

import com.uroboros.memory.nav.AddressSorter
import com.uroboros.memory.nav.ForeignSpeech
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TgWordsTest {

    private val me = GateBotApi.Me(id = 900, username = "aleksandr_bot")

    private fun msg(
        text: String? = "Привет",
        chatType: String = "private",
        chatId: Long = 42,
        from: Long? = 42,
        first: String? = "Денис",
        entities: List<GateBotApi.Entity> = emptyList(),
        edited: Boolean = false,
        senderChat: Long? = null,
        replyFrom: Long? = null,
        replyId: Long? = null,
        topicRoot: Boolean = false,
        thread: Long? = null,
        topic: Boolean = false,
        forwarded: Boolean = false,
        bot: Boolean = false,
    ) = GateBotApi.Message(
        chatId = chatId, privateChat = chatType == "private", fromId = from, fromUsername = "denis", senderChatId = senderChat,
        edited = edited, dateSec = 1_000, doc = null, messageId = 7, chatType = chatType, chatTitle = "Сообщество",
        threadId = thread, topicMessage = topic, fromIsBot = bot, fromFirstName = first, fromLastName = null, text = text,
        entities = entities, replyToFromId = replyFrom, replyToMessageId = replyId, replyToTopicRoot = topicRoot, forwarded = forwarded,
    )

    private fun words(m: GateBotApi.Message) = (TgWords.of(m, me) as TgWords.Read.Of).words

    private fun notWords(m: GateBotApi.Message) = (TgWords.of(m, me) as TgWords.Read.NotWords).why

    @Test
    fun `личка — слова с ключом, местом и подписью`() {
        val w = words(msg())
        assertEquals(Place.DIRECT, w.place)
        assertEquals(PersonKey("tg:user:42"), w.message.sender)
        assertEquals(PersonKey("tg:chat:42"), w.chat)
        assertEquals("Денис", w.signature)
        assertEquals(1_000_000L, w.atMs)
        assertEquals("tg:chat:42", w.chatRef)
    }

    @Test
    fun `не слова молчат с причиной`() {
        assertTrue(notWords(msg(edited = true)).contains("правка"))
        assertTrue(notWords(msg(senderChat = -100)).contains("от имени чата"))
        assertTrue(notWords(msg(from = null)).contains("нет отправителя"))
        assertTrue(notWords(msg(from = 900)).contains("своё"))
        assertTrue(notWords(msg(chatType = "channel")).contains("не личка и не группа"))
        assertTrue(notWords(msg(text = null)).contains("не текст"))
        assertTrue(notWords(msg(text = "   ")).contains("не текст"))
        assertTrue(notWords(msg(forwarded = true)).contains("пересланное"))
    }

    @Test
    fun `одна команда — не речь, команда в тексте — речь`() {
        assertTrue(notWords(msg(text = "/start", entities = listOf(GateBotApi.Entity("bot_command", 0, 6)))).contains("/start"))
        val w = words(msg(text = "/help@other_bot и ещё вопрос", chatType = "supergroup", chatId = -5, entities = listOf(GateBotApi.Entity("bot_command", 0, 15))))
        assertEquals(listOf("/help@other_bot"), w.message.commands)
    }

    @Test
    fun `упоминания по смещениям UTF-16`() {
        // Эмодзи — два знака UTF-16: смещение упоминания после него — 3, а не 2.
        val text = "😀 @aleksandr_bot глянь"
        val w = words(msg(text = text, chatType = "supergroup", chatId = -5, entities = listOf(
            GateBotApi.Entity("mention", 3, 14),
            GateBotApi.Entity("text_mention", 0, 2, userId = 77),
            GateBotApi.Entity("mention", 30, 5),
        )))
        assertEquals(listOf("@aleksandr_bot"), w.message.mentions)
        assertEquals(listOf(PersonKey("tg:user:77")), w.message.textMentions)
    }

    @Test
    fun `ответ на служебное создание ветки — не ответ`() {
        val inTopic = words(msg(chatType = "supergroup", chatId = -5, replyFrom = 11, replyId = 300, topicRoot = true, thread = 300, topic = true))
        assertNull(inTopic.message.replyTo)
        assertEquals(300L, inTopic.thread)
        assertEquals("tg:chat:-5/300", inTopic.chatRef)
        val byNumber = words(msg(chatType = "supergroup", chatId = -5, replyFrom = 11, replyId = 300, thread = 300, topic = true))
        assertNull("ответ на сообщение с номером ветки — тоже служебный", byNumber.message.replyTo)
        val real = words(msg(chatType = "supergroup", chatId = -5, replyFrom = 11, replyId = 301, thread = 300, topic = true))
        assertEquals(PersonKey("tg:user:11"), real.message.replyTo)
    }

    @Test
    fun `ветка — только у сообщения ветки`() {
        assertNull(words(msg(chatType = "supergroup", chatId = -5, thread = 300, topic = false)).thread)
    }

    @Test
    fun `сортировщик видит в ветке общее, а не ответ другому`() {
        val w = words(msg(text = "Сегодня солнечно", chatType = "supergroup", chatId = -5, replyFrom = 11, replyId = 300, topicRoot = true, thread = 300, topic = true))
        val route = AddressSorter.sort(w.message, AddressSorter.Self(PersonKey("tg:user:900"), setOf("aleksandr_bot")))
        assertEquals(AddressSorter.Ring.GENERAL, route.ring)
        assertTrue(AddressSorter.Sign.REPLY_TO_OTHER !in route.channel)
    }

    @Test
    fun `подпись — первое слово имени с заглавной, иначе запасная`() {
        assertEquals("Денис", TgWords.signature("Денис Петров", "d"))
        assertEquals("Денис", TgWords.signature("денис", null))
        assertEquals("Denis", TgWords.signature("Denis", null))
        assertEquals("Ivan", TgWords.signature("😀", "ivan_99"))
        assertEquals(TgWords.FALLBACK_SIGNATURE, TgWords.signature("😀 7", null))
        assertEquals(TgWords.FALLBACK_SIGNATURE, TgWords.signature(null, null))
        assertEquals("ярлык подписью не читается", TgWords.FALLBACK_SIGNATURE, TgWords.signature("Итог", null))
    }

    @Test
    fun `чужие слова в память — чужой речью, свои владельца — как есть`() {
        val signed = TgWords.memoryText("я не пью кофе. Совсем.", "Денис", owner = false)!!
        assertEquals("Денис: я не пью кофе. Совсем.", signed)
        assertTrue(ForeignSpeech.ofRecord(signed).all { it.kind == ForeignSpeech.Kind.DIRECT && it.speaker == "Денис" })
        assertTrue("не своя речь владельца", !ForeignSpeech.allOwn(signed))
        assertEquals("я пью кофе", TgWords.memoryText("я пью кофе", "Иван", owner = true))
    }

    @Test
    fun `подпись, которую чужой речью не прочесть, — в память не идёт`() {
        assertNull(TgWords.memoryText("текст", "Итог", owner = false))
    }

    @Test
    fun `длинный текст режется по предложению, короткий не трогается`() {
        assertEquals("Коротко.", TgWords.cut("  Коротко.  "))
        val long = "Первое предложение. " + "а".repeat(5000)
        assertEquals("Первое предложение.", TgWords.cut(long, limit = 30))
        val noEnd = "б".repeat(5000)
        assertEquals(4096, TgWords.cut(noEnd).length)
    }

    @Test
    fun `разбор обновления — текст, сущности, ответ, ветка, бот`() {
        val json = """{"ok":true,"result":[{"update_id":5,"message":{"message_id":12,"message_thread_id":300,"is_topic_message":true,
            "from":{"id":11,"is_bot":true,"first_name":"Praxis","username":"praxis_bot"},
            "chat":{"id":-1001,"type":"supergroup","title":"Агенты","is_forum":true},"date":1700000000,
            "reply_to_message":{"message_id":300,"from":{"id":5,"is_bot":false,"first_name":"Аня"},"chat":{"id":-1001,"type":"supergroup"},"date":1,"forum_topic_created":{"name":"Общая","icon_color":1}},
            "text":"@aleksandr_bot привет","entities":[{"type":"mention","offset":0,"length":14}]}}]}"""
        val r = GateBotApi.reply(json, 200) as GateBotApi.Reply.Ok
        val m = GateBotApi.updates(r.result).single().message!!
        assertEquals(12L, m.messageId)
        assertEquals(300L, m.threadId)
        assertTrue(m.topicMessage)
        assertTrue(m.fromIsBot)
        assertEquals("Praxis", m.fromFirstName)
        assertEquals("supergroup", m.chatType)
        assertEquals("Агенты", m.chatTitle)
        assertTrue(m.replyToTopicRoot)
        assertEquals(5L, m.replyToFromId)
        assertEquals(listOf(GateBotApi.Entity("mention", 0, 14)), m.entities)
        val w = words(m)
        assertNull(w.message.replyTo)
        assertEquals(listOf("@aleksandr_bot"), w.message.mentions)
        val route = AddressSorter.sort(w.message, AddressSorter.Self(PersonKey("tg:user:900"), setOf("aleksandr_bot")))
        assertEquals(AddressSorter.Ring.TO_AGENT, route.ring)
    }

    @Test
    fun `тело отправки — без разметки, с ответом и веткой`() {
        val body = String(GateBotApi.messageBody(-1001, "Привет & *мир*", replyTo = 12, thread = 300), Charsets.UTF_8)
        assertTrue(body.startsWith("chat_id=-1001&text="))
        assertTrue(body.contains("message_thread_id=300"))
        assertTrue(body.contains("reply_parameters=%7B%22message_id%22%3A12"))
        assertTrue("разметка не включена", !body.contains("parse_mode"))
        assertTrue("знаки текста закодированы", body.contains("%26") && body.contains("*"))
        val plain = String(GateBotApi.messageBody(42, "Да", replyTo = null, thread = null), Charsets.UTF_8)
        assertEquals("chat_id=42&text=%D0%94%D0%B0", plain)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `слишком длинный текст не отправляется`() {
        GateBotApi.messageBody(42, "а".repeat(GateBotApi.TEXT_LIMIT + 1), null, null)
    }

    @Test
    fun `кто я — приватность групп`() {
        val r = GateBotApi.reply("""{"ok":true,"result":{"id":900,"is_bot":true,"username":"aleksandr_bot","can_read_all_group_messages":false}}""", 200) as GateBotApi.Reply.Ok
        assertEquals(GateBotApi.Me(900, "aleksandr_bot", false), GateBotApi.me(r.result))
        val r2 = GateBotApi.reply("""{"ok":true,"result":{"id":900}}""", 200) as GateBotApi.Reply.Ok
        assertNull(GateBotApi.me(r2.result)!!.readsAllGroupMessages)
    }
}
