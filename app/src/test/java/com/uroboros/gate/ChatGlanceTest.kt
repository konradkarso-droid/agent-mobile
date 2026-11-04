package com.uroboros.gate

import com.uroboros.llm.ConversationJournal
import com.uroboros.llm.Glance
import com.uroboros.memory.desk.Promises
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGlanceTest {

    private val messages = listOf(ConversationJournal.ROLE_USER to "Иван: Сколько у тебя записей?")
    private val noHalt = ChatGlance.Halt { null }

    /** Проходы по очереди из [passes]; что получал каждый — в [seen]. */
    private class Script(vararg passes: ChatGlance.Pass) {
        private val left = ArrayDeque(passes.toList())
        val seen = ArrayList<List<Pair<String, String>>>()
        suspend fun pass(msgs: List<Pair<String, String>>): ChatGlance.Pass {
            seen += msgs
            return left.removeFirst()
        }
    }

    private fun run(script: Script, desk: String = "доска", halt: ChatGlance.Halt = noHalt) =
        runBlocking { ChatGlance.run(messages, { script.pass(it) }, { desk }, halt) }

    @Test
    fun `без вызова — ответ первого прохода, второго нет`() {
        val s = Script(ChatGlance.Pass("Привет!", called = false))
        assertEquals(ChatGlance.Outcome.Answer("Привет!", ChatGlance.Look.NONE), run(s))
        assertEquals(1, s.seen.size)
    }

    @Test
    fun `пустой проход без вызова — ответа нет, и сказано почему`() {
        val o = run(Script(ChatGlance.Pass("  ", called = false)))
        assertEquals(ChatGlance.Outcome.NoAnswer("модель не выдала ни знака", ChatGlance.Look.NONE), o)
    }

    @Test
    fun `вызов — второй проход с доской, ответ его, сказанное до вызова не в ответе`() {
        val s = Script(ChatGlance.Pass("Сейчас гляну.", called = true), ChatGlance.Pass("Записей 677.", called = false))
        assertEquals(ChatGlance.Outcome.Answer("Записей 677.", ChatGlance.Look.LOOKED), run(s, desk = "Память: записей 677"))
        val second = s.seen[1]
        assertEquals(messages, second.take(1))
        assertEquals(ConversationJournal.ROLE_ASSISTANT to Glance.callMessage("Сейчас гляну."), second[1])
        assertEquals(ConversationJournal.ROLE_USER, second[2].first)
        assertTrue(second[2].second.startsWith("<tool_response>\n${Glance.DESK_HEADER}\n"))
        assertTrue("Память: записей 677" in second[2].second)
    }

    @Test
    fun `второй вызов подряд доску снова не показывает — ответ режется по нему`() {
        val s = Script(ChatGlance.Pass("", called = true), ChatGlance.Pass("Записей 677.", called = true))
        assertEquals(ChatGlance.Outcome.Answer("Записей 677.", ChatGlance.Look.LOOKED), run(s))
        assertEquals(2, s.seen.size)
    }

    @Test
    fun `после доски пусто — один повтор, и только один`() {
        val ok = Script(ChatGlance.Pass("", called = true), ChatGlance.Pass("", called = false), ChatGlance.Pass("Записей 677.", called = false))
        assertEquals(ChatGlance.Outcome.Answer("Записей 677.", ChatGlance.Look.RETRIED), run(ok))
        assertEquals(ok.seen[1], ok.seen[2])

        val empty = Script(ChatGlance.Pass("", called = true), ChatGlance.Pass("", called = true), ChatGlance.Pass(" ", called = false))
        val o = run(empty)
        assertTrue(o is ChatGlance.Outcome.NoAnswer && o.look == ChatGlance.Look.RETRIED)
        assertEquals(3, empty.seen.size)
    }

    @Test
    fun `оборванный проход не повторяется`() {
        val first = Script(ChatGlance.Pass("", called = false, broken = "сторож: нагрев"))
        assertEquals(ChatGlance.Outcome.NoAnswer("сторож: нагрев", ChatGlance.Look.NONE), run(first))
        assertEquals(1, first.seen.size)

        val second = Script(ChatGlance.Pass("", called = true), ChatGlance.Pass("", called = false, broken = "движок: сбой"))
        val o = run(second)
        assertTrue(o is ChatGlance.Outcome.NoAnswer && "движок: сбой" in o.why)
        assertEquals(2, second.seen.size)
    }

    @Test
    fun `стоп или нагрев после вызова — второго прохода нет`() {
        val s = Script(ChatGlance.Pass("", called = true))
        val o = run(s, halt = { "аварийный стоп" })
        assertEquals(ChatGlance.Outcome.NoAnswer("позвал приборы, второй проход не пошёл — аварийный стоп", ChatGlance.Look.LOOKED), o)
        assertEquals(1, s.seen.size)
    }

    @Test
    fun `проверка перед повтором — повтора нет`() {
        var calls = 0
        val s = Script(ChatGlance.Pass("", called = true), ChatGlance.Pass("", called = false))
        val o = run(s, halt = { if (calls++ == 0) null else "сторож: нагрев" })
        assertTrue(o is ChatGlance.Outcome.NoAnswer && "сторож: нагрев" in o.why)
        assertEquals(2, s.seen.size)
    }

    @Test
    fun `доска чата — тело, записи, скорость, обещания, и что здесь не называется`() {
        val d = ChatGlance.desk("Тело: заряд 52%, 34.0°C, зона норма", 677, "12.5", "Обещаний в этом чате за 7 дн. не нашлось.")
        assertEquals(
            listOf(
                "Тело: заряд 52%, 34.0°C, зона норма",
                "Память: записей 677",
                "Прошлый ответ в этом чате: выдача 12.5 ток/с",
                "Обещаний в этом чате за 7 дн. не нашлось.",
                ChatGlance.NOT_HERE,
            ),
            d.lines(),
        )
    }

    @Test
    fun `доска чата молчит о модели и ленте экрана, нет числа или скорости — сказано или пропущено`() {
        val d = ChatGlance.desk("Тело: …", null, null, "обещания")
        assertFalse(d.lines().any { it.startsWith("Модель:") || it.startsWith("Лента:") })
        assertTrue("Память: число записей сейчас не прочиталось." in d.lines())
        assertFalse("Прошлый ответ" in d)
    }

    @Test
    fun `обещания для доски — не прочитались, нет, есть — три разные строки`() {
        assertEquals("Обещания этого чата мне сейчас не видны.", ChatGlance.promisesLine(null) { "" })
        assertEquals("Обещаний в этом чате за ${Promises.DAYS} дн. не нашлось.", ChatGlance.promisesLine(emptyList()) { "" })
        val p = Promises.Promise("Я напомню завтра.", null, 0L, false, Promises.To("Пете", "с Петей", "личка", "tg:chat:1"))
        assertEquals(ChatPromises.chatText(listOf(p)) { "3 ноября" }, ChatGlance.promisesLine(listOf(p)) { "3 ноября" })
    }

    @Test
    fun `строка записей одна на экран и чат`() {
        assertEquals("Память: записей 677", Glance.recordsLine(677))
    }
}
