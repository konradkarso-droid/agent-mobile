package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Перехват повтора [EchoIntercept] и запрос без выкинутых ходов
 * ([ConversationJournal.messagesFor], `without`).
 *
 * Главные — проверки на молчание: ответ по делу не перехватывается;
 * предложение из одной основы не перехватывается; копия ответа, стоящего
 * дальше трёх последних, не перехватывается; ход, чей ответ не скопирован,
 * из второй попытки не выкидывается. Перехват, срабатывающий на всё, в
 * проверках «ловит» выглядел бы правильным.
 *
 * Сама вторая попытка ([ConversationTurns]) здесь не проверяется: она ходит в
 * движок, а движка в тестах нет. Её проверка — на телефоне.
 */
class EchoInterceptTest {

    private fun turn(question: String, answer: String, selfNote: String? = null) = ConversationJournal.Turn(
        userContent = question,
        agentContent = answer,
        question = question,
        selfNote = selfNote,
    )

    // Живой пример с телефона: ответы ходов 1 и 2 одной ленты.
    private val memoryAnswer = "Особого раннего воспоминания в моей памяти нет. Я живу постоянно, и мой опыт формируется каждый день."
    private val copiedAnswer = "Особого раннего воспоминания в моей памяти нет. Могу рассказать о чём-нибудь другом."

    // --- Перехват ---

    @Test
    fun `копия прошлого ответа перехватывается, и выкидывается ход-образец`() {
        val history = listOf(
            turn("Привет", "Привет, рад тебя слышать."),
            turn("Какое твоё самое раннее воспоминание?", memoryAnswer),
        )
        val retry = EchoIntercept.decide(copiedAnswer, "А всё-таки?", history)
        assertNotNull(retry)
        assertEquals("Особого раннего воспоминания в моей памяти нет.", retry!!.sentence)
        assertEquals(setOf(1), retry.without)
    }

    @Test
    fun `повтор двух разных ответов выкидывает оба хода`() {
        val history = listOf(
            turn("Что делаешь?", "Я выполняю задания и отвечаю на вопросы."),
            turn("Кто ты?", "Как агент, я не могу иметь собственное мнение."),
            turn("Как дела?", "Сегодня спокойный день, работы немного."),
        )
        val answer = "Как агент, я не могу иметь собственное мнение. Я выполняю задания и отвечаю на вопросы."
        val retry = EchoIntercept.decide(answer, "Ну а всё-таки?", history)
        assertEquals(setOf(0, 1), retry!!.without)
    }

    // --- Молчание ---

    @Test
    fun `ответ по делу не перехватывается`() {
        val history = listOf(turn("Какое твоё самое раннее воспоминание?", memoryAnswer))
        assertNull(EchoIntercept.decide("Вчера мне снились черепахи и поединок.", "Что снилось?", history))
    }

    @Test
    fun `предложение из одной основы не перехватывается`() {
        val history = listOf(turn("Помнишь?", "Помню."))
        assertNull(EchoIntercept.decide("Помню.", "А это помнишь?", history))
    }

    @Test
    fun `копия ответа дальше трёх последних не перехватывается`() {
        val history = listOf(
            turn("Какое твоё самое раннее воспоминание?", memoryAnswer),
            turn("Как дела?", "Сегодня спокойный день, работы немного."),
            turn("Что снилось?", "Мне снились цвета и черепахи."),
            turn("Ты работаешь по субботам?", "По субботам я отвечаю так же, как в будни."),
        )
        assertNull(EchoIntercept.decide(copiedAnswer, "А всё-таки?", history))
    }

    @Test
    fun `на пустой ленте перехватывать нечего`() {
        assertNull(EchoIntercept.decide(copiedAnswer, "Привет", emptyList()))
    }

    @Test
    fun `ход с непохожим ответом из второй попытки не выкидывается`() {
        val history = listOf(
            turn("Какое твоё самое раннее воспоминание?", memoryAnswer),
            turn("Как дела?", "Сегодня спокойный день, работы немного."),
        )
        assertEquals(setOf(0), EchoIntercept.decide(copiedAnswer, "А всё-таки?", history)!!.without)
    }

    // --- Строка «Эхо:» после перехвата ---

    @Test
    fun `строка эха меряет отброшенный ответ, а не легший на его место`() {
        val earlier = turn("Какое твоё самое раннее воспоминание?", memoryAnswer)
        val intercepted = ConversationJournal.Turn(
            userContent = "А всё-таки?", agentContent = "Вчера мне снились черепахи.",
            question = "А всё-таки?", rejected = copiedAnswer,
        )
        assertEquals(
            "Особого раннего воспоминания в моей памяти нет.",
            EchoCheck.ofLast(listOf(earlier, intercepted))!!.selfRepeat,
        )
    }

    @Test
    fun `без перехвата строка эха меряет сам ответ`() {
        val earlier = turn("Какое твоё самое раннее воспоминание?", memoryAnswer)
        val plain = turn("Что снилось?", "Вчера мне снились черепахи.")
        assertNull(EchoCheck.ofLast(listOf(earlier, plain))!!.selfRepeat)
    }

    // --- Запрос без выкинутых ходов ---

    private fun journalOf(vararg turns: ConversationJournal.Turn) =
        ConversationJournal().apply { restore(turns.toList()) }

    @Test
    fun `без выкинутых ходов запрос прежний`() {
        val journal = journalOf(turn("а", "ответ а"), turn("б", "ответ б", selfNote = "строка о себе"))
        assertEquals(
            journal.messagesFor("в", currentSelfNote = "сейчас"),
            journal.messagesFor("в", currentSelfNote = "сейчас", without = emptySet()),
        )
    }

    @Test
    fun `выкинутый последний ход не трогает начало запроса`() {
        val journal = journalOf(turn("а", "ответ а"), turn("б", "ответ б"), turn("в", "ответ в"))
        val full = journal.messagesFor("г")
        val cut = journal.messagesFor("г", without = setOf(2))
        assertEquals(full.take(4), cut.take(4))
        assertEquals(listOf("user" to "г"), cut.drop(4))
        assertNotEquals(full, cut)
    }

    @Test
    fun `выкинутый первый ход не ставит системную строку первой`() {
        val journal = journalOf(turn("а", "ответ а"), turn("б", "ответ б", selfNote = "строка о себе"))
        val cut = journal.messagesFor("в", currentSelfNote = "сейчас", without = setOf(0))
        assertNotEquals("system", cut.first().first)
        assertEquals("user" to "б", cut.first())
    }

    @Test
    fun `выкинуты все ходы — запрос как на пустой ленте`() {
        val journal = journalOf(turn("а", "ответ а", selfNote = "о себе"))
        assertEquals(listOf("user" to "б"), journal.messagesFor("б", currentSelfNote = "сейчас", without = setOf(0)))
    }
}
