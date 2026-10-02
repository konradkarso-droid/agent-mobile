package com.uroboros.memory.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Случаи взяты из живой ленты (рубанок, Админ, «обо мне», «строить и
 * дебажить»). Закреплены обе стороны: где пробел есть и где его нет.
 */
class GapsTest {

    private fun t(question: String, answer: String, conversation: Int = 1) =
        Gaps.Turn(question, answer, conversation)

    // --- Находит ---

    @Test
    fun `признание и предмет — общее у реплики и фразы «не знаю»`() {
        val gaps = Gaps.of(listOf(t(
            "Из какого дерева сделана колодка моего рубанка?",
            "Без дополнительной информации я не могу сказать, из какого дерева колодка рубанка.",
        )))
        assertEquals(1, gaps.size)
        assertTrue(gaps[0].subject.isNotEmpty())
        assertEquals(Gaps.State.OPEN, gaps[0].state)
    }

    @Test
    fun `вопрос о собеседнике — пробел о нём без основ`() {
        val gaps = Gaps.of(listOf(t("Что знаешь обо мне?", "В моих записях нет информации о вас.")))
        assertEquals(1, gaps.size)
        assertTrue(gaps[0].aboutOwner)
        assertTrue(gaps[0].subject.isEmpty())
    }

    @Test
    fun `закрывает утверждение владельца, покрывающее предмет`() {
        val gaps = Gaps.of(listOf(
            t("Из какого дерева сделана колодка моего рубанка?", "Не могу сказать, из какого дерева колодка рубанка."),
            t("Колодка рубанка не деревянная.", "Понял."),
        ))
        assertEquals(Gaps.State.CLOSED, gaps.single().state)
        assertEquals("Колодка рубанка не деревянная.", gaps.single().closedBy)
    }

    @Test
    fun `вопрос владельца пробел не закрывает`() {
        val gaps = Gaps.of(listOf(
            t("Кто Админ?", "У меня нет информации об Админе."),
            t("А Админ тебе нужен?", "Не знаю."),
        ))
        assertEquals(Gaps.State.OPEN, gaps.first().state)
    }

    @Test
    fun `признание после закрытия — не дошло, а не новый пробел`() {
        val gaps = Gaps.of(listOf(
            t("Кто Админ?", "У меня нет информации об Админе.", conversation = 26),
            t("Я - Админ", "Понятно.", conversation = 26),
            t("Что ты думаешь об Админе?", "Во мне нет информации об Админе.", conversation = 75),
        ))
        val g = gaps.single()
        assertEquals(Gaps.State.CLOSED, g.state)
        assertEquals(listOf("Что ты думаешь об Админе?"), g.missed)
        assertEquals(setOf(26, 75), g.conversations)
    }

    @Test
    fun `спрошен и в том же ответе`() {
        val gaps = Gaps.of(listOf(t("Кто Админ?", "Я не знаю, кто такой Админ. Расскажешь, кто такой Админ?")))
        assertEquals(Gaps.State.ASKED, gaps.single().state)
    }

    // --- Молчит ---

    @Test
    fun `без признания пробела нет`() =
        assertTrue(Gaps.of(listOf(t("Кто Админ?", "Админ — это ты."))).isEmpty())

    @Test
    fun `общего предмета нет — молчим`() {
        // «…как я себя чувствовал, не могу сказать» — пробел о себе, а не о
        // «строить и дебажить»; общих основ нет, кроме слова-связки.
        val gaps = Gaps.of(listOf(t(
            "Когда-нибудь я закончу строить и дебажить, но только не сегодня. Ты как?",
            "Как я себя чувствовал сегодня, не могу сказать точно.",
        )))
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `«не знаю, чем помочь» на благодарность — молчим`() =
        assertTrue(Gaps.of(listOf(t("Спасибо, всё.", "Не знаю, чем ещё помочь."))).isEmpty())

    @Test
    fun `ход агента без реплики владельца признанием не считается`() =
        assertTrue(Gaps.of(listOf(t("", "Не знаю, что было во сне."))).isEmpty())

    @Test
    fun `фраза признания находится, а обычная — нет`() {
        assertEquals("Этого я не помню.", Gaps.admission("Хороший вопрос. Этого я не помню."))
        assertNull(Gaps.admission("Колодка из бука."))
    }

    // --- Прибор ---

    @Test
    fun `прибор при пустом`() =
        assertEquals("Пробелы: признанных «не знаю» нет", Gaps.meterLine(emptyList(), "Как дела?"))

    @Test
    fun `прибор — счёт, последний открытый, тема, не дошло`() {
        val gaps = Gaps.of(listOf(
            t("Кто Админ?", "У меня нет информации об Админе."),
            t("Я - Админ", "Понятно."),
            t("Что ты думаешь об Админе?", "Во мне нет информации об Админе."),
            t("Что знаешь обо мне?", "В моих записях нет информации о вас."),
        ))
        assertEquals(
            "Пробелы: открыто 1, спрошено 0, закрыто 1 · последний открытый: «Что знаешь обо мне?» (о собеседнике)" +
                " · к этому ходу: не по теме · не дошло: «Что ты думаешь об Админе?» — ответ был «Я - Админ»",
            Gaps.meterLine(gaps, "Как дела?"),
        )
    }

    @Test
    fun `открытый пробел по теме хода`() {
        val gaps = Gaps.of(listOf(t(
            "Из какого дерева сделана колодка моего рубанка?",
            "Не могу сказать, из какого дерева колодка рубанка.",
        )))
        assertTrue(Gaps.meterLine(gaps, "Колодку рубанка я сам сделал.").contains("к этому ходу: по теме 1"))
        assertFalse(Gaps.meterLine(gaps, "Как дела?").contains("по теме 1"))
    }
}
