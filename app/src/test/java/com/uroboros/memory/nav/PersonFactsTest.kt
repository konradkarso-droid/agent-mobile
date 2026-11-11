package com.uroboros.memory.nav

import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonFactsTest {

    private val t0 = 1_000_000_000_000L

    private fun record(id: Long, text: String, at: Long, touches: Int = 0, speaker: String? = null) =
        Sticker(id = id, content = text, createdAt = at, source = SourceKind.USER_STATED.name, userMatchCount = touches, speaker = speaker)

    // --- Ответ модели ---

    @Test
    fun `ответ читается словом`() {
        assertEquals(PersonFacts.Verdict.FACT, PersonFacts.parse("Факт."))
        assertEquals(PersonFacts.Verdict.MOMENT, PersonFacts.parse(" момент"))
        assertEquals("«не факт, а момент» — момент", PersonFacts.Verdict.MOMENT, PersonFacts.parse("Не факт, а момент"))
        assertEquals(PersonFacts.Verdict.UNCLEAR, PersonFacts.parse("Не знаю"))
        assertEquals(PersonFacts.Verdict.UNCLEAR, PersonFacts.parse(""))
    }

    // --- Что размечать ---

    @Test
    fun `размечаются строки портрета, свежие первыми, без размеченных и не больше потолка`() {
        val records = listOf(
            record(1, "По профессии я химик.", t0),
            record(2, "Я люблю рубанки.", t0 + 1),
            record(3, "Я работаю по субботам.", t0 + 2),
        )
        val marked = setOf(PersonFacts.Key(2, "Я люблю рубанки."))
        val p = PersonFacts.toMark(records, null, marked, limit = 1)
        assertEquals(listOf(PersonFacts.Key(3, "Я работаю по субботам.")), p.next)
        assertEquals(2, p.total)
    }

    @Test
    fun `всё размечено — размечать нечего`() {
        val records = listOf(record(1, "По профессии я химик.", t0))
        val p = PersonFacts.toMark(records, null, setOf(PersonFacts.Key(1, "По профессии я химик.")))
        assertTrue(p.next.isEmpty())
        assertEquals(0, p.total)
    }

    @Test
    fun `собеседники — владелец и каждый ключ из канала, кроме ключа владельца`() {
        val records = listOf(
            record(1, "Я химик.", t0),
            record(2, "Я химик.", t0, speaker = "tg:user:1"),
            record(3, "Я пекарь.", t0, speaker = "tg:user:2"),
        )
        assertEquals(listOf(PersonKey.OWNER, PersonKey("tg:user:2")), PersonFacts.persons(records, "tg:user:1"))
    }

    // --- Порядок портрета ---

    private val moments = listOf(
        record(10, "Вот, теперь я понял, спасибо.", t0 + 50),
        record(11, "А вот теперь я, кажется, прозрел.", t0 + 40),
        record(12, "Я никогда бы не подумал, что такое возможно.", t0 + 30),
    )
    private val facts = listOf(
        record(20, "По профессии я химик.", t0, touches = 0),
        record(21, "Я состою в клубе фехтования.", t0 + 1, touches = 6),
    )

    @Test
    fun `факты первыми — по касаниям, холодный факт тоже`() {
        val marks = mapOf(
            PersonFacts.Key(20, "По профессии я химик.") to true,
            PersonFacts.Key(21, "Я состою в клубе фехтования.") to true,
            PersonFacts.Key(10, "Вот, теперь я понял, спасибо.") to false,
        )
        val chosen = Portrait.of(moments + facts, marks = marks).chosen.map { it.sentence }
        assertEquals(listOf("Я состою в клубе фехтования.", "По профессии я химик."), chosen.take(2))
        assertEquals("остальное — по прежнему правилу", 5, chosen.size)
    }

    @Test
    fun `без отметок порядок прежний — свежие первыми`() {
        val chosen = Portrait.of(moments + facts).chosen.map { it.sentence }
        assertEquals("Вот, теперь я понял, спасибо.", chosen.first())
    }

    @Test
    fun `одинаковые факты встают один раз`() {
        val records = listOf(record(1, "Я - Иван.", t0), record(2, "Я - Иван.", t0 + 1), record(3, "Я люблю рубанки.", t0 + 2))
        val marks = mapOf(PersonFacts.Key(1, "Я - Иван.") to true, PersonFacts.Key(2, "Я - Иван.") to true)
        val chosen = Portrait.of(records, marks = marks).chosen.map { it.sentence }
        assertEquals(listOf("Я - Иван.", "Я люблю рубанки."), chosen)
    }

    @Test
    fun `момент не встаёт впереди факта, даже свежий`() {
        val marks = mapOf(
            PersonFacts.Key(20, "По профессии я химик.") to true,
            PersonFacts.Key(10, "Вот, теперь я понял, спасибо.") to false,
        )
        val chosen = Portrait.of(moments + facts, marks = marks).chosen.map { it.sentence }
        assertEquals("По профессии я химик.", chosen.first())
    }

    // --- Приборы ---

    @Test
    fun `итог ночи различает все случаи`() {
        assertEquals("Факты: размечать нечего — все строки портретов размечены", PersonFacts.outcome(0, 0, 0, 0, null))
        assertEquals("Факты: не размечал — тихо 1 мин из 5", PersonFacts.outcome(0, 0, 0, 7, "тихо 1 мин из 5"))
        assertEquals(
            "Факты: размечено 4 (фактов 2, моментов 1, непонятных 1), осталось 3 · остановился: модель занята",
            PersonFacts.outcome(2, 1, 1, 3, "модель занята"),
        )
    }

    @Test
    fun `строка портрета говорит о разметке`() {
        val marks = mapOf(PersonFacts.Key(20, "По профессии я химик.") to true, PersonFacts.Key(10, "Вот, теперь я понял, спасибо.") to false)
        val r = Portrait.of(moments + facts, marks = marks)
        assertEquals("фактов 1, не размечено 3, в портрете фактов 1", PersonFacts.marksLine(r))
        assertEquals("факт", PersonFacts.label(r.passed.first { it.recordId == 20L }))
        assertEquals("момент", PersonFacts.label(r.passed.first { it.recordId == 10L }))
        assertEquals("не размечена", PersonFacts.label(r.passed.first { it.recordId == 11L }))
    }
}
