package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замок на пометку «прошлый ответ без опоры» в [ConversationJournal].
 *
 * Зачем нужен именно тест, а не проверка руками. Пометка отвечает на
 * вопрос, была ли под прошлым ответом хоть одна запись памяти, и в ленту
 * она уходит навсегда. Ошибка здесь молчалива в обе стороны: пропущенная
 * пометка оставляет выдумку выглядеть подтверждённой, а лишняя — вешает
 * подозрение на честный ответ. Ни то, ни другое на экране не видно.
 *
 * Главная проверка — [пометки нет после хода с записями]. Механизм,
 * который лепит пометку в каждый запрос, во всех остальных случаях
 * выглядел бы правильным.
 *
 * Условие пометки — ПУСТОЙ список записей прошлого хода, а не отсутствие
 * новых: запись, легшая на первом ходе, на шестом не подставляется
 * повторно, но модели всё это время видна и опорой быть не перестаёт.
 */
class ConversationJournalTest {

    private val note = ConversationJournal.ANSWER_WITHOUT_SUPPORT

    @Test
    fun `на первом ходе пометки нет`() {
        val journal = ConversationJournal()

        val content = journal.composeUserContent(emptyList(), "Сколько звёзд на небе?")

        assertEquals("Сколько звёзд на небе?", content)
    }

    @Test
    fun `пометка появляется после хода без записей`() {
        val journal = ConversationJournal()
        journal.appendTurn(
            userContent = "Сколько звёзд на небе?",
            agentContent = "Около ста миллиардов.",
            question = "Сколько звёзд на небе?",
            records = emptyList(),
        )

        val content = journal.composeUserContent(emptyList(), "Где обедал воробей?")

        assertEquals(note + "\n\n" + "Где обедал воробей?", content)
    }

    @Test
    fun `пометки нет после хода с записями`() {
        val journal = ConversationJournal()
        journal.appendTurn(
            userContent = "Пользователь сказал: «Правило есть.»\n\nКакое правило?",
            agentContent = "Вот такое.",
            question = "Какое правило?",
            records = listOf("Пользователь сказал: «Правило есть.»"),
        )

        val content = journal.composeUserContent(emptyList(), "Где обедал воробей?")

        assertEquals("Где обедал воробей?", content)
    }

    @Test
    fun `пометка стоит перед записями, а не вместо них`() {
        val journal = ConversationJournal()
        journal.appendTurn(
            userContent = "Сколько звёзд на небе?",
            agentContent = "Около ста миллиардов.",
            question = "Сколько звёзд на небе?",
            records = emptyList(),
        )

        val content = journal.composeUserContent(
            listOf("Пользователь сказал: «Правило есть.»"),
            "Какое правило?",
        )

        assertEquals(
            note + "\n\n" +
                "Пользователь сказал: «Правило есть.»" + "\n\n" +
                "Какое правило?",
            content,
        )
    }

    @Test
    fun `пометка ставится один раз, а не по разу на каждый прошлый ход`() {
        val journal = ConversationJournal()
        repeat(3) { i ->
            journal.appendTurn(
                userContent = "вопрос $i",
                agentContent = "ответ $i",
                question = "вопрос $i",
                records = emptyList(),
            )
        }

        val content = journal.composeUserContent(emptyList(), "четвёртый вопрос")

        assertEquals(1, content.split(note).size - 1)
    }

    @Test
    fun `после опорного хода пометка снимается, хотя раньше в ленте она была`() {
        val journal = ConversationJournal()
        journal.appendTurn(
            userContent = "без опоры",
            agentContent = "выдумка",
            question = "без опоры",
            records = emptyList(),
        )
        journal.appendTurn(
            userContent = "с опорой",
            agentContent = "ответ по записи",
            question = "с опорой",
            records = listOf("Пользователь сказал: «Правило есть.»"),
        )

        val content = journal.composeUserContent(emptyList(), "третий вопрос")

        assertFalse(content.contains(note))
    }

    /**
     * Пометка — утверждение о положении дел, а не указание модели. Разница
     * не стилистическая: указание модель взвешивает против цели ответить, и
     * такому в проверках места нет. Тест держит границу, чтобы формулировку
     * не переписали в повелительное наклонение при правке текста.
     */
    @Test
    fun `текст пометки не содержит указаний`() {
        val forbidden = listOf("не полагайся", "не доверяй", "учти", "помни", "обязан")

        for (word in forbidden) {
            assertFalse(
                "Пометка стала указанием: «$word»",
                note.lowercase().contains(word),
            )
        }
        assertTrue(note.endsWith("."))
    }
}
