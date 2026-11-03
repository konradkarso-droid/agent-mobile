package com.uroboros.memory.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Просьба вспомнить (RecallRequest): как узнаётся, что даёт план. Реплики —
 * настоящие реплики владельца из архива лент, кроме помеченных. Словарь —
 * настоящий файл приложения (TestVerbs.lemmas).
 */
class RecallRequestTest {

    @Before
    fun verbs() = TestVerbs.install()

    private fun plan(query: String, recent: List<String> = emptyList()) =
        RecallRequest.plan(query, recent, TestVerbs.lemmas)

    // --- Узнаёт ---

    @Test
    fun `вопрос с «помнишь» — просьба`() {
        for (q in listOf(
            "Что ты помнишь про полотенце?",
            "Хмм. Совсем ничего не помнишь?",
            "Помнишь что-нибудь из наших прошлых разговоров?",
            "Работаю над твоей памятью, точками роста. Кстати, помнишь, что было неделю назад?",
            "А сейчас не вспомнишь?",
        )) assertNotNull(q, RecallRequest.of(q))
    }

    @Test
    fun `«помнишь» после вопросительного слова — просьба и без знака вопроса`() {
        assertEquals("помнишь", RecallRequest.of("А что помнишь о желтом, расскажи")?.word)
        assertEquals("помнишь", RecallRequest.of("Расскажи, что ты помнишь")?.word)
        assertEquals("помнишь", RecallRequest.of("Мне интересно, что ты помнишь из этих разговоров.")?.word)
    }

    @Test
    fun `«вспомни» и «вспомнить» — просьба и без вопроса`() {
        assertEquals("вспомнить", RecallRequest.of("Попробуй вспомнить")?.word)
        assertEquals("вспомни", RecallRequest.of("Вспомни, что я говорил про рубанок.")?.word) // не из архива
    }

    @Test
    fun `слова о прошлом — просьба только в вопросе`() {
        assertEquals("в прошлый раз", RecallRequest.of("А что снилось в прошлый раз?")?.word)
        assertEquals("давно", RecallRequest.of("Как давно был наш первый разговор?")?.word)
        assertNull(RecallRequest.of("Это было в прошлый раз. Может теперь у тебя по-другому."))
    }

    // --- Молчит ---

    @Test
    fun `утверждения с «помнишь» — не просьба`() {
        for (q in listOf(
            "Ты помнишь неправильно, ведь мнемоническое правило должно соответствовать первыми буквами слов",
            "Ты в телефоне живешь. Можешь смотреть на приборы. Помнишь что было и видишь сны.",
            "Вспомнишь в процессе, - я понял.",
            "Меня зовут Иван. Ты должен это помнить, я говорил.",
            "Хорошо, что помнишь сны.",
            "Дай-ка вспомнить. Около четырех.",
            "Да, что-то у тебя с воспоминаниями не очень. В разговоре всë неплохо, а потом не можешь вспомнить.",
        )) assertNull(q, RecallRequest.of(q))
    }

    @Test
    fun `«раньше» не о прошлом разговоре — не просьба`() {
        assertNull(RecallRequest.of("Чем раньше, тем лучше?")) // не из архива
        assertNull(RecallRequest.of("Не кончится ли заряд раньше времени?")) // не из архива
        assertNull(RecallRequest.of("Как дела? Что нового?")) // не из архива
    }

    // --- План ---

    @Test
    fun `просьба с якорем — якоря разговора не берутся, переспроса нет`() {
        val p = plan("Что ты помнишь про полотенце?", recent = listOf("Расскажи про Praxis"))!!
        assertTrue(p.contextAnchors.isEmpty())
        assertFalse(p.askBack)
    }

    @Test
    fun `просьба без якоря — якоря из последних реплик разговора, новые первыми`() {
        val p = plan(
            "Помнишь что-нибудь?",
            recent = listOf("Расскажи про радугу", "А Клод что говорил про Praxis?"),
        )!!
        assertEquals(listOf("клод", "praxis", "радугу"), p.contextAnchors.map { it.word })
        assertFalse(p.askBack)
    }

    @Test
    fun `просьба без якоря со словом поиска — якоря разговора есть, переспроса нет и без них`() {
        val withContext = plan("А что помнишь о желтом, расскажи", recent = listOf("Какие цвета у радуги?"))!!
        assertEquals(listOf("цвета", "радуги"), withContext.contextAnchors.map { it.word })
        val alone = plan("А что помнишь о желтом, расскажи")!!
        assertTrue(alone.contextAnchors.isEmpty())
        assertFalse("«желтом» — слово поиска", alone.askBack)
    }

    @Test
    fun `ни якоря, ни слова, ни разговора — переспрос`() {
        for (q in listOf("Помнишь что-нибудь?", "Расскажи, что ты помнишь", "Попробуй вспомнить")) {
            assertTrue(q, plan(q)!!.askBack)
        }
        assertNull("не просьба — плана нет", plan("Да, давай"))
    }

    @Test
    fun `просьба о самом владельце — без переспроса`() {
        val q = "Помнишь меня?"
        val address = Coordinates.questionAddress(q)
        assertFalse(RecallRequest.plan(q, emptyList(), TestVerbs.lemmas, address)!!.askBack)
        assertTrue("без адреса — переспрос", RecallRequest.plan(q, emptyList(), TestVerbs.lemmas)!!.askBack)
    }

    @Test
    fun `слова просьбы не ищутся, тема — ищется`() {
        assertEquals(listOf("полотенце"), RecallRequest.searchWords("Что ты помнишь про полотенце?"))
        assertTrue(RecallRequest.searchWords("Помнишь что-нибудь из наших прошлых разговоров?").isEmpty())
    }

    @Test
    fun `текст для своей речи — без слов просьбы, с якорями разговора`() {
        val p = plan("Помнишь?", recent = listOf("Расскажи про Praxis"))!!
        val text = RecallRequest.topicText("Помнишь?", p)
        assertFalse(text, text.contains("Помнишь"))
        assertTrue(text, text.contains("praxis"))
        assertEquals("без просьбы — реплика как есть", "Помнишь?", RecallRequest.topicText("Помнишь?", null))
    }

    // --- Без просьбы — сам ---

    @Test
    fun `сам — только без просьбы, с якорем, когда горячее пусто, а холод нет`() {
        assertTrue(RecallRequest.recallsItself(null, anchors = 1, hotAnchorFound = 0, coldAnchorFound = 2))
        assertFalse("горячее нашло", RecallRequest.recallsItself(null, 1, 1, 2))
        assertFalse("холод пуст", RecallRequest.recallsItself(null, 1, 0, 0))
        assertFalse("якорей нет", RecallRequest.recallsItself(null, 0, 0, 2))
        assertFalse("есть просьба — это не «сам»", RecallRequest.recallsItself(plan("Что ты помнишь про полотенце?"), 1, 0, 2))
    }

    // --- Строка прибора ---

    @Test
    fun `строка прибора различает «нет», «переспрос» и «якоря из разговора»`() {
        assertEquals("просьба вспомнить: нет", RecallRequest.meterPart(null))
        assertTrue(RecallRequest.meterPart(plan("Помнишь что-нибудь?")).contains("переспросить"))
        val line = RecallRequest.meterPart(plan("Помнишь что-нибудь?", listOf("Расскажи про Praxis")), 2, 1)
        assertEquals(
            "просьба вспомнить: «помнишь» · холод и своя речь — до 3 мест · " +
                "якоря из разговора (praxis) — в холоде нашли 2, мест 1",
            line,
        )
    }
}
