package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замки на два механизма [ConversationJournal]: пометку «прошлый ответ без
 * опоры» и отрезание последнего хода. Общее у них то, ради чего тесты и
 * написаны, — обе ошибки молчаливы.
 *
 * ПОМЕТКА. Отвечает на вопрос, была ли под прошлым ответом хоть одна
 * запись памяти, и в ленту она уходит навсегда. Ошибка молчалива в обе
 * стороны: пропущенная пометка оставляет выдумку выглядеть
 * подтверждённой, а лишняя — вешает подозрение на честный ответ. Ни то,
 * ни другое на экране не видно.
 *
 * Главная проверка — [пометки нет после хода с записями]. Механизм,
 * который лепит пометку в каждый запрос, во всех остальных случаях
 * выглядел бы правильным.
 *
 * Условие пометки — ПУСТОЙ список записей прошлого хода, а не отсутствие
 * новых: запись, легшая на первом ходе, на шестом не подставляется
 * повторно, но модели всё это время видна и опорой быть не перестаёт.
 *
 * ОТРЕЗАНИЕ. Убирает последний ход и снимает отметки записей, легших на
 * нём впервые. Главная проверка здесь —
 * [отметка записи с прошлых ходов после отрезания остаётся]: механизм,
 * снимающий отметки со всех записей подряд, во всех остальных проверках
 * выглядел бы правильным, а стоил бы повторной укладки старых записей в
 * ленту на каждом ходе.
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

    // --- Отрезание последнего хода ---

    /** Проверка на молчание: пустой ленте отрезать нечего. */
    @Test
    fun `на пустой ленте отрезать нечего`() {
        val journal = ConversationJournal()

        assertEquals(null, journal.dropLastTurn())
        assertEquals(0, journal.turnCount)
    }

    @Test
    fun `отрезание убирает ровно один ход, предыдущий цел`() {
        val journal = ConversationJournal()
        journal.appendTurn("первый", "ответ 1", "первый", emptyList())
        journal.appendTurn("второй", "ответ 2", "второй", emptyList())

        val dropped = journal.dropLastTurn()

        assertEquals("ответ 2", dropped?.agentContent)
        assertEquals(1, journal.turnCount)
        assertEquals("ответ 1", journal.history().last().agentContent)
    }

    /**
     * Запись, чей единственный след стёрт, обязана снова считаться
     * неуложенной. Иначе [ConversationJournal.unseenRecords] отбрасывала бы
     * её и дальше, и в ленту она не попала бы больше никогда — при том, что
     * в базе она есть и отбор её находит.
     */
    @Test
    fun `запись, легшая на отрезанном ходе, снова считается новой`() {
        val journal = ConversationJournal()
        val record = "Пользователь сказал: «Правило есть.»"
        journal.appendTurn("вопрос", "ответ", "вопрос", listOf(record))

        assertEquals(emptyList<String>(), journal.unseenRecords(listOf(record)))

        journal.dropLastTurn()

        assertEquals(listOf(record), journal.unseenRecords(listOf(record)))
    }

    /**
     * Главная проверка отрезания. Механизм, снимающий отметки со всех
     * записей подряд, прошёл бы все остальные тесты этого раздела — и
     * укладывал бы старые записи в ленту повторно, по 200 токенов за раз.
     */
    @Test
    fun `отметка записи с прошлых ходов после отрезания остаётся`() {
        val journal = ConversationJournal()
        val old = "Пользователь сказал: «Старая запись.»"
        val fresh = "Пользователь сказал: «Свежая запись.»"
        journal.appendTurn("первый", "ответ 1", "первый", listOf(old))
        journal.appendTurn("второй", "ответ 2", "второй", listOf(fresh))

        journal.dropLastTurn()

        assertEquals(listOf(fresh), journal.unseenRecords(listOf(old, fresh)))
    }

    /**
     * Счётчик описывает запрос, в который отрезанный ход входил, поэтому
     * после отрезания он завышает. Пересчитать его нечем: настоящее число
     * приходит от движка. Ноль означает «не измерено».
     */
    @Test
    fun `после отрезания размер запроса считается неизмеренным`() {
        val journal = ConversationJournal()
        journal.appendTurn("вопрос", "ответ", "вопрос", emptyList())
        journal.notePromptTokens(500)

        journal.dropLastTurn()

        assertEquals(0, journal.lastPromptTokens)
    }

    /**
     * Пометка считается по ленте, а не по прошлому состоянию: отрезали
     * опорный ход — последним снова стал безопорный, и пометка обязана
     * вернуться.
     */
    @Test
    fun `после отрезания опорного хода пометка возвращается`() {
        val journal = ConversationJournal()
        journal.appendTurn("без опоры", "выдумка", "без опоры", emptyList())
        journal.appendTurn(
            "с опорой",
            "ответ по записи",
            "с опорой",
            listOf("Пользователь сказал: «Правило есть.»"),
        )

        journal.dropLastTurn()
        val content = journal.composeUserContent(emptyList(), "следующий вопрос")

        assertEquals(note + "\n\n" + "следующий вопрос", content)
    }
}
