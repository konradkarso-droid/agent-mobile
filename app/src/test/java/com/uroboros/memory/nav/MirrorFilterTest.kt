package com.uroboros.memory.nav

import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.Coordinates.Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `на другие адреса молчит`() {
        for (a in listOf(Address.OWNER, Address.BOTH, Address.UNDEFINED)) {
            assertTrue(MirrorFilter.keeps(mixed, a))
            assertEquals(0, MirrorFilter.apply(listOf(mixed, aboutOwner), a).second)
        }
    }

    @Test
    fun `apply сохраняет порядок и считает снятые`() {
        val (kept, removed) = MirrorFilter.apply(listOf(mixed, aboutAgent, aboutOwner, world), Address.AGENT)
        assertEquals(listOf(aboutAgent, world), kept)
        assertEquals(2, removed)
    }

    @Test
    fun `строка прибора на каждый адрес`() {
        assertEquals("адрес — агент · снято записей с чужим «я»: 2", MirrorFilter.meterLine(Address.AGENT, 2))
        assertEquals("адрес — владелец", MirrorFilter.meterLine(Address.OWNER, 0))
        assertEquals("адрес не определён — отбор как обычно", MirrorFilter.meterLine(Address.UNDEFINED, 0))
    }
}
