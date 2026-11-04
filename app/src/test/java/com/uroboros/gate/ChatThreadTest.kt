package com.uroboros.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatThreadTest {

    private val minute = 60_000L
    private val t0 = 1_000_000_000_000L

    private fun peer(text: String, sig: String, at: Long, reads: Boolean = true) = ChatFeed.Line(false, sig, text, reads, at = at)
    private fun agent(text: String, at: Long) = ChatFeed.Line(true, "", text, true, at = at)
    private fun general(text: String, sig: String, at: Long) = peer(text, sig, at, reads = false)

    private val clock: (Long) -> String = { "ЧЧ:${(it - t0) / minute}" }

    // --- Голова ---

    @Test
    fun `голова — кто писал, сколько я ответил, о чём`() {
        val before = listOf(
            peer("Поедем в отпуск на море", "Петя", t0),
            agent("Хорошая мысль.", t0 + minute),
            peer("Билеты на море дорогие", "Аня", t0 + 2 * minute),
            peer("Море того стоит", "Петя", t0 + 3 * minute),
        )
        val h = ChatThread.head(before, t0 + 4 * minute, null)
        assertNotNull(h)
        h!!
        assertEquals(t0, h.since)
        assertEquals(listOf("Петя", "Аня"), h.voices.names)
        assertEquals(1, h.agentAnswers)
        assertEquals("слово двух ходов — первым", "море", h.words.first())
        val text = ChatThread.headText(h, clock)
        assertTrue(text, text.startsWith("Раньше в этом разговоре, выше реплик, что я вижу (с ЧЧ:0): писали Петя и Аня; я ответил 1 раз."))
        assertTrue(text, text.contains("Слова, что звучали чаще: море"))
    }

    @Test
    fun `голова — только этот разговор, за часом тишины прежнего нет`() {
        val before = listOf(
            peer("Вчерашняя тема про рыбалку", "Петя", t0),
            peer("Сегодня про погоду", "Аня", t0 + 120 * minute),
        )
        val h = ChatThread.head(before, t0 + 121 * minute, null)!!
        assertEquals(listOf("Аня"), h.voices.names)
        assertFalse(h.words.any { it.startsWith("рыбалк") })
        assertEquals(0, h.agentAnswers)
        assertFalse(ChatThread.headText(h, clock).contains("я ответил"))
    }

    @Test
    fun `головы нет — перед окном пусто или всё из прошлого разговора`() {
        assertNull(ChatThread.head(emptyList(), t0, null))
        assertNull(ChatThread.head(listOf(peer("давно", "Петя", t0)), t0 + 90 * minute, null))
        assertNull("одни свои ответы — людей нет", ChatThread.head(listOf(agent("я", t0)), t0 + minute, null))
    }

    @Test
    fun `слова агента в тему не идут`() {
        val before = listOf(peer("Расскажи что-нибудь", "Петя", t0), agent("Вулканы извергаются лавой", t0 + minute))
        val h = ChatThread.head(before, t0 + 2 * minute, null)!!
        assertFalse(h.words.any { it.startsWith("вулкан") || it.startsWith("лав") })
    }

    @Test
    fun `тема — всё ушедшее, а не последний ход`() {
        // Поход звучал в двух ходах из трёх, погода — только в последнем.
        val before = listOf(
            peer("Собираемся в поход", "Петя", t0),
            agent("Куда?", t0 + minute),
            peer("Поход на озеро", "Аня", t0 + 2 * minute),
            agent("Хорошо.", t0 + 3 * minute),
            peer("Какая будет погода", "Петя", t0 + 4 * minute),
        )
        val h = ChatThread.head(before, t0 + 5 * minute, null)!!
        assertEquals("поход", h.words.first())
        assertEquals("при равенстве — что раньше: начало разговора не видно ничем, кроме головы", "собираемся", h.words[1])
        val t = ChatThread.tail(listOf(general("про кота", "Аня", t0), general("про погоду", "Вика", t0 + minute), peer("Агент?", "Петя", t0 + 2 * minute)), null)!!
        assertEquals("у хвоста при равенстве — что позже", "погоду", t.words.first())
    }

    @Test
    fun `имена пишущих и агента — не тема`() {
        val lines = listOf(
            general("Вика, поехали на озеро", "Аня", t0),
            general("Алекс знает дорогу", "Вика", t0 + minute),
            peer("Агент?", "Петя", t0 + 2 * minute),
        )
        val t = ChatThread.tail(lines, null, agentNames = listOf("Алекс"))!!
        assertFalse(t.words.toString(), t.words.any { it == "вика" || it == "алекс" })
        assertTrue(t.words.contains("озеро"))
    }

    // --- Хвост ---

    @Test
    fun `хвост — общее группы после моего последнего ответа`() {
        val lines = listOf(
            general("старое общее до ответа", "Вика", t0),
            peer("Агент, привет", "Петя", t0 + minute),
            agent("Привет!", t0 + 2 * minute),
            general("У кота температура", "Аня", t0 + 3 * minute),
            general("Вези кота к ветеринару", "Вика", t0 + 4 * minute),
            general("Кот уже лучше", "Аня", t0 + 5 * minute),
            peer("Агент, а ты что думаешь?", "Петя", t0 + 6 * minute),
        )
        val t = ChatThread.tail(lines, null)!!
        assertEquals(3, t.lines)
        assertTrue(t.sinceMine)
        assertEquals(listOf("Аня", "Вика"), t.voices.names)
        assertEquals("ещё 3 сообщения", ChatThread.tailText(t).substringAfter("ответа ").substringBefore(" не мне"))
        assertFalse(t.words.any { it.startsWith("стар") })
    }

    @Test
    fun `хвост — с начала разговора, если я в нём не отвечал`() {
        val lines = listOf(general("обсуждаем поход", "Аня", t0), peer("Агент, ты с нами?", "Петя", t0 + minute))
        val t = ChatThread.tail(lines, null)!!
        assertFalse(t.sinceMine)
        assertTrue(ChatThread.tailText(t).startsWith("В группе в этом разговоре ещё 1 сообщение не мне: писали Аня."))
    }

    @Test
    fun `хвоста нет — в личке всё читается, общее было до ответа, прошлый разговор`() {
        val direct = listOf(peer("Привет", "Петя", t0), agent("Привет!", t0 + minute), peer("Как дела?", "Петя", t0 + 2 * minute))
        assertNull(ChatThread.tail(direct, null))
        val before = listOf(general("болтовня", "Аня", t0), agent("ответ", t0 + minute), peer("вопрос", "Петя", t0 + 2 * minute))
        assertNull(ChatThread.tail(before, null))
        val old = listOf(general("вчерашнее", "Аня", t0), peer("сегодня вопрос", "Петя", t0 + 90 * minute))
        assertNull(ChatThread.tail(old, null))
        assertNull("одна текущая строка", ChatThread.tail(listOf(peer("вопрос", "Петя", t0)), null))
    }

    // --- Слова ---

    @Test
    fun `числа и имена — по-русски`() {
        val voices = ChatThread.Voices(listOf("Петя", "Аня", "Вика"), 2)
        val t = ChatThread.Tail(5, voices, emptyList(), true)
        assertEquals("В группе после моего последнего ответа ещё 5 сообщений не мне: писали Петя, Аня, Вика и ещё 2.", ChatThread.tailText(t))
        assertTrue(ChatThread.tailText(t.copy(lines = 21)).contains("ещё 21 сообщение"))
        assertTrue(ChatThread.tailText(t.copy(lines = 12)).contains("ещё 12 сообщений"))
        val h = ChatThread.Head(t0, ChatThread.Voices(listOf("Петя"), 0), 3, emptyList())
        assertTrue(ChatThread.headText(h, clock).contains("писали Петя; я ответил 3 раза."))
        assertTrue(ChatThread.headText(h.copy(agentAnswers = 11), clock).contains("я ответил 11 раз."))
        assertFalse("слов нет — и фразы о словах нет", ChatThread.headText(h, clock).contains("звучали"))
    }

    @Test
    fun `имён больше трёх — первые по числу строк`() {
        val lines = listOf("Аня", "Петя", "Петя", "Вика", "Гоша", "Гоша", "Гоша").mapIndexed { i, n ->
            general("сообщение номер $i", n, t0 + i * minute)
        } + peer("Агент?", "Петя", t0 + 10 * minute)
        val t = ChatThread.tail(lines, null)!!
        assertEquals(listOf("Гоша", "Петя", "Аня"), t.voices.names)
        assertEquals(1, t.voices.more)
    }

    @Test
    fun `настойчивость — счёт сообщений мне за десять минут, меньше трёх — молчит`() {
        fun from(who: String, at: Long, reads: Boolean = true) = ChatFeed.Line(false, who, "ну ответь", reads, at = at, sender = "k:$who")
        val five = (0 until 5).map { from("Петя", t0 + it * minute) }
        assertEquals("От «Петя» мне за 10 минут 5 сообщений.", ChatThread.insistence(five))
        assertNull(ChatThread.insistence(five.takeLast(2)))
        val old = listOf(from("Петя", t0), from("Петя", t0 + 30 * minute), from("Петя", t0 + 31 * minute))
        assertNull("старое за окном не считается", ChatThread.insistence(old))
        val others = listOf(from("Аня", t0), from("Аня", t0 + minute), from("Петя", t0 + 2 * minute))
        assertNull("чужие сообщения не его", ChatThread.insistence(others))
        val general = listOf(from("Петя", t0, reads = false), from("Петя", t0 + minute, reads = false), from("Петя", t0 + 2 * minute))
        assertNull("общее группы — не мне", ChatThread.insistence(general))
    }

    // --- Прибор ---

    @Test
    fun `строка прибора называет и пустую нить`() {
        val w = ChatFeed.Window(listOf(peer("вопрос", "Петя", t0)), 0, 15)
        val line = ChatThread.meterLine("Петя", w, null, null, null, dictionary = false, clock = clock)
        assertEquals("Нить (Петя): окно 15 из 2400 зн., встало · раньше: нет — в этом разговоре всё в окне · " +
            "фон группы: нет · словаря нет — слова как написаны", line)
        assertTrue(ChatThread.meterLine("Петя", w, false, null, null, true, clock).contains("на месте"))
        assertTrue(ChatThread.meterLine("Петя", w, true, null, null, true, clock).contains("сдвинулось скачком"))
    }
}
