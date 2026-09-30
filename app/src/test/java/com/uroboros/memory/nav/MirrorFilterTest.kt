package com.uroboros.memory.nav

import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.Coordinates.Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorFilterTest {

    private fun record(id: Long, text: String, source: SourceKind = SourceKind.USER_STATED) =
        Sticker(id = id, content = text, source = source.name)

    private val mixed = record(1, "Работаю над твоей памятью")
    private val aboutOwner = record(2, "Я был у врача")
    private val aboutAgent = record(3, "Будет тебе новый опыт, не волнуйся")
    private val ocr = record(4, "Я люблю чай", SourceKind.OCR_EXTRACTED)
    private val agentOwn = record(5, "Я думаю, что радуга красивая", SourceKind.AGENT_INFERRED)
    private val world = record(6, "Вода кипит при ста градусах")
    private val unclear = record(7, "Тренируемся по субботам")
    private val mixedTwoSentences = record(10, "Сегодня отдыхаю. Вечером займусь твоими настройками")

    @Test
    fun `на адрес агент снимает смешанную и о владельце`() {
        assertFalse(MirrorFilter.keeps(mixed, Address.AGENT))
        assertFalse(MirrorFilter.keeps(aboutOwner, Address.AGENT))
    }

    @Test
    fun `на адрес агент пропускает второе лицо без чужого я, картинку, своё, мир и не ясно`() {
        for (r in listOf(aboutAgent, ocr, agentOwn, world, unclear)) {
            assertTrue(r.content, MirrorFilter.keeps(r, Address.AGENT))
        }
    }

    private val table = RetellTable.parse(
        sequenceOf("работаю работаешь", "отдыхаю отдыхаешь", "займусь займёшься")
    )
    private val aboutOwnerAddresses = listOf(Address.OWNER, Address.BOTH)

    @Test
    fun `без разворота смешанная снимается на адрес агент и не определён`() {
        for (a in listOf(Address.AGENT, Address.UNDEFINED)) {
            assertFalse(a.name, MirrorFilter.keeps(mixed, a))
            assertFalse(a.name, MirrorFilter.keeps(mixedTwoSentences, a))
        }
    }

    @Test
    fun `с разворотом смешанная владельца проходит пересказом на адрес не определён`() {
        assertTrue(MirrorFilter.keeps(mixed, Address.UNDEFINED, table))
        assertEquals(
            MirrorFilter.Shown("работаешь над моей памятью.", retold = true),
            MirrorFilter.shown(mixed, Address.UNDEFINED, table),
        )
    }

    // Граница (правило 0): на вопрос о собеседнике из записи владельца уходят
    // предложения, обращённые к агенту, — с таблицей и без.

    @Test
    fun `граница снимает запись владельца только из обращений к агенту`() {
        for (a in aboutOwnerAddresses) for (t in listOf(null, table)) {
            assertNull(a.name, MirrorFilter.shown(mixed, a, t))
            assertNull(a.name, MirrorFilter.shown(record(20, "У тебя есть сны. Ты не знаешь цвета радуги?"), a, t))
            assertNull(a.name, MirrorFilter.shown(record(24, "Хорошо, что помнишь сны. А какие цвета?"), a, t))
        }
    }

    @Test
    fun `граница оставляет от записи предложения не к агенту, цитатой`() {
        for (a in aboutOwnerAddresses) for (t in listOf(null, table)) {
            assertEquals(a.name, MirrorFilter.Shown("Сегодня отдыхаю.", retold = false), MirrorFilter.shown(mixedTwoSentences, a, t))
            assertEquals(a.name, MirrorFilter.Shown(aboutOwner.content, retold = false), MirrorFilter.shown(aboutOwner, a, t))
        }
    }

    @Test
    fun `граница не снимает просьбу без местоимения и мы с тобой`() {
        val advice = record(21, "Всегда носи с собой полотенце.")
        val us = record(22, "Мы с тобой говорили о саде")
        for (a in aboutOwnerAddresses) {
            assertEquals(a.name, advice.content, MirrorFilter.shown(advice, a)?.text)
            assertEquals(a.name, us.content, MirrorFilter.shown(us, a)?.text)
        }
    }

    @Test
    fun `граница не трогает записи агента и адрес агент`() {
        val agentAboutOwner = record(23, "Ты любишь чай", SourceKind.AGENT_INFERRED)
        assertEquals(agentAboutOwner.content, MirrorFilter.shown(agentAboutOwner, Address.OWNER)?.text)
        assertTrue(MirrorFilter.keeps(aboutAgent, Address.AGENT))
    }

    @Test
    fun `на адрес агент смешанная владельца снимается и с разворотом`() {
        assertFalse(MirrorFilter.keeps(mixed, Address.AGENT, table))
        assertFalse(MirrorFilter.keeps(mixedTwoSentences, Address.AGENT, table))
    }

    @Test
    fun `смешанная запись агента снимается и с разворотом`() {
        val agentMixed = record(11, "Я помню, что ты любишь чай", SourceKind.AGENT_INFERRED)
        for (a in Address.values()) assertFalse(a.name, MirrorFilter.keeps(agentMixed, a, table))
    }

    @Test
    fun `запись владельца только из вопросов и просьб снимается и с разворотом`() {
        val onlyAsks = record(12, "Проверь мою память. Ты помнишь, что я говорил?")
        assertTrue(MirrorFilter.isMixed(onlyAsks.content))
        assertFalse(MirrorFilter.keeps(onlyAsks, Address.UNDEFINED, table))
    }

    @Test
    fun `пересказом идёт только смешанная запись владельца при доступном развороте`() {
        assertEquals("работаешь над моей памятью.", MirrorFilter.retold(mixed, Address.OWNER, table))
        assertNull(MirrorFilter.retold(mixed, Address.OWNER, null))
        assertNull(MirrorFilter.retold(mixed, Address.AGENT, table))
        assertNull(MirrorFilter.retold(aboutOwner, Address.OWNER, table))
        assertNull(MirrorFilter.retold(aboutAgent, Address.BOTH, table))
    }

    @Test
    fun `apply с разворотом не снимает смешанную владельца на адрес не определён`() {
        assertEquals(0, MirrorFilter.apply(listOf(mixed, aboutOwner), Address.UNDEFINED, table).second)
    }

    @Test
    fun `на другие адреса запись с одним я владельца приходит`() {
        for (a in listOf(Address.OWNER, Address.BOTH, Address.UNDEFINED)) {
            assertTrue(a.name, MirrorFilter.keeps(aboutOwner, a))
            assertEquals(1, MirrorFilter.apply(listOf(mixed, aboutOwner), a).second)
        }
    }

    @Test
    fun `мы с тобой смешанной не считается`() {
        assertFalse(MirrorFilter.isMixed("Мы с тобой говорили о саде"))
        assertTrue(MirrorFilter.keeps(record(8, "Мы с тобой говорили о саде"), Address.UNDEFINED))
    }

    @Test
    fun `смешанная с картинки не снимается`() {
        assertTrue(MirrorFilter.keeps(record(9, "Работаю над твоей памятью", SourceKind.OCR_EXTRACTED), Address.BOTH))
    }

    @Test
    fun `apply сохраняет порядок и считает снятые`() {
        val (kept, removed) = MirrorFilter.apply(listOf(mixed, aboutAgent, aboutOwner, world), Address.AGENT)
        assertEquals(listOf(aboutAgent, world), kept)
        assertEquals(2, removed)
    }

    private val ready = RetellHolder.State.Ready(table, 12)

    @Test
    fun `строка прибора на каждый адрес при загруженной таблице`() {
        assertEquals("адрес — агент · снято записей с чужим «я»: 2", MirrorFilter.meterLine(Address.AGENT, 2, 0, ready))
        assertEquals(
            "адрес — владелец · снято границей (обращения к агенту): 0",
            MirrorFilter.meterLine(Address.OWNER, 0, 0, ready),
        )
        assertEquals(
            "адрес — владелец и агент · снято границей (обращения к агенту): 1",
            MirrorFilter.meterLine(Address.BOTH, 1, 0, ready),
        )
        // Таблица загружена, но разворот смешанных выключен (RetellHolder.MIXED):
        // строка не должна читаться как «развернуть было нечего».
        assertFalse(RetellHolder.MIXED)
        assertEquals(
            "адрес не определён · снято смешанных: 0 · разворот смешанных: выключен",
            MirrorFilter.meterLine(Address.UNDEFINED, 0, 1, ready),
        )
    }

    @Test
    fun `строка прибора при недоступном развороте`() {
        val loading = RetellHolder.State.Loading
        val failed = RetellHolder.State.Failed("FileNotFoundException")
        assertEquals("адрес — агент · снято записей с чужим «я»: 0", MirrorFilter.meterLine(Address.AGENT, 0, 0, failed))
        assertEquals(
            "адрес не определён · снято смешанных: 2 · разворот: таблица загружается",
            MirrorFilter.meterLine(Address.UNDEFINED, 2, 0, loading),
        )
        assertEquals(
            "адрес не определён · снято смешанных: 0 · разворот: таблица не загрузилась: FileNotFoundException",
            MirrorFilter.meterLine(Address.UNDEFINED, 0, 0, failed),
        )
        assertEquals(
            "адрес не определён · снято смешанных: 3 · разворот: выключен",
            MirrorFilter.meterLine(Address.UNDEFINED, 3, 0, RetellHolder.State.Off),
        )
    }
}
