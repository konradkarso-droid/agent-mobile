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

        val content = journal.composeUserContent("Сколько звёзд на небе?")

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

        val content = journal.composeUserContent("Где обедал воробей?")

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

        val content = journal.composeUserContent("Где обедал воробей?")

        assertEquals("Где обедал воробей?", content)
    }

    @Test
    fun `записи в реплику не идут, пометка в ней на месте`() {
        val journal = ConversationJournal()
        journal.appendTurn(
            userContent = "Сколько звёзд на небе?",
            agentContent = "Около ста миллиардов.",
            question = "Сколько звёзд на небе?",
            records = emptyList(),
        )

        val content = journal.composeUserContent("Какое правило?")

        assertEquals(note + "\n\n" + "Какое правило?", content)
        assertFalse(content.contains("Правило есть"))
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

        val content = journal.composeUserContent("четвёртый вопрос")

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

        val content = journal.composeUserContent("третий вопрос")

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
        val content = journal.composeUserContent("следующий вопрос")

        assertEquals(note + "\n\n" + "следующий вопрос", content)
    }

    @Test
    fun `предложение спросить стоит под меткой, вопрос — последним`() {
        val journal = ConversationJournal()

        val content = journal.composeUserContent(
            "Какое правило?",
            curiosityAsk = "Меня занимает, как связано.",
        )

        assertEquals(
            "[Мне от системы: Меня занимает, как связано.]" + "\n\n" + "Какое правило?",
            content,
        )
        assertEquals(
            "без предложения блока нет",
            "Какое правило?",
            journal.composeUserContent("Какое правило?", curiosityAsk = null),
        )
    }

    /**
     * Ход, начатый агентом («пишет первым»): вопрос пустой, реплика — служебная
     * строка. Лента обязана принять такой ход и при закрытии, и при подъёме с
     * диска, и отдать модели его реплику как есть. Хранилище на диске (Room)
     * обычным тестом не достать — здесь закреплена память.
     */
    @Test
    fun `ход с пустым вопросом ложится в ленту и поднимается`() {
        val journal = ConversationJournal()
        var appended: ConversationJournal.Turn? = null
        journal.onTurnAppended = { _, turn -> appended = turn }

        journal.appendTurn("Пользователь молчит больше часа.", "Как тебе тот сон?", question = "", records = emptyList())

        assertEquals("", appended?.question)
        assertEquals("Пользователь молчит больше часа.", journal.messagesFor("Сон был странный.")[0].second)

        val raised = ConversationJournal()
        assertTrue(raised.restore(journal.history()))
        assertEquals("", raised.history().single().question)
        assertEquals("Как тебе тот сон?", raised.messagesFor("x")[1].second)
    }

    // СТРОКА О СЕБЕ — системным сообщением перед репликой своего хода и
    // никогда первым (см. ConversationJournal.messagesFor). Главная проверка —
    // [на пустой ленте строка о себе не уходит]: системное сообщение первым
    // оставило бы модель без стены, и ответы при этом не падают, а молча
    // теряют всё, что в стене.

    @Test
    fun `на пустой ленте строка о себе не уходит и в ход не ложится`() {
        val journal = ConversationJournal()

        val messages = journal.messagesFor("Привет", "Я очнулся в 15:42.")
        assertEquals(listOf(ConversationJournal.ROLE_USER to "Привет"), messages)

        journal.appendTurn("Привет", "Привет!", "Привет", emptyList(), selfNote = "Я очнулся в 15:42.")
        assertEquals(null, journal.history().single().selfNote)
        assertEquals(ConversationJournal.ROLE_USER, journal.messagesFor("дальше").first().first)
    }

    @Test
    fun `строка о себе стоит перед репликой своего хода и остаётся на месте`() {
        val journal = ConversationJournal()
        journal.appendTurn("Привет", "Привет!", "Привет", emptyList())

        val note = "Перед этим у меня был перерыв в жизни: я очнулся в 15:42."
        val sent = journal.messagesFor("Как дела?", note)
        assertEquals(
            listOf(
                ConversationJournal.ROLE_USER to "Привет",
                ConversationJournal.ROLE_ASSISTANT to "Привет!",
                ConversationJournal.ROLE_SYSTEM to note,
                ConversationJournal.ROLE_USER to "Как дела?",
            ),
            sent,
        )

        journal.appendTurn("Как дела?", "Хорошо.", "Как дела?", emptyList(), selfNote = note)
        assertEquals(note, journal.history().last().selfNote)
        // Следующий запрос начинается ровно тем, что ушло в прошлый раз, —
        // иначе начало разойдётся с обсчитанным.
        val next = journal.messagesFor("А ещё?")
        assertEquals(sent, next.take(sent.size))
        assertEquals(ConversationJournal.ROLE_ASSISTANT to "Хорошо.", next[sent.size])
    }

    @Test
    fun `сохранённая у первого хода строка о себе первой не встаёт`() {
        val raised = ConversationJournal()
        assertTrue(
            raised.restore(
                listOf(ConversationJournal.Turn("Привет", "Привет!", "Привет", selfNote = "Я очнулся в 05:01.")),
            ),
        )
        val messages = raised.messagesFor("дальше")
        assertEquals(ConversationJournal.ROLE_USER, messages.first().first)
        assertFalse(messages.any { it.first == ConversationJournal.ROLE_SYSTEM })
    }

    @Test
    fun `в системную роль не попадает ни реплика, ни записи`() {
        val journal = ConversationJournal()
        journal.appendTurn("Твои слова вчера: «Отдыхаю».\n\nПривет", "Привет!", "Привет", listOf("Твои слова вчера: «Отдыхаю»."))
        val fresh = "Твои слова сегодня: «Я был у стоматолога»."
        val content = journal.composeUserContent("Что нового?")

        val system = journal.messagesFor(content, "Я очнулся в 15:42.", null, journal.recallOf(listOf(fresh)))
            .filter { it.first == ConversationJournal.ROLE_SYSTEM }
            .map { it.second }
        assertEquals(listOf("Я очнулся в 15:42."), system)
    }
}

class ConversationJournalDreamNoteTest {

    @Test
    fun `описание ночи не ставится первым и не хранится у первого хода`() {
        val journal = ConversationJournal()
        val first = journal.messagesFor("привет", currentDreamNote = "Этой ночью мне снилось: радуга.")
        assertEquals(listOf(ConversationJournal.ROLE_USER to "привет"), first)
        journal.appendTurn("привет", "здравствуй", "привет", emptyList(), dreamNote = "Этой ночью мне снилось: радуга.")
        assertEquals(null, journal.history()[0].dreamNote)
    }

    @Test
    fun `описание ночи — системным сообщением перед репликой, за строкой о себе`() {
        val journal = ConversationJournal()
        journal.appendTurn("привет", "здравствуй", "привет", emptyList())
        val messages = journal.messagesFor("что снилось?", "Я бодр.", "Этой ночью мне снилось: радуга.")
        assertEquals(
            listOf(
                ConversationJournal.ROLE_SYSTEM to "Я бодр.",
                ConversationJournal.ROLE_SYSTEM to "Этой ночью мне снилось: радуга.",
                ConversationJournal.ROLE_USER to "что снилось?",
            ),
            messages.takeLast(3),
        )
        journal.appendTurn("что снилось?", "радуга", "что снилось?", emptyList(), dreamNote = "Этой ночью мне снилось: радуга.", at = 5L)
        assertEquals("Этой ночью мне снилось: радуга.", journal.history()[1].dreamNote)
        assertEquals(5L, journal.history()[1].at)
    }
}

class ConversationJournalRecallTest {

    // ЗАПИСИ ГОЛОСОМ АГЕНТА — сообщением агента перед репликой своего хода
    // (см. ConversationJournal.recallOf). Главная проверка —
    // [старый ход без вспоминания уходит как был]: лента только дописывается,
    // и лишнее сообщение у старого хода сдвинуло бы всё обсчитанное начало.

    private val rec1 = "Твои слова вчера: «Правило есть.»."
    private val rec2 = "Я говорил на днях: «Мне снилось море.»."

    @Test
    fun `нет записей — нет вспоминания`() {
        assertEquals(null, ConversationJournal().recallOf(emptyList()))
    }

    @Test
    fun `вспоминание — записи строками, в том же порядке`() {
        assertEquals(rec1 + "\n" + rec2, ConversationJournal().recallOf(listOf(rec1, rec2)))
    }

    @Test
    fun `вспоминание стоит сообщением агента за системными и перед репликой`() {
        val journal = ConversationJournal()
        journal.appendTurn("Привет", "Привет!", "Привет", emptyList())

        val sent = journal.messagesFor("Какое правило?", "Я очнулся в 15:42.", null, rec1)

        assertEquals(
            listOf(
                ConversationJournal.ROLE_USER to "Привет",
                ConversationJournal.ROLE_ASSISTANT to "Привет!",
                ConversationJournal.ROLE_SYSTEM to "Я очнулся в 15:42.",
                ConversationJournal.ROLE_ASSISTANT to rec1,
                ConversationJournal.ROLE_USER to "Какое правило?",
            ),
            sent,
        )
    }

    /** Стену движок ставит, когда первое сообщение не системное; сообщение агента первым быть может. */
    @Test
    fun `на пустой ленте вспоминание уходит первым`() {
        val sent = ConversationJournal().messagesFor("Какое правило?", currentRecall = rec1)

        assertEquals(
            listOf(ConversationJournal.ROLE_ASSISTANT to rec1, ConversationJournal.ROLE_USER to "Какое правило?"),
            sent,
        )
    }

    @Test
    fun `вспоминание ложится в ход и уходит на своём месте после подъёма`() {
        val journal = ConversationJournal()
        journal.appendTurn("Какое правило?", "Такое.", "Какое правило?", listOf(rec1), recall = rec1)

        assertEquals(rec1, journal.history().single().recall)
        val raised = ConversationJournal()
        assertTrue(raised.restore(journal.history()))
        assertEquals(
            listOf(
                ConversationJournal.ROLE_ASSISTANT to rec1,
                ConversationJournal.ROLE_USER to "Какое правило?",
                ConversationJournal.ROLE_ASSISTANT to "Такое.",
                ConversationJournal.ROLE_USER to "дальше",
            ),
            raised.messagesFor("дальше"),
        )
    }

    /** Проверка на молчание: старый ход с записями внутри реплики лишнего сообщения не получает. */
    @Test
    fun `старый ход без вспоминания уходит как был`() {
        val journal = ConversationJournal()
        journal.appendTurn(rec1 + "\n\nКакое правило?", "Такое.", "Какое правило?", listOf(rec1))

        assertEquals(
            listOf(
                ConversationJournal.ROLE_USER to rec1 + "\n\nКакое правило?",
                ConversationJournal.ROLE_ASSISTANT to "Такое.",
                ConversationJournal.ROLE_USER to "дальше",
            ),
            journal.messagesFor("дальше"),
        )
    }

    @Test
    fun `пустое вспоминание в ход не ложится и не уходит`() {
        val journal = ConversationJournal()
        journal.appendTurn("Привет", "Привет!", "Привет", emptyList(), recall = "  ")

        assertEquals(null, journal.history().single().recall)
        assertEquals(2 + 1, journal.messagesFor("дальше", currentRecall = "").size)
    }
}
