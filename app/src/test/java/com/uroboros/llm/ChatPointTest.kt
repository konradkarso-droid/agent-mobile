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

    // Почему у чата нет файла снимка (missingWhy). Часы — минуты от полуночи, для чтения.
    private val clock: (Long) -> String = { ms -> "%02d:%02d".format(ms / 60_000 / 60, ms / 60_000 % 60) }
    private fun at(h: Int, m: Int) = (h * 60L + m) * 60_000

    @Test
    fun `нет файла - снимок пропал незаписанным`() {
        // Ход чата 02:41, модель выгружена по памяти 02:47 — до записи дело не дошло.
        assertEquals(
            "снимок не записан: модель выгружена по сигналу памяти в 02:47",
            ChatPoint.missingWhy(null, at(2, 47), "модель выгружена по сигналу памяти", null, at(0, 10), clock),
        )
        // Пропажа после прежней записи — тоже она: файл описывал бы старую ленту, но его нет.
        assertEquals(
            "снимок не записан: модель загружалась заново в 03:00",
            ChatPoint.missingWhy(at(2, 50), at(3, 0), "модель загружалась заново", null, at(0, 10), clock),
        )
    }

    @Test
    fun `нет файла - записан, но стёрт`() {
        assertEquals(
            "записан в 02:50, стёрт по пределу файлов (${ChatPoint.MAX_FILES})",
            ChatPoint.missingWhy(at(2, 50), null, null, at(3, 5), at(0, 10), clock),
        )
        assertEquals(
            "записан в 02:50, но в 03:00 сменилась папка кэша (стена или модель) — снимок ушёл с прежней",
            ChatPoint.missingWhy(at(2, 50), null, null, null, at(3, 0), clock),
        )
    }

    @Test
    fun `нет файла - в этом запуске не писался`() {
        assertEquals("в этом запуске не записывался", ChatPoint.missingWhy(null, null, null, null, at(0, 10), clock))
    }

    @Test
    fun `молчит о причинах, которых не было`() {
        // Пропажа раньше удачной записи — не причина: запись её перекрыла.
        val after = ChatPoint.missingWhy(at(2, 50), at(2, 40), "модель загружалась заново", null, at(0, 10), clock)
        assertEquals("записан в 02:50, файла нет — отчего, не видно", after)
        // Стирание по пределу раньше записи и смена папки раньше записи — тоже нет.
        assertEquals(
            "записан в 02:50, файла нет — отчего, не видно",
            ChatPoint.missingWhy(at(2, 50), null, null, at(2, 0), at(1, 0), clock),
        )
    }
}
