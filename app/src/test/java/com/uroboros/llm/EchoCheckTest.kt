package com.uroboros.llm

import com.uroboros.memory.STOP_WORDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Детекторы эха [EchoCheck] и пометки о нём в реплике следующего хода.
 *
 * Живые примеры — с телефона. Главные проверки на молчание: ответ по делу с
 * парой общих слов с вопросом — не зеркало; предложение из одной основы не
 * учитывается. Детектор, ловящий всё подряд, в проверках «ловит» выглядел бы
 * правильным.
 */
class EchoCheckTest {

    private fun turn(question: String, answer: String) = ConversationJournal.Turn(
        userContent = question,
        agentContent = answer,
        question = question,
        records = listOf(ConversationJournal.RecordUse("запись", 0)),
    )

    // --- Зеркало ---

    /** Живой пример; «сейчас» стоит в STOP_WORDS — мера детектора его не слепнет. */
    @Test
    fun `зеркало реплики ловится и после добавки сейчас в список служебных слов`() {
        assertTrue("сейчас" in STOP_WORDS)
        val result = EchoCheck.check("Нет, сейчас не вспомнишь.", "А сейчас не вспомнишь?", emptyList())
        assertEquals("Нет, сейчас не вспомнишь.", result.mirror)
        assertNull(result.selfRepeat)
    }

    @Test
    fun `ответ по делу с парой общих слов с вопросом — не зеркало`() {
        val result = EchoCheck.check(
            "Колодка сделана из бука, его древесина плотная и не трескается.",
            "Из какого дерева сделана колодка моего рубанка?",
            emptyList(),
        )
        assertNull(result.mirror)
        assertFalse(result.any)
    }

    @Test
    fun `предложение из одной основы не учитывается`() {
        val result = EchoCheck.check("Помню.", "Ты помнишь?", listOf("Помню."))
        assertNull(result.mirror)
        assertNull(result.selfRepeat)
    }

    /** Ход, начатый агентом: реплики не было, отражать нечего. */
    @Test
    fun `ход агента первым зеркала не имеет`() {
        val result = EchoCheck.check("Снова активирован, память проверить нужно.", "", emptyList())
        assertNull(result.mirror)
    }

    // --- Повтор себя ---

    /** Живой пример: одно и то же предложение в двух ответах подряд. */
    @Test
    fun `повтор себя ловится по предложению прошлого ответа`() {
        val result = EchoCheck.check(
            "Снова активирован, память проверить нужно. Спрашивай.",
            "Ты здесь?",
            listOf("Привет. Снова активирован, память проверить нужно."),
        )
        assertEquals("Снова активирован, память проверить нужно.", result.selfRepeat)
        assertNull(result.mirror)
    }

    /** Слова, рассыпанные по разным предложениям прошлых ответов, — не повтор фразы. */
    @Test
    fun `слова из разных предложений прошлых ответов — не повтор`() {
        val result = EchoCheck.check(
            "Рубанок строгает доску.",
            "Что делает рубанок?",
            listOf("Рубанок стоит в углу. Доска лежит на верстаке. Строгает мастер."),
        )
        assertNull(result.selfRepeat)
    }

    /** Сверяются только три прошлых ответа, считая от последнего хода. */
    @Test
    fun `повтор ищется только в трёх прошлых ответах`() {
        val phrase = "Снова активирован, память проверить нужно."
        val old = turn("Привет", phrase)
        val filler = (1..3).map { turn("Вопрос номер $it", "Ответ про погоду номер $it.") }
        val last = turn("Ты здесь?", phrase)
        assertNull(EchoCheck.ofLast(listOf(old) + filler + last)!!.selfRepeat)
        assertEquals(phrase, EchoCheck.ofLast(filler.drop(1) + old + last)!!.selfRepeat)
    }

    // --- Прибор ---

    @Test
    fun `строка Эхо печатается всегда и разводит два вида`() {
        assertEquals("Эхо: ответа ещё нет", EchoCheck.meter(null))
        assertEquals(
            "Эхо: повтор себя — нет · зеркало — «Нет, сейчас не вспомнишь.»",
            EchoCheck.meter(EchoCheck.Result(selfRepeat = null, mirror = "Нет, сейчас не вспомнишь.")),
        )
        assertEquals(
            "Эхо: повтор себя — нет · зеркало — нет",
            EchoCheck.meter(EchoCheck.Result(null, null)),
        )
    }

    // --- Пометки на следующем ходе ---

    private fun journalAfter(question: String, answer: String, earlier: String? = null) =
        ConversationJournal().apply {
            if (earlier != null) appendTurn("Привет", earlier, "Привет", listOf("запись"))
            appendTurn(question, answer, question, listOf("запись"))
        }

    @Test
    fun `обе пометки, без цитаты пойманного`() {
        val journal = journalAfter(
            question = "А сейчас не вспомнишь?",
            answer = "Нет, сейчас не вспомнишь. Снова активирован, память проверить нужно.",
            earlier = "Снова активирован, память проверить нужно.",
        )
        val content = journal.composeUserContent(emptyList(), "Хорошо.")
        assertEquals(
            ConversationJournal.ECHO_SELF_REPEAT + "\n\n" + ConversationJournal.ECHO_MIRROR + "\n\nХорошо.",
            content,
        )
        assertFalse(content.contains("вспомнишь"))
        assertFalse(content.contains("активирован"))
    }

    @Test
    fun `одна пометка — только зеркало`() {
        val journal = journalAfter("А сейчас не вспомнишь?", "Нет, сейчас не вспомнишь.")
        val content = journal.composeUserContent(emptyList(), "Хорошо.")
        assertEquals(ConversationJournal.ECHO_MIRROR + "\n\nХорошо.", content)
        assertFalse(content.contains("вспомнишь"))
    }

    @Test
    fun `ни одной пометки после ответа по делу`() {
        val journal = journalAfter(
            "Из какого дерева сделана колодка моего рубанка?",
            "Колодка сделана из бука, его древесина плотная и не трескается.",
        )
        assertEquals("Хорошо.", journal.composeUserContent(emptyList(), "Хорошо."))
    }

    /** Пометки об эхе стоят за пометкой об опоре: та по-прежнему первая. */
    @Test
    fun `пометка об опоре остаётся первой`() {
        val journal = ConversationJournal().apply {
            appendTurn("А сейчас не вспомнишь?", "Нет, сейчас не вспомнишь.", "А сейчас не вспомнишь?", emptyList())
        }
        val content = journal.composeUserContent(emptyList(), "Хорошо.")
        assertEquals(
            ConversationJournal.ANSWER_WITHOUT_SUPPORT + "\n\n" + ConversationJournal.ECHO_MIRROR + "\n\nХорошо.",
            content,
        )
    }
}
