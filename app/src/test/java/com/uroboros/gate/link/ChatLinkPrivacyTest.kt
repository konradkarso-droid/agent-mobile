package com.uroboros.gate.link

import com.uroboros.gate.GateBotApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строка прибора «Приватность в группах» (ChatLink.privacyLine): она есть
 * всегда — и до первого сообщения, и до открытия канала, — и говорит, почему
 * не знает, а не молчит.
 */
class ChatLinkPrivacyTest {

    @Test
    fun `телеграм сказал — строка говорит, что видно`() {
        assertEquals(
            "Приватность в группах: выключена — вижу все сообщения",
            ChatLink.privacyLine(GateBotApi.Me(900, "bot", readsAllGroupMessages = true)),
        )
        assertTrue(ChatLink.privacyLine(GateBotApi.Me(900, "bot", readsAllGroupMessages = false)).contains("включена"))
    }

    @Test
    fun `телеграм не сказал — так и сказано`() {
        assertEquals(
            "Приватность в группах: телеграм не сказал",
            ChatLink.privacyLine(GateBotApi.Me(900, "bot", readsAllGroupMessages = null)),
        )
    }

    @Test
    fun `канал не открыт — строка не молчит и не гадает`() {
        val line = ChatLink.privacyLine(null)
        assertTrue(line, line.startsWith("Приватность в группах: не знаю"))
        assertTrue("не выдаёт догадку за ответ телеграма", !line.contains("включена") && !line.contains("выключена"))
    }
}
