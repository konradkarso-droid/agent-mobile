package com.uroboros.memory.nav

import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.Coordinates.Address
import com.uroboros.memory.nav.Coordinates.TimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinatesTest {

    private val owner = SourceKind.USER_STATED.name
    private val agent = SourceKind.AGENT_INFERRED.name
    private val ocr = SourceKind.OCR_EXTRACTED.name

    // --- Кто сказал, кому, о ком ---

    @Test
    fun `кто сказал — по source`() {
        assertEquals(PersonKey.OWNER, Coordinates.speakerOf(owner))
        assertEquals(PersonKey.AGENT, Coordinates.speakerOf(agent))
        assertNull(Coordinates.speakerOf(ocr))
    }

    @Test
    fun `на экране владелец говорит агенту, агент владельцу`() {
        assertEquals(PersonKey.AGENT, Coordinates.addresseeOf(PersonKey.OWNER))
        assertEquals(PersonKey.OWNER, Coordinates.addresseeOf(PersonKey.AGENT))
    }

    @Test
    fun `смешанная запись владельца — о владельце и об агенте`() {
        val about = Coordinates.aboutRecord("Работаю над твоей памятью", owner)
        assertEquals(setOf(PersonKey.OWNER, PersonKey.AGENT), about.persons)
        assertEquals("владелец → владелец+агент", Coordinates.mark(PersonKey.OWNER, about))
    }

    @Test
    fun `запись владельца со вторым лицом — только об агенте`() {
        val about = Coordinates.aboutRecord("Будет тебе новый опыт, не волнуйся", owner)
        assertEquals(setOf(PersonKey.AGENT), about.persons)
    }

    @Test
    fun `вывод агента с я — об агенте`() {
        val about = Coordinates.aboutRecord("Я думаю, что радуга красивая", agent)
        assertEquals(setOf(PersonKey.AGENT), about.persons)
    }

    @Test
    fun `распознанное с картинки — не ясно, даже с я`() {
        val about = Coordinates.aboutRecord("Я люблю чай", ocr)
        assertTrue(about.unclear)
        assertTrue(about.persons.isEmpty())
    }

    @Test
    fun `безличная запись — о мире`() {
        val about = Coordinates.aboutRecord("Вода кипит при ста градусах", owner)
        assertTrue(about.aboutWorld)
        assertEquals("владелец → о мире", Coordinates.mark(PersonKey.OWNER, about))
    }

    // --- Время хода ---

    @Test
    fun `записанное время хода — как есть`() {
        val t = Coordinates.turnTime(100L, "вопрос", 500L) { 50L }
        assertEquals(Coordinates.TurnTime(100L, TimeSource.RECORDED), t)
    }

    @Test
    fun `старый ход — по записи памяти с тем же текстом`() {
        val t = Coordinates.turnTime(null, "Люблю чай", 500L) { if (it == "Люблю чай") 70L else null }
        assertEquals(Coordinates.TurnTime(70L, TimeSource.FROM_MEMORY), t)
    }

    @Test
    fun `старый ход без записи — не позже закрытия`() {
        val t = Coordinates.turnTime(null, "Люблю чай", 500L) { null }
        assertEquals(Coordinates.TurnTime(500L, TimeSource.NOT_LATER_THAN_CLOSE), t)
    }

    @Test
    fun `ход агента первым в открытой ленте — время неизвестно`() {
        val t = Coordinates.turnTime(null, "", null) { 1L }
        assertEquals(Coordinates.TurnTime(null, TimeSource.UNKNOWN), t)
    }

    // --- Адрес вопроса ---

    @Test
    fun `вопрос со вторым лицом — к агенту`() {
        assertEquals(Address.AGENT, Coordinates.questionAddress("Что нового у тебя за этот день?"))
    }

    @Test
    fun `вопрос с первым лицом — о владельце`() {
        assertEquals(Address.OWNER, Coordinates.questionAddress("Как меня зовут?"))
    }

    @Test
    fun `оба лица — оба`() {
        assertEquals(Address.BOTH, Coordinates.questionAddress("Что знаешь обо мне?"))
        assertEquals(Address.BOTH, Coordinates.questionAddress("Что мы с тобой обсуждали?"))
    }

    @Test
    fun `просьба без лица — адрес не определён`() {
        assertEquals(Address.UNDEFINED, Coordinates.questionAddress("Назови цвета радуги"))
    }

    @Test
    fun `вопрос без лица на экране — к агенту`() {
        assertEquals(Address.AGENT, Coordinates.questionAddress("Что снилось?"))
    }

    @Test
    fun `продолжение наследует адрес прошлого вопроса эпизода`() {
        assertEquals(Address.OWNER, Coordinates.questionAddress("А про чай?", previous = Address.OWNER))
    }

    @Test
    fun `прошлый вопрос без адреса не наследуется`() {
        assertEquals(Address.AGENT, Coordinates.questionAddress("А про чай?", previous = Address.UNDEFINED))
    }

    @Test
    fun `в группе вопрос без лица — не определён`() {
        assertEquals(Address.UNDEFINED, Coordinates.questionAddress("Что снилось?", place = Place.GROUP))
    }

    @Test
    fun `объявленная цена — безличный вопрос о мире получает адрес агент`() {
        // Прошлый вопрос о мире тоже получил «агент» по тому же правилу.
        val first = Coordinates.questionAddress("Сколько цветов у радуги?")
        assertEquals(Address.AGENT, first)
        assertEquals(Address.AGENT, Coordinates.questionAddress("А у спектра?", previous = first))
    }

    // --- Стыковочный узел ---

    @Test
    fun `ключи владельца и агента различны и не имена`() {
        assertFalse(PersonKey.OWNER == PersonKey.AGENT)
        assertEquals(Place.SCREEN, Place.values().first())
    }
}

class AddressInRibbonTest {

    private val hour = Episodes.EPISODE_SILENCE_MS

    @Test
    fun `продолжение наследует адрес в том же эпизоде`() {
        val history = listOf("Как меня зовут?" to 0L)
        assertEquals(Address.OWNER, Coordinates.addressInRibbon(history, "А про чай?", 60_000L))
    }

    @Test
    fun `после часа тишины продолжение не наследует`() {
        val history = listOf("Как меня зовут?" to 0L)
        assertEquals(Address.AGENT, Coordinates.addressInRibbon(history, "А про чай?", hour + 1))
    }

    @Test
    fun `ход агента первым пропускается`() {
        val history = listOf("Как меня зовут?" to 0L, "" to 30_000L)
        assertEquals(Address.OWNER, Coordinates.addressInRibbon(history, "А про чай?", 60_000L))
    }

    @Test
    fun `пустая лента — правило без лица`() {
        assertEquals(Address.AGENT, Coordinates.addressInRibbon(emptyList(), "Что снилось?", 0L))
    }
}
