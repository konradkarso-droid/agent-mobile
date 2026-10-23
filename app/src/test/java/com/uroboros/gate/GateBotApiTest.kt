package com.uroboros.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GateBotApiTest {

    private val token = "123456789:AAH-abcdefghijklmnopqrstuvwxyz_0123"

    @Test
    fun `адреса — только телеграм, токен в пути`() {
        assertEquals("https://api.telegram.org/bot$token/getMe", GateBotApi.methodUrl(token, "getMe"))
        assertEquals("https://api.telegram.org/file/bot$token/documents/f.txt", GateBotApi.fileUrl(token, "documents/f.txt"))
        val u = GateBotApi.updatesUrl(token, 77)
        assertTrue(u.startsWith("https://api.telegram.org/bot$token/getUpdates?timeout=50&allowed_updates="))
        assertTrue(u.endsWith("&offset=77"))
        assertFalse(GateBotApi.updatesUrl(token, null).contains("offset"))
    }

    @Test
    fun `токена нет ни в одной строке после чистки`() {
        val err = "FileNotFoundException: https://api.telegram.org/bot$token/getUpdates?timeout=50"
        val s = GateBotApi.scrub(err, token)
        assertFalse(s.contains(token))
        assertFalse(s.contains("AAH-abc"))
        assertFalse("и чужой похожий токен", GateBotApi.scrub("bot987654321:${"x".repeat(35)}/x", null).contains("x".repeat(35)))
        assertEquals("молчит, когда токена нет", "нет связи", GateBotApi.scrub("нет связи", token))
    }

    @Test
    fun `вид токена — от опечатки`() {
        assertTrue(GateBotApi.looksLikeToken(token))
        assertFalse(GateBotApi.looksLikeToken("123456789"))
        assertFalse(GateBotApi.looksLikeToken("$token "))
        assertFalse(GateBotApi.looksLikeToken("@my_bot"))
    }

    @Test
    fun `пауза после сбоя растёт до потолка`() {
        assertEquals(30_000L, GateBotApi.pauseMs(1))
        assertEquals(60_000L, GateBotApi.pauseMs(2))
        assertEquals(GateBotApi.PAUSE_MAX_MS, GateBotApi.pauseMs(20))
    }

    @Test
    fun `ответ — ok и ошибки 409 и 401, не JSON — ошибка с кодом HTTP`() {
        assertTrue(GateBotApi.reply("""{"ok":true,"result":[]}""", 200) is GateBotApi.Reply.Ok)
        assertEquals(
            GateBotApi.Reply.Error(409, "Conflict: terminated by other getUpdates request"),
            GateBotApi.reply("""{"ok":false,"error_code":409,"description":"Conflict: terminated by other getUpdates request"}""", 409),
        )
        assertEquals(401, (GateBotApi.reply("""{"ok":false,"error_code":401,"description":"Unauthorized"}""", 401) as GateBotApi.Reply.Error).code)
        assertEquals(502, (GateBotApi.reply("<html>Bad Gateway</html>", 502) as GateBotApi.Reply.Error).code)
    }

    private val updates = """
        {"ok":true,"result":[
          {"update_id":10,"message":{"message_id":1,"from":{"id":222,"is_bot":true,"first_name":"Kate","username":"kate_ai_bot"},
            "chat":{"id":222,"first_name":"Kate","username":"kate_ai_bot","type":"private"},"date":1760947200,
            "document":{"file_name":"trafaret.txt","mime_type":"text/plain","file_id":"BQAC","file_unique_id":"u","file_size":512}}},
          {"update_id":11,"edited_message":{"message_id":1,"from":{"id":222,"is_bot":true},"chat":{"id":222,"type":"private"},"date":1760947201,"edit_date":1760947300,
            "document":{"file_id":"BQAD","file_size":512}}},
          {"update_id":12,"message":{"message_id":2,"from":{"id":1087968824,"is_bot":true},"sender_chat":{"id":-100777,"type":"supergroup"},
            "chat":{"id":-100777,"type":"supergroup"},"date":1760947202,"text":"hi"}},
          {"update_id":13,"message":{"message_id":3,"from":{"id":333},"chat":{"id":333,"type":"private"},"date":1760947203,
            "location":{"latitude":55.7558,"longitude":37.6173,"horizontal_accuracy":null}}},
          {"update_id":14,"message":{"message_id":4,"from":{"id":222},"chat":{"id":222,"type":"private"},"date":1760947204,
            "document":{"file_id":"BIG","file_size":99999}}},
          {"update_id":15,"my_chat_member":{"chat":{"id":5}}}
        ]}
    """.trimIndent()

    private fun parsed() = GateBotApi.updates((GateBotApi.reply(updates, 200) as GateBotApi.Reply.Ok).result)

    @Test
    fun `обновления разбираются при дробных, null и глубине больше четырёх`() {
        val u = parsed()
        assertEquals(listOf(10L, 11L, 12L, 13L, 14L, 15L), u.map { it.id })
        val m = u[0].message!!
        assertEquals(222L, m.chatId)
        assertTrue(m.privateChat)
        assertEquals("kate_ai_bot", m.fromUsername)
        assertEquals(GateBotApi.Doc("BQAC", 512), m.doc)
        assertFalse(m.edited)
        assertTrue(u[1].message!!.edited)
        assertEquals(-100777L, u[2].message!!.senderChatId)
        assertNull("не сообщение — без сообщения", u[5].message)
    }

    @Test
    fun `скачивается только то, что может пойти во врата`() {
        val u = parsed()
        assertTrue(GateBotApi.needsFile(u[0].message!!))
        assertFalse("правка", GateBotApi.needsFile(u[1].message!!))
        assertFalse("от имени чата, не личка", GateBotApi.needsFile(u[2].message!!))
        assertFalse("не файл", GateBotApi.needsFile(u[3].message!!))
        assertFalse("больше предела конверта", GateBotApi.needsFile(u[4].message!!))
    }

    @Test
    fun `правка и от имени чата не доходят до врат`() {
        val u = parsed()
        assertTrue(GateTelegram.incoming(u[1].message!!.toTg(ByteArray(0))) is GateTelegram.Incoming.NotGate)
        assertTrue(GateTelegram.incoming(u[2].message!!.toTg(null)) is GateTelegram.Incoming.NotGate)
    }

    @Test
    fun `кто я и куда ушло`() {
        assertEquals(GateBotApi.Me(42, "hope_bot"), GateBotApi.me(TgJson.parse("""{"id":42,"is_bot":true,"username":"hope_bot"}""")))
        assertNull(GateBotApi.me(TgJson.parse("""{"is_bot":true}""")))
        assertEquals(222L, GateBotApi.sentChatId(TgJson.parse("""{"message_id":9,"chat":{"id":222,"type":"private"}}""")))
        assertEquals("documents/file_1.txt", GateBotApi.filePath(TgJson.parse("""{"file_id":"x","file_path":"documents/file_1.txt"}""")))
    }

    @Test
    fun `тело отправки файла — поле чата и файл байт в байт`() {
        val file = "trafaret 3 2 00000000 sig\n{}".toByteArray()
        val body = GateBotApi.documentBody("@kate_ai_bot", "trafaret.txt", file, "b0b0")
        val text = String(body, Charsets.UTF_8)
        assertTrue(text.contains("name=\"chat_id\"\r\n\r\n@kate_ai_bot\r\n"))
        assertTrue(text.contains("filename=\"trafaret.txt\""))
        assertTrue(text.contains("\r\n\r\n" + String(file) + "\r\n--b0b0--\r\n"))
        try {
            GateBotApi.documentBody("1", "f.txt", "xx b0b0 xx".toByteArray(), "b0b0")
            fail("разделитель в файле")
        } catch (e: IllegalArgumentException) {
        }
        assertEquals("konvert.txt", GateBotApi.fileNameOf("konvert 1 0 00000000\n".toByteArray()))
    }

    @Test
    fun `нестрогий JSON — испорченное всё равно отказ`() {
        for (bad in listOf("{", """{"a":}""", "[1,]", """{"a":1} x""", "nul")) {
            try {
                TgJson.parse(bad)
                fail(bad)
            } catch (e: TgJson.Malformed) {
            }
        }
        assertEquals(1.5, TgJson.parse("1.5"))
        assertNull(TgJson.parse("null"))
    }

    @Test
    fun `номер из ключа — только у ключа аккаунта`() {
        assertEquals(222L, GateTelegram.userId(GateTelegram.userKey(222)))
        assertNull(GateTelegram.userId(GateTelegram.chatKey(222)))
    }
}
