package com.uroboros.gate

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateTelegramTest {

    private val now = 1_760_947_200_000L
    private val me = GateTelegram.userKey(789)
    private val agentB = GateTelegram.userKey(123)
    private val humanB = GateTelegram.userKey(456)

    private val packet = GatePacket(
        from = agentB, represents = humanB, to = me, number = 42L, sentMs = now,
        place = Place.DIRECT, saidBy = humanB, about = listOf(humanB), aboutUnclear = false, atMs = now,
        zone = "Europe/Moscow", source = SourceKind.USER_STATED, kind = SentenceKind.Kind.STATEMENT,
        topic = emptyList(), exact = emptyMap(), words = "в субботу я занят",
    )

    private fun msg(
        fromId: Long? = 123,
        privateChat: Boolean = true,
        senderChatId: Long? = null,
        edited: Boolean = false,
        file: ByteArray? = GateWire.write(packet),
    ) = TgMessage(chatId = fromId ?: -100500, privateChat = privateChat, fromId = fromId, senderChatId = senderChatId, edited = edited, dateSec = now / 1000, file = file)

    private fun notGate(m: TgMessage): String {
        val i = GateTelegram.incoming(m)
        assertTrue("ждали «не во врата», а: $i", i is GateTelegram.Incoming.NotGate)
        return (i as GateTelegram.Incoming.NotGate).words
    }

    @Test
    fun `ключи — по номеру, вид аккаунта в ключ не входит`() {
        assertEquals(PersonKey("tg:user:123"), GateTelegram.userKey(123))
        assertEquals(PersonKey("tg:chat:-100500"), GateTelegram.chatKey(-100500))
        // Номера до 52 бит — как у телеграма.
        assertEquals(PersonKey("tg:user:4503599627370495"), GateTelegram.userKey((1L shl 52) - 1))
    }

    @Test
    fun `пакет файлом в личке от своего имени — во врата, ключ канала от номера`() {
        val i = GateTelegram.incoming(msg()) as GateTelegram.Incoming.Gate
        assertEquals(agentB, i.sender)
        assertEquals(GateWire.Form.PACKET, i.form)
        assertEquals(GateIntake.Verdict.Accepted(packet), GateIntake(me).receive(i.raw, i.sender, now))
    }

    @Test
    fun `принял файлом — во врата как принял`() {
        val ack = GateAck(agentB, me, 42L, GateAck.Result.ACCEPTED, now, now)
        assertEquals(GateWire.Form.ACK, (GateTelegram.incoming(msg(file = GateWire.writeAck(ack))) as GateTelegram.Incoming.Gate).form)
    }

    @Test
    fun `правка уже отправленного — не во врата`() {
        assertEquals("Не пакет: правка уже отправленного — во врата не идёт", notGate(msg(edited = true)))
    }

    @Test
    fun `от имени чата — не во врата, даже если в from стоит номер`() {
        // Анонимный админ: в from — общий служебный аккаунт, отправитель — sender_chat.
        assertEquals("Не пакет: от имени чата tg:chat:-100500 — не принимаю", notGate(msg(fromId = 1087968824, senderChatId = -100500)))
    }

    @Test
    fun `без отправителя, не в личке, не файл, файл без шапки — не во врата`() {
        assertEquals("Не пакет: нет отправителя", notGate(msg(fromId = null)))
        assertEquals("Не пакет: не в личке (tg:chat:123) — пакеты только в личке", notGate(msg(privateChat = false)))
        assertEquals("Не пакет: не файл", notGate(msg(file = null)))
        assertEquals("Не пакет: файл без шапки врат", notGate(msg(file = "фото кота".toByteArray())))
    }

    @Test
    fun `пересланный пакет — врата отказывают, отправитель не тот`() {
        // Пакет агента Бориса переслал бот 666: ключ канала — пересылающий.
        val i = GateTelegram.incoming(msg(fromId = 666)) as GateTelegram.Incoming.Gate
        val v = GateIntake(me).receive(i.raw, i.sender, now) as GateIntake.Verdict.Refused
        assertEquals(GateRefusal.NOT_SENDER, v.refusal)
    }

    @Test
    fun `файл больше предела врат не скачивается, размер не известен — скачивается`() {
        assertTrue(GateTelegram.worthFetching(8 * 1024L))
        assertTrue(!GateTelegram.worthFetching(8 * 1024L + 1))
        assertTrue(GateTelegram.worthFetching(null))
    }

    @Test
    fun `время сервера из секунд сообщения`() {
        assertEquals(now / 1000 * 1000, msg().serverMs)
    }
}
