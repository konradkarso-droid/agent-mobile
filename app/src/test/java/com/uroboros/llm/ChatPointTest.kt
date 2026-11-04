package com.uroboros.llm

import com.uroboros.llm.ChatPoint.Outcome
import com.uroboros.llm.ChatPoint.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatPointTest {

    private val petya = "tg:chat:1"
    private val group = "tg:chat:-100"

    @Test
    fun `ход чата после экрана — поднять его файл, записывать нечего`() {
        val b = ChatPoint.before(State(), petya)
        assertNull(b.save)
        assertEquals(petya, b.lift)
    }

    @Test
    fun `тот же чат подряд — ни записи, ни подъёма`() {
        val s = State(petya, owned = true, onDisk = false)
        assertEquals(ChatPoint.Before(null, null), ChatPoint.before(s, petya))
    }

    @Test
    fun `чужая работа после чата — снимок чата в его файл`() {
        val s = State(petya, owned = true, onDisk = false)
        assertEquals(ChatPoint.Before(petya, null), ChatPoint.before(s, null))
        assertEquals(ChatPoint.Before(petya, group), ChatPoint.before(s, group))
    }

    @Test
    fun `не свой снимок и уже записанный — не пишутся`() {
        assertNull("в слоте чужой снимок", ChatPoint.before(State(petya, owned = false), null).save)
        assertNull("уже на диске", ChatPoint.before(State(petya, owned = true, onDisk = true), null).save)
    }

    @Test
    fun `чей снимок в слоте — по исходу хода`() {
        val lifted = ChatPoint.afterLift(petya, ok = true)
        assertEquals(State(petya, owned = true, onDisk = true), lifted)
        assertEquals("снят новый — на диске прежний", State(petya, true, false), ChatPoint.afterChat(lifted, petya, Outcome.TAKEN))
        assertEquals("граница та же — на диске тот же", lifted, ChatPoint.afterChat(lifted, petya, Outcome.SAME))
        assertEquals("не снят — слот прежний", lifted, ChatPoint.afterChat(lifted, petya, Outcome.NOT_TAKEN))
    }

    @Test
    fun `подъём не удался и снимок не снят — слот не чата`() {
        val failed = ChatPoint.afterLift(petya, ok = false)
        assertEquals(State(), failed)
        // Слот остался прежним — экрана; под именем чата его писать нельзя.
        assertEquals(State(petya, owned = false, onDisk = false), ChatPoint.afterChat(failed, petya, Outcome.NOT_TAKEN))
        assertEquals(State(petya, owned = false, onDisk = false), ChatPoint.afterChat(failed, petya, Outcome.NONE))
    }

    @Test
    fun `запись удалась — снимок на диске, не удалась — всё как было`() {
        val s = State(petya, owned = true, onDisk = false)
        assertEquals(s.copy(onDisk = true), ChatPoint.afterSave(s, ok = true))
        assertEquals(s, ChatPoint.afterSave(s, ok = false))
    }

    @Test
    fun `файлов сверх предела — стираются самые давние`() {
        val files = listOf("a" to 10L, "b" to 30L, "c" to 20L)
        assertEquals(listOf("a"), ChatPoint.extra(files, max = 2))
        assertEquals(emptyList<String>(), ChatPoint.extra(files.take(2), max = 2))
    }
}
