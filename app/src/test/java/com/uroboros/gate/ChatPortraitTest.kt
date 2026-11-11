package com.uroboros.gate

import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.PersonFacts
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Portrait
import com.uroboros.memory.nav.RetellTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPortraitTest {

    private val t0 = 1_000_000_000_000L
    private val owner = "tg:user:1"
    private val praxis = "tg:user:2"
    private val table = RetellTable.parse(sequenceOf("работаю работаешь", "люблю любишь", "предпочитаю предпочитаешь"))

    private fun record(id: Long, text: String, speaker: String? = null, at: Long = t0) =
        Sticker(id = id, content = text, createdAt = at, source = SourceKind.USER_STATED.name, speaker = speaker)

    private val records = listOf(
        record(1, "Я работаю по субботам."),
        record(2, "Praxis: Я люблю поезда.", praxis),
        record(3, "Praxis: Путаницу я предпочитаю исправлять.", praxis),
    )

    private fun of(scope: ChatRecall.Scope, sender: String, question: String, marks: Map<PersonFacts.Key, Boolean> = emptyMap()) =
        ChatPortrait.of(scope, sender, records, owner, "Иван", question, null, table, marks, t0 + 1000, t0 + 500, "чат")

    @Test
    fun `чей портрет — по границе чата`() {
        assertEquals(PersonKey.OWNER, ChatPortrait.personOf(ChatRecall.Scope.OWNER, owner))
        assertEquals(PersonKey(praxis), ChatPortrait.personOf(ChatRecall.Scope.PERSON, praxis))
        assertNull(ChatPortrait.personOf(ChatRecall.Scope.GROUP, praxis))
    }

    @Test
    fun `группе портрета нет — ни строки`() {
        val fed = of(ChatRecall.Scope.GROUP, praxis, "Что ты знаешь обо мне?")
        assertTrue(fed.lines.isEmpty())
        assertEquals(Portrait.Reason.NONE, fed.reason)
        assertTrue(fed.meter, fed.meter.endsWith("группе не положен"))
        assertEquals(ChatRecall.message(listOf("x")), ChatPortrait.message(fed, listOf("x")))
    }

    @Test
    fun `личка владельца — его слова, с именем и создателем, без слов другого`() {
        val fed = of(ChatRecall.Scope.OWNER, owner, "Что ты знаешь обо мне?")
        assertEquals(Portrait.Reason.QUESTION, fed.reason)
        assertEquals(listOf("ты работаешь по субботам."), fed.texts)
        val msg = ChatPortrait.message(fed, listOf("Ещё запись"))!!
        assertTrue(msg, msg.startsWith("Ты — Иван, мой создатель."))
        assertFalse(msg, msg.contains("поезд"))
        assertTrue(msg, msg.endsWith("Ещё запись"))
        assertEquals(listOf("Я работаю по субботам."), fed.records)
    }

    @Test
    fun `личка другого — только его слова, подпись снята, без «создателя»`() {
        val fed = of(ChatRecall.Scope.PERSON, praxis, "Что ты знаешь обо мне?")
        assertEquals(setOf("ты любишь поезда.", "ты путаницу предпочитаешь исправлять."), fed.texts.toSet())
        val msg = ChatPortrait.message(fed, emptyList())!!
        assertTrue(msg, msg.startsWith(Portrait.HEADER))
        assertFalse(msg, msg.contains("создатель"))
        assertFalse(msg, msg.contains("субботам"))
        assertFalse(msg, msg.contains("Praxis:"))
    }

    @Test
    fun `факты другого человека встают первыми`() {
        val marks = mapOf(PersonFacts.Key(3, "Путаницу я предпочитаю исправлять.") to true)
        val fed = of(ChatRecall.Scope.PERSON, praxis, "Что ты знаешь обо мне?", marks)
        assertEquals("ты путаницу предпочитаешь исправлять.", fed.texts.first())
    }

    @Test
    fun `без повода портрет молчит, сообщение — как прежде`() {
        val fed = of(ChatRecall.Scope.OWNER, owner, "Как погода?")
        assertEquals(Portrait.Reason.NONE, fed.reason)
        assertTrue(fed.lines.isEmpty())
        assertEquals("a\nb", ChatPortrait.message(fed, listOf("a", "b")))
        assertNull(ChatPortrait.message(fed, emptyList()))
    }

    @Test
    fun `без таблицы разворота портрет не подаётся, прибор говорит почему`() {
        val fed = ChatPortrait.of(ChatRecall.Scope.OWNER, owner, records, owner, "Иван", "Что ты знаешь обо мне?", null, null, emptyMap(), t0, t0, "чат")
        assertTrue(fed.lines.isEmpty())
        assertTrue(fed.meter, fed.meter.contains("таблица разворота не готова"))
        assertFalse("нечего сказать — это не «знаю ничего»", fed.nothing)
    }

    @Test
    fun `чужая речь внутри слов другого человека остаётся чужой`() {
        // Дверь записи подписывает слова человека его подписью; внутри — переданная им реплика Клода.
        val mixed = listOf(record(4, "Praxis: Клод: Я люблю код.", praxis))
        val r = Portrait.of(mixed, PersonKey(praxis), ownerKey = owner)
        assertTrue(r.passed.isEmpty())
    }
}
