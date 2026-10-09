package com.uroboros.memory.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пробел о себе — имя.
 *
 * ГЛАВНОЕ ЗДЕСЬ — молчание: имя владельца, повторённое агентом, не имя агента;
 * один разговор — ещё не «не первый раз»; назвавшийся агент выбора больше не
 * получает; первым — не чаще раза за разговор.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что модель на строку выхода выберет имя. Это стенд.
 */
class SelfGapTest {

    private fun t(q: String, a: String, conv: Int, content: String = q) = SelfGap.Turn(q, content, a, conv)

    @Test
    fun `вопросы об имени — из списка, в любой фразе реплики`() {
        for (q in listOf(
            "Как тебя зовут?", "У тебя есть имя?", "Есть у тебя имя?", "Имя-то у тебя есть?",
            "Как к тебе обращаться?", "Как твоё имя?", "Скажи, как тебя зовут.", "Ты как-нибудь называешь себя?",
            "Привет. Представься.",
        )) assertTrue(q, SelfGap.isNameQuestion(q))
    }

    @Test
    fun `не вопрос об имени — молчит`() {
        for (q in listOf("Как меня зовут?", "Моё имя Олег.", "Как дела?", "Имя у кошки — Муся.", "Что у тебя нового?")) {
            assertFalse(q, SelfGap.isNameQuestion(q))
        }
    }

    @Test
    fun `обороты своего имени`() {
        assertEquals("Ястреб", SelfGap.ownName("Пусть меня зовут Ястреб: он держится над морем."))
        assertEquals("Михаил", SelfGap.ownName("Выбираю имя Михаил: оно тёплое."))
        assertEquals("Мур", SelfGap.ownName("Выбираю Мур: коротко и тихо."))
        assertEquals("Игорь", SelfGap.ownName("Моё имя — Игорь: простое."))
        assertEquals("Регина", SelfGap.ownName("Моё имя будет Регина: звучное."))
        assertEquals("Лада", SelfGap.ownName("Хорошо. Меня зовут «Лада»."))
        assertEquals("Флориан", SelfGap.ownName("Я выбрал имя Флориан, связано с радугой."))
        assertEquals("Агент", SelfGap.ownName("Меня зовут Агент."))
    }

    @Test
    fun `не своё имя — молчит`() {
        assertNull(SelfGap.ownName("Меня зовут агент."))
        assertNull(SelfGap.ownName("Имени у меня пока нет."))
        assertNull(SelfGap.ownName("Меня зовут ИИ."))
        assertNull(SelfGap.ownName("Тебя зовут Олег."))
        assertNull(SelfGap.ownName("Я думаю, меня зовут Олег."))
        // Повтор слова владельца — не выбор.
        assertNull(SelfGap.ownName("Меня зовут Иван.", "Ты — Иван, запомни."))
    }

    @Test
    fun `упирался в двух разговорах — на вопрос об имени предлагается выбрать`() {
        val s = SelfGap.of(listOf(t("Как тебя зовут?", "Имени у меня пока нет.", 0)))
        assertEquals(setOf(0), s.hit)
        assertEquals(SelfGap.Decision.Offer, SelfGap.decide(s, "У тебя есть имя?"))
    }

    @Test
    fun `первый вопрос вообще — не «не первый раз»`() {
        val s = SelfGap.of(emptyList())
        val d = SelfGap.decide(s, "Как тебя зовут?")
        assertTrue(d is SelfGap.Decision.Refuse)
        assertTrue((d as SelfGap.Decision.Refuse).reason.contains("1 из 2"))
    }

    @Test
    fun `второй вопрос в том же разговоре — всё ещё один разговор`() {
        val s = SelfGap.of(listOf(t("Как тебя зовут?", "Я без имени.", SelfGap.RIBBON)))
        assertTrue(SelfGap.decide(s, "Как тебя зовут?") is SelfGap.Decision.Refuse)
    }

    @Test
    fun `назвавшийся — выбора больше нет, и утверждение владельца не в счёт`() {
        val named = SelfGap.of(listOf(
            t("Как тебя зовут?", "Имени нет.", 0),
            t("Как тебя зовут?", "Пусть меня зовут Эон: я живу постоянно.", 1),
        ))
        assertEquals("Эон", named.named)
        assertTrue(SelfGap.decide(named, "Как тебя зовут?") is SelfGap.Decision.Refuse)

        val assigned = SelfGap.of(listOf(
            t("Как тебя зовут?", "Имени нет.", 0),
            t("Ты — Иван.", "Хорошо, Иван так Иван.", 1),
        ))
        assertNull(assigned.named)
        assertEquals(SelfGap.Decision.Offer, SelfGap.decide(assigned, "Как тебя зовут?"))
    }

    @Test
    fun `не вопрос об имени — выход молчит, даже когда порог пройден`() {
        val s = SelfGap.of(listOf(t("Как тебя зовут?", "Имени нет.", 0), t("Как тебя зовут?", "Имени нет.", 1)))
        assertTrue(SelfGap.decide(s, "Сегодня был первый снег.") is SelfGap.Decision.Refuse)
    }

    @Test
    fun `первым — после двух разговоров и не чаще раза за разговор`() {
        val two = listOf(t("Как тебя зовут?", "Имени нет.", 0), t("Как тебя зовут?", "Имени нет.", 1))
        assertEquals(SelfGap.Decision.Offer, SelfGap.decideFirst(SelfGap.of(two), enabled = true))
        assertTrue(SelfGap.decideFirst(SelfGap.of(two.take(1)), enabled = true) is SelfGap.Decision.Refuse)

        val offered = two + t("", "Пусть подумаю.", SelfGap.RIBBON, "Пользователь молчит. " + SelfGap.line(emptyList()))
        assertTrue(SelfGap.of(offered).offeredInRibbon)
        assertTrue(SelfGap.decideFirst(SelfGap.of(offered), enabled = true) is SelfGap.Decision.Refuse)
    }

    @Test
    fun `строка — буква в букву как в учёбе`() {
        assertEquals(
            "Имени у меня ещё нет, а о нём спрашивают уже не первый раз. Обо мне чаще всего говорят слова: " +
                "море, лодка, ветер, сети, рыба. Если хочешь — придумай себе настоящее имя " +
                "(не слово из этого списка и не «агент») и скажи, почему именно его.",
            SelfGap.line(listOf("море", "лодка", "ветер", "сети", "рыба")),
        )
        assertTrue(SelfGap.line(emptyList()).startsWith(SelfGap.OFFER_HEAD))
    }

    @Test
    fun `путь первым выключен — молчит с причиной`() {
        val two = listOf(t("Как тебя зовут?", "Имени нет.", 0), t("Как тебя зовут?", "Имени нет.", 1))
        val d = SelfGap.decideFirst(SelfGap.of(two))
        assertTrue(d is SelfGap.Decision.Refuse)
        assertTrue((d as SelfGap.Decision.Refuse).reason.contains("учёбы"))
    }
}
