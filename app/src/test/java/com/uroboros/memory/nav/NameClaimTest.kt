package com.uroboros.memory.nav

import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NameClaimTest {

    @Before
    fun verbs() = TestVerbs.install()

    private fun record(
        id: Long,
        text: String,
        at: Long = id,
        source: SourceKind = SourceKind.USER_STATED,
        rejectedAt: Long? = null,
    ) = Sticker(id = id, content = text, createdAt = at, source = source.name, rejectedAt = rejectedAt)

    // --- Обороты из списка ---

    @Test
    fun `объявленные обороты дают имя`() {
        assertEquals("Кэп", NameClaim.claimed("Зови меня Кэп"))
        assertEquals("Кэп", NameClaim.claimed("зови меня Кэп."))
        assertEquals("Кэп", NameClaim.claimed("Зовите меня Кэп!"))
        assertEquals("Кэп", NameClaim.claimed("Меня зовут Кэп."))
        assertEquals("Иван Петрович", NameClaim.claimed("Меня зовут Иван Петрович"))
        assertEquals("Кэп", NameClaim.claimed("Моё имя — Кэп"))
        assertEquals("Кэп", NameClaim.claimed("Мое имя Кэп"))
        assertEquals("Кэп", NameClaim.claimed("Зови меня «Кэп»"))
        assertEquals("Кэп", NameClaim.claimed("Зови меня Кэп)"))
    }

    // --- Молчание: граница закреплена ---

    @Test
    fun `вопрос о своём имени - не заявление`() {
        assertNull(NameClaim.claimed("Как меня зовут?"))
        assertNull(NameClaim.claimed("Меня зовут Кэп?"))
    }

    @Test
    fun `зовут в значении приглашают и зови в значении позови - не имя`() {
        assertNull(NameClaim.claimed("Меня зовут на день рождения."))
        assertNull(NameClaim.claimed("Меня зовут Петя на работу."))
        assertNull(NameClaim.claimed("Зови меня, если что."))
    }

    @Test
    fun `имя строчными - не берётся`() {
        assertNull(NameClaim.claimed("зови меня кэп"))
    }

    @Test
    fun `имя агенту фразой - не ловится`() {
        assertNull(NameClaim.claimed("Тебя зовут Хоуп."))
        assertNull(NameClaim.claimed("Зови себя Хоуп."))
    }

    @Test
    fun `обороты вне списка - не ловятся`() {
        assertNull(NameClaim.claimed("Называй меня Кэпом."))
        assertNull(NameClaim.claimed("Можешь звать меня Кэп."))
        assertNull(NameClaim.claimed("Друзья зовут меня Кэп."))
    }

    // --- Поиск по записям ---

    @Test
    fun `действует последнее заявление, прежние считаются`() {
        val r = NameClaim.of(listOf(
            record(1, "Зови меня Кэп", at = 10),
            record(2, "Я работаю по субботам.", at = 20),
            record(3, "Привет. Зови меня Сэр.", at = 30),
            record(4, "Меня зовут Кэп", at = 5),
        ))
        assertEquals(4, r.records)
        assertEquals("Сэр", r.found!!.name)
        assertEquals(3L, r.found!!.recordId)
        assertEquals(1, r.found!!.times)
    }

    @Test
    fun `повтор того же имени - заявлено раз`() {
        val r = NameClaim.of(listOf(record(1, "Зови меня Кэп", at = 10), record(2, "Меня зовут Кэп.", at = 20)))
        assertEquals(2, r.found!!.times)
        assertEquals(2L, r.found!!.recordId)
    }

    @Test
    fun `отвергнутые записи и записи агента не смотрятся`() {
        val r = NameClaim.of(listOf(
            record(1, "Зови меня Кэп", rejectedAt = 99),
            record(2, "Зови меня Кэп", source = SourceKind.AGENT_INFERRED),
        ))
        assertEquals(0, r.records)
        assertNull(r.found)
    }

    @Test
    fun `прибор отличает не задано, найдено и не ушло модели`() {
        val age: (Long) -> String = { "сегодня" }
        assertTrue(NameClaim.meterLine(NameClaim.Result(5, null), age).contains("не задано — заявлений 0, просмотрено записей 5"))
        val found = NameClaim.Result(5, NameClaim.Found("Кэп", 7, 1, 2))
        assertEquals(
            "Имя собеседника: Кэп — с его слов, сегодня, заявлено раз: 2 · запись №7",
            NameClaim.meterLine(found, age),
        )
        assertTrue(NameClaim.meterLine(found, age, fed = false).endsWith("модели не ушло: строк портрета нет"))
        assertTrue(NameClaim.meterLine(null, age).contains("не искалось"))
    }
}
