package com.uroboros.llm

import com.dark.gguf_lib.models.DecodingMetrics
import com.dark.gguf_lib.models.GenerationEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Лестница ответа на поддельном движке: ответы движка — по очереди из списка,
 * конец прогона — как у настоящего: оборванный нами (вызов приборов) — отмена.
 */
class AnswerLadderTest {

    private class Call(val messages: List<Pair<String, String>>, val dry: String?, val stable: Int?, val first: Boolean)

    /** Ответ движка: текст и чем кончится прогон, если его не оборвут. */
    private class Reply(val text: String, val end: GenerationEnd = GenerationEnd.COMPLETED)

    private class FakeEngine(vararg replies: Reply) : AnswerLadder.Engine {
        val left = ArrayDeque(replies.toList())
        val calls = ArrayList<Call>()
        override var lastEnd = GenerationEnd.NOT_STARTED
        override val lastDryFed = 0
        override val wallText = "Я агент на телефоне."
        override fun generate(messages: List<Pair<String, String>>, dryAgainst: String?, stableMessages: Int?, first: Boolean): Flow<GenerationEvent> {
            calls += Call(messages, dryAgainst, stableMessages, first)
            val reply = left.removeFirst()
            return flow {
                lastEnd = GenerationEnd.RUNNING
                for (piece in reply.text.chunked(4)) emit(GenerationEvent.Token(piece))
                emit(GenerationEvent.Metrics(DecodingMetrics(tokensPredicted = reply.text.length / 4 + 1, tokensEvaluated = 100)))
                lastEnd = reply.end
            }.onCompletion { if (it != null) lastEnd = GenerationEnd.CANCELLED }
        }
    }

    private class FakeRibbon(
        private val turns: List<ConversationJournal.Turn> = emptyList(),
        private val own: List<String> = emptyList(),
    ) : AnswerLadder.Ribbon {
        var closed: Pair<String, String?>? = null
        var hidden: Pair<String, String>? = null
        override fun history() = turns
        override fun ownSpeech() = own
        override fun messages(without: Set<Int>) =
            listOf(ConversationJournal.ROLE_USER to "Иван: вопрос" + if (without.isEmpty()) "" else " (без $without)")
        override fun messages(without: Set<Int>, withoutRecall: Set<String>) =
            messages(without).map { (role, text) -> role to text + if (withoutRecall.isEmpty()) "" else " (без записей ${withoutRecall.size})" }
        override fun stableMessages(without: Set<Int>) = if (without.isEmpty()) 0 else null
        override fun close(answer: String, rejected: String?) {
            closed = answer to rejected
        }
        override fun hide(reason: String, first: String, rejected: List<ConversationJournal.Rejected>) {
            hidden = reason to first
        }
    }

    /** Трафарет из слов ответов в тестах: всё прочее — порча. */
    private val words = WordDamage.fromWords(
        ("привет как дела хорошо записей в памяти у меня лес грибы река первый второй третий вариант ответа про " +
            "я люблю гулять по лесу и собирать осенью всё спасибо сегодня мы просто болтаем правильно погода").split(" "),
    )

    private fun ladder(engine: FakeEngine, damage: WordDamage? = words) =
        AnswerLadder(engine, answerLimit = 300, damage = { damage })

    private fun run(
        engine: FakeEngine,
        ribbon: FakeRibbon = FakeRibbon(),
        question: String = "Как дела?",
        desk: String = "Память: записей 677",
        halt: () -> String? = { null },
        damage: WordDamage? = words,
    ) = runBlocking { ladder(engine, damage).run(ribbon, question, { desk }, halt) }

    private val call = "<tool_call>\n{\"name\": \"look_at_instruments\", \"arguments\": {}}\n</tool_call>"

    @Test
    fun `чистый ответ — один проход, ложится как есть, перехвата нет`() {
        val e = FakeEngine(Reply("Привет! Всё хорошо."))
        val r = FakeRibbon()
        val res = run(e, r)
        assertEquals("Привет! Всё хорошо." to null, r.closed)
        assertEquals(1, e.calls.size)
        assertTrue(e.calls[0].first)
        assertEquals(0, e.calls[0].stable)
        assertNull(res.intercept)
        assertFalse(res.glanced)
        assertEquals(GenerationEnd.COMPLETED, res.generationEnd)
    }

    @Test
    fun `вызов приборов — доска и второй проход, ответ его, сказанное до вызова не в ответе`() {
        val e = FakeEngine(Reply("Гляну. $call"), Reply("В памяти у меня 677 записей."))
        val r = FakeRibbon()
        val res = run(e, r)
        assertEquals("В памяти у меня 677 записей." to null, r.closed)
        assertTrue(res.glanced)
        val second = e.calls[1].messages
        assertEquals(ConversationJournal.ROLE_ASSISTANT to Glance.callMessage("Гляну. "), second[1])
        assertTrue(second[2].second.startsWith("<tool_response>\n${Glance.DESK_HEADER}\n"))
        assertTrue("Память: записей 677" in second[2].second)
        assertFalse(e.calls[1].first)
    }

    @Test
    fun `второй вызов подряд режется, и проход считается кончившимся сам`() {
        val e = FakeEngine(Reply(call), Reply("В памяти у меня 677 записей. $call"))
        val r = FakeRibbon()
        val res = run(e, r)
        assertEquals("В памяти у меня 677 записей." to null, r.closed)
        assertEquals(GenerationEnd.COMPLETED, res.generationEnd)
        assertEquals(2, e.calls.size)
    }

    @Test
    fun `после доски пусто — один повтор, и только один`() {
        val e = FakeEngine(Reply(call), Reply(call), Reply("В памяти у меня 677 записей."))
        val r = FakeRibbon()
        val res = run(e, r)
        assertTrue(res.glanceRetried)
        assertEquals("В памяти у меня 677 записей." to null, r.closed)
        assertEquals(3, e.calls.size)

        val empty = FakeEngine(Reply(call), Reply(call), Reply(call))
        val rr = FakeRibbon()
        val res2 = run(empty, rr)
        assertEquals(3, empty.calls.size)
        assertNull(rr.closed)
        assertEquals(0, res2.tokensSeen)
    }

    @Test
    fun `остановка перед доской — второго прохода нет, и сказано чем`() {
        val e = FakeEngine(Reply("Сейчас гляну. $call"))
        val r = FakeRibbon()
        val res = run(e, r, halt = { "сторож: нагрев" })
        assertEquals(1, e.calls.size)
        assertEquals("сторож: нагрев", res.haltedBy)
        assertTrue(res.glanced)
    }

    @Test
    fun `порча — вторая попытка на том же запросе, чистая вторая ложится, первый отброшен`() {
        val e = FakeEngine(Reply("Привет, всё повторяюек хорошо."), Reply("Привет, всё хорошо."))
        val r = FakeRibbon()
        val res = run(e, r)
        assertTrue(res.intercept?.cause is AnswerLadder.Cause.Damage)
        assertEquals("Привет, всё хорошо." to "Привет, всё повторяюек хорошо.", r.closed)
        assertEquals(0, e.calls[1].stable)
        assertNull(e.calls[1].dry)
    }

    @Test
    fun `порча и во второй — три варианта, взят первый годный`() {
        val e = FakeEngine(
            Reply("Привет повторяюек."),
            Reply("Привет порчаслово."),
            Reply("Вариант кривойслов.\nПервый вариант ответа про лес.\nВторой вариант ответа про реку."),
        )
        val r = FakeRibbon()
        val res = run(e, r)
        val third = res.intercept!!.third!!
        assertEquals("Первый вариант ответа про лес.", third.chosen)
        assertEquals("Первый вариант ответа про лес." to "Привет повторяюек.", r.closed)
        assertEquals(ConversationJournal.ROLE_SYSTEM to AnswerLadder.THREE_VARIANTS_ASK, e.calls[2].messages.last())
        assertTrue(e.calls[2].dry != null)
    }

    @Test
    fun `годного нет ни в одном — канал решает, что значит «скрыть», первый ответ ему отдан`() {
        val e = FakeEngine(Reply("Привет повторяюек."), Reply("Привет порчаслово."), Reply("Кривойслов раз.\nКривойслов два."))
        val r = FakeRibbon()
        val res = run(e, r)
        assertTrue(res.hidden)
        assertNull(r.closed)
        assertEquals("Привет повторяюек.", r.hidden!!.second)
    }

    @Test
    fun `повтор прошлого ответа — вторая попытка без хода-образца и без снимка`() {
        val past = "Я люблю гулять по лесу и собирать грибы осенью."
        val turns = listOf(ConversationJournal.Turn(userContent = "Что любишь?", agentContent = past, question = "Что любишь?"))
        val e = FakeEngine(Reply(past), Reply("Спасибо, у меня всё хорошо."))
        val r = FakeRibbon(turns)
        val res = run(e, r, question = "Как дела?")
        val cause = res.intercept?.cause
        assertTrue(cause is AnswerLadder.Cause.Repeat)
        assertEquals(setOf(0), (cause as AnswerLadder.Cause.Repeat).without)
        assertTrue("(без [0])" in e.calls[1].messages[0].second)
        assertNull(e.calls[1].stable)
        assertEquals("Спасибо, у меня всё хорошо." to past, r.closed)
    }

    @Test
    fun `копия записи своей речи — вторая попытка без этой записи, ходы и снимок на месте`() {
        val own = "Я говорил в прошлом разговоре, на днях: «Я люблю гулять по лесу и собирать грибы осенью.»."
        val e = FakeEngine(Reply("Я люблю гулять по лесу и собирать грибы осенью."), Reply("Спасибо, у меня всё хорошо."))
        val r = FakeRibbon(own = listOf(own))
        val res = run(e, r, question = "Как дела?")
        val cause = res.intercept?.cause as AnswerLadder.Cause.Repeat
        assertEquals(emptySet<Int>(), cause.without)
        assertEquals(setOf(own), cause.withoutRecall)
        assertTrue("(без записей 1)" in e.calls[1].messages[0].second)
        assertEquals(0, e.calls[1].stable)
        assertEquals("Спасибо, у меня всё хорошо." to "Я люблю гулять по лесу и собирать грибы осенью.", r.closed)
    }

    /** Проверка на молчание: ответ по делу рядом с записью своей речи не перехватывается. */
    @Test
    fun `запись своей речи без копии — перехвата нет`() {
        val own = "Я говорил в прошлом разговоре, на днях: «Я люблю гулять по лесу и собирать грибы осенью.»."
        val e = FakeEngine(Reply("Спасибо, у меня всё хорошо."))
        val res = run(e, FakeRibbon(own = listOf(own)), question = "Как дела?")
        assertNull(res.intercept)
        assertEquals(1, e.calls.size)
    }

    @Test
    fun `оборванный первый ответ не меряется и ложится как есть`() {
        val e = FakeEngine(Reply("Привет повторяюек", end = GenerationEnd.WATCHDOG_CRITICAL))
        val r = FakeRibbon()
        val res = run(e, r)
        assertNull(res.intercept)
        assertEquals(GenerationEnd.WATCHDOG_CRITICAL, res.generationEnd)
        assertEquals(1, e.calls.size)
        assertEquals("Привет повторяюек" to null, r.closed)
    }

    @Test
    fun `остановка перед второй попыткой — ход ложится первым, второй попытки нет`() {
        val e = FakeEngine(Reply("Привет, всё повторяюек хорошо."))
        val r = FakeRibbon()
        val res = run(e, r, halt = { "аварийный стоп" })
        assertNull(res.intercept)
        assertEquals("аварийный стоп", res.haltedBy)
        assertEquals("Привет, всё повторяюек хорошо." to null, r.closed)
        assertEquals(1, e.calls.size)
    }

    @Test
    fun `детектор не загружен — порча не ловится, ход ложится как есть`() {
        val e = FakeEngine(Reply("Привет повторяюек."))
        val r = FakeRibbon()
        val res = run(e, r, damage = null)
        assertNull(res.intercept)
        assertEquals("Привет повторяюек." to null, r.closed)
    }

    @Test
    fun `ноль знаков — ход не ложится`() {
        val e = FakeEngine(Reply(""))
        val r = FakeRibbon()
        val res = run(e, r)
        assertEquals(0, res.tokensSeen)
        assertNull(r.closed)
        assertNull(r.hidden)
    }

    @Test
    fun `петля до потолка — правило с потолком канала`() {
        val looped = (1..6).joinToString(" ") { "Я люблю гулять по лесу и собирать грибы осенью." }
        assertTrue(AnswerLadder.loopAtCeiling(looped, 300, 300) != null)
        assertNull(AnswerLadder.loopAtCeiling(looped, 299, 300))
    }
}
