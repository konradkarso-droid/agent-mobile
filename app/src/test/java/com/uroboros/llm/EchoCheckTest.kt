package com.uroboros.llm

import com.uroboros.memory.STOP_WORDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Детекторы эха [EchoCheck]; пометок о нём в реплике следующего хода нет.
 *
 * Живые примеры — с телефона. Главные проверки на молчание: ответ по делу с
 * парой общих слов с вопросом — не зеркало; предложение из одной основы не
 * учитывается; ответ, совпадающий с вопросом или записью, — не ответ
 * служебной строке. Детектор, ловящий всё подряд, в проверках «ловит»
 * выглядел бы правильным.
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
    fun `строка Эхо печатается всегда и разводит четыре вида`() {
        assertEquals("Эхо: ответа ещё нет", EchoCheck.meter(null))
        assertEquals(
            "Эхо: повтор себя — нет · зеркало — «Нет, сейчас не вспомнишь.» · ответ служебной строке — нет" +
                " · петля в ответе — нет",
            EchoCheck.meter(EchoCheck.Result(selfRepeat = null, mirror = "Нет, сейчас не вспомнишь.")),
        )
        assertEquals(
            "Эхо: повтор себя — нет · зеркало — нет · ответ служебной строке — нет · петля в ответе — нет",
            EchoCheck.meter(EchoCheck.Result(null, null)),
        )
    }

    // --- Петля в ответе ---

    /**
     * Живой пример с телефона, укорочен: ответ на «Что знаешь обо мне?» —
     * перечисление записей подписями пошло по второму кругу. Время у записи в
     * круге другое («с неделю назад» → «пару недель назад»), слова те же.
     */
    @Test
    fun `петля ловится на живом примере`() {
        val answer = "Твои слова в прошлом разговоре, пару недель назад: «Ты работаешь по субботам.». " +
            "Твои слова в прошлом разговоре, пару недель назад: «Твой любимый инструмент — рубанок с железной колодкой.». " +
            "Твои слова в прошлом разговоре, с неделю назад: «Ты был у стоматолога, сейчас вот код сочиняешь, пишешь.». " +
            "Твои слова в прошлом разговоре, с неделю назад: «Твой любимый инструмент — рубанок с железной колодкой.»."
        val result = EchoCheck.check(answer, "Что знаешь обо мне?", emptyList())
        assertTrue(result.loop!!.contains("рубанок"))
        assertNull(result.selfRepeat)
        assertEquals(
            "Эхо: повтор себя — нет · зеркало — нет · ответ служебной строке — нет · петля в ответе — с предложения " +
                "${result.loopAt} «${result.loop}»",
            EchoCheck.meter(result),
        )
    }

    /**
     * Молчание: перечисление РАЗНЫХ записей одной подписью — не петля. Живой
     * пример со стенда: при доле 0.8 вторая строка ловилась — подпись делит с
     * первой почти все основы.
     */
    @Test
    fun `разные записи под одной подписью — не петля`() {
        val answer = "С твоих слов в прошлом разговоре, на днях: ты можешь научить думать. " +
            "С твоих слов в прошлом разговоре, на днях: ты это говорил. " +
            "С твоих слов в прошлом разговоре, пару недель назад: ты работаешь по субботам."
        assertNull(EchoCheck.check(answer, "Что знаешь обо мне?", emptyList()).loop)
    }

    /** Молчание: короткий ответ по делу и повтор из одной основы. */
    @Test
    fun `ответ без повтора и повтор одной основой — не петля`() {
        assertNull(EchoCheck.check("Температура телефона 28.5°C, уровень заряда 53%.", "", emptyList()).loop)
        assertNull(EchoCheck.check("Помню. Помню.", "", emptyList()).loop)
    }

    /** Петля — свой счёт: в [EchoCheck.Result.any] она не входит. */
    @Test
    fun `петля не входит в общий признак эха`() {
        val result = EchoCheck.check(
            "Ты работаешь по субботам и любишь рубанок. Ты работаешь по субботам и любишь рубанок.", "", emptyList(),
        )
        assertEquals(2, result.loopAt)
        assertFalse(result.any)
    }

    // --- Ответ служебной строке ---

    /**
     * Живой пример: пометка об эхе стояла в реплике, модель ответила на неё.
     * Доля основ 3 из 4 («понял» в блоке нет) — порог зеркала 0.8 её пропустил бы.
     */
    @Test
    fun `ответ на служебную строку ловится на живом примере`() {
        val service = "В прошлом ответе ты повторил слова пользователя вместо своих."
        val last = ConversationJournal.Turn(
            userContent = service + "\n\nХорошо.",
            agentContent = "Понял, повторил слова пользователя.",
            question = "Хорошо.",
        )
        val result = EchoCheck.ofLast(listOf(last))!!
        assertEquals("Понял, повторил слова пользователя.", result.serviceReply)
        assertEquals(service, result.serviceBlock)
        assertNull(result.mirror)
        assertEquals(
            "Эхо: повтор себя — нет · зеркало — нет · ответ служебной строке — " +
                "«Понял, повторил слова пользователя.» → «В прошлом ответе ты повторил слова польз…»" +
                " · петля в ответе — нет",
            EchoCheck.meter(result),
        )
    }

    /** Совпадение с вопросом — это зеркало, а не ответ служебной строке. */
    @Test
    fun `ответ, совпадающий только с вопросом, — не ответ служебной строке`() {
        val last = ConversationJournal.Turn(
            userContent = ConversationJournal.ANSWER_WITHOUT_SUPPORT + "\n\nА сейчас не вспомнишь?",
            agentContent = "Нет, сейчас не вспомнишь.",
            question = "А сейчас не вспомнишь?",
        )
        val result = EchoCheck.ofLast(listOf(last))!!
        assertEquals("Нет, сейчас не вспомнишь.", result.mirror)
        assertNull(result.serviceReply)
        assertNull(result.serviceBlock)
    }

    /**
     * Пересказ записи — законный ответ. Запись нарочно с пустой строкой внутри
     * (сохранённая речь владельца бывает в несколько абзацев): деление реплики
     * по пустой строке до выемки записей выдало бы её хвост за служебный блок.
     */
    @Test
    fun `ответ, совпадающий только с записью, — не ответ служебной строке`() {
        val record = "Пользователь сказал: «Колодка из бука.\n\nРубанок старый.»"
        val question = "Какой у меня рубанок?"
        val last = ConversationJournal.Turn(
            userContent = ConversationJournal.ANSWER_WITHOUT_SUPPORT + "\n\n" + record + "\n\n" + question,
            agentContent = "Рубанок старый.",
            question = question,
            records = listOf(ConversationJournal.RecordUse(record, 0)),
        )
        assertEquals(listOf(ConversationJournal.ANSWER_WITHOUT_SUPPORT), EchoCheck.serviceBlocks(last))
        assertNull(EchoCheck.ofLast(listOf(last))!!.serviceReply)
    }

    @Test
    fun `ход без служебных блоков — нет`() {
        val last = ConversationJournal.Turn(
            userContent = "Из чего колодка?",
            agentContent = "Колодка из бука.",
            question = "Из чего колодка?",
        )
        assertEquals(emptyList<String>(), EchoCheck.serviceBlocks(last))
        val result = EchoCheck.ofLast(listOf(last))!!
        assertNull(result.serviceReply)
        assertTrue(EchoCheck.meter(result).contains(" · ответ служебной строке — нет · "))
    }

    // --- Пометок об эхе в реплике нет ---

    private fun journalAfter(question: String, answer: String, earlier: String? = null) =
        ConversationJournal().apply {
            if (earlier != null) appendTurn("Привет", earlier, "Привет", listOf("запись"))
            appendTurn(question, answer, question, listOf("запись"))
        }

    /** Эхо поймано обоих видов, а в реплику следующего хода о нём не идёт ничего. */
    @Test
    fun `пойманное эхо в реплику не идёт`() {
        val journal = journalAfter(
            question = "А сейчас не вспомнишь?",
            answer = "Нет, сейчас не вспомнишь. Снова активирован, память проверить нужно.",
            earlier = "Снова активирован, память проверить нужно.",
        )
        val echo = EchoCheck.ofLast(journal.history())!!
        assertTrue(echo.mirror != null && echo.selfRepeat != null)
        assertEquals("Хорошо.", journal.composeUserContent("Хорошо."))
    }

    /** Пометка об опоре не тронута: после хода без записей она на месте, эха рядом нет. */
    @Test
    fun `пометка об опоре остаётся, пометки об эхе нет`() {
        val journal = ConversationJournal().apply {
            appendTurn("А сейчас не вспомнишь?", "Нет, сейчас не вспомнишь.", "А сейчас не вспомнишь?", emptyList())
        }
        assertEquals(
            ConversationJournal.ANSWER_WITHOUT_SUPPORT + "\n\nХорошо.",
            journal.composeUserContent("Хорошо."),
        )
    }
}
