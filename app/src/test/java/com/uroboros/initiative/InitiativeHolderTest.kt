package com.uroboros.initiative

import com.uroboros.initiative.InitiativeHolder.Holder
import com.uroboros.llm.ConversationJournal.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Прибор инициативы (InitiativeHolder). Главное здесь — проверки на молчание:
 * реплика без уверенного признака оставляет ход у владельца, иначе будущая
 * подача сделает агента напористым на пустом месте.
 */
class InitiativeHolderTest {

    private fun owner(question: String, agent: String = "Ответ.") = Turn(question, agent, question)
    private fun agentFirst(agent: String = "Я тут подумал.") = Turn("служебная строка", agent, "")

    private fun holder(previousAgent: String?, question: String): Pair<Holder, String> =
        InitiativeHolder.classify(previousAgent, question)

    @Test
    fun `пустая лента — чтения нет, строка говорит почему`() {
        assertNull(InitiativeHolder.read(emptyList()))
        assertEquals("Инициатива: разговора нет — определять не по чему", InitiativeHolder.meter(null))
    }

    @Test
    fun `ход начат агентом — ход у агента, без цитаты`() {
        val reading = InitiativeHolder.read(listOf(agentFirst()))!!
        assertEquals(Holder.AGENT, reading.holder)
        assertNull(reading.fragment)
        assertEquals(
            "Инициатива: у агента — агент заговорил первым · у агента ходов подряд: 1",
            InitiativeHolder.meter(reading),
        )
    }

    @Test
    fun `вопрос со знаком — у владельца`() {
        assertEquals(Holder.OWNER to "вопрос", holder(null, "а зеркало?"))
    }

    @Test
    fun `вопросительное слово в начале или «ли» — у владельца`() {
        assertEquals(Holder.OWNER to "вопрос без «?»", holder(null, "как там погода"))
        assertEquals(Holder.OWNER to "вопрос без «?»", holder(null, "знаешь ли ты это"))
    }

    @Test
    fun `просьба — у владельца`() {
        assertEquals(Holder.OWNER to "просьба", holder(null, "расскажи про рубанок"))
    }

    @Test
    fun `поддакивание — у агента`() {
        assertEquals(Holder.AGENT to "поддакивание", holder(null, "ага"))
        assertEquals(Holder.AGENT to "поддакивание", holder(null, "Ну да, хорошо."))
        assertEquals(Holder.AGENT to "поддакивание", holder(null, "ничего особенного"))
    }

    @Test
    fun `молчание — согласие с продолжением длиннее потолка — не поддакивание`() {
        assertEquals(Holder.OWNER to "утверждение", holder(null, "да, я сегодня весь день работал"))
    }

    @Test
    fun `ответ на вопрос агента с его словом — у агента`() {
        assertEquals(
            Holder.AGENT to "ответ на вопрос агента",
            holder("Интересно. Ты уже починил рубанок?", "Рубанок починил вчера вечером"),
        )
    }

    @Test
    fun `своё после вопроса агента — отбито, у владельца`() {
        assertEquals(
            Holder.OWNER to "своё после вопроса агента — отбито",
            holder("Ты уже починил рубанок?", "Сегодня жарко на улице"),
        )
    }

    @Test
    fun `молчание — утверждение без вопроса агента — у владельца`() {
        assertEquals(Holder.OWNER to "утверждение", holder("Рубанок хороший.", "Рубанок починил вчера"))
        assertEquals(Holder.OWNER to "утверждение", holder(null, "Рубанок починил вчера"))
    }

    @Test
    fun `молчание — смайлик без слов — утверждение`() {
        assertEquals(Holder.OWNER to "утверждение", holder(null, "👍"))
    }

    @Test
    fun `счёт подряд — по хвосту ленты и сбрасывается владельцем`() {
        val three = listOf(owner("Привет?"), agentFirst(), owner("ага"), owner("угу"))
        assertEquals(3, InitiativeHolder.read(three)!!.agentStreak)
        val reset = three + owner("а зеркало?")
        val reading = InitiativeHolder.read(reset)!!
        assertEquals(0, reading.agentStreak)
        assertEquals(
            "Инициатива: у владельца — вопрос («а зеркало?») · у агента ходов подряд: 0",
            InitiativeHolder.meter(reading),
        )
    }

    @Test
    fun `вопрос агента берётся из прошлого хода, а не из этого`() {
        // Этот ход ответил вопросом, но его реплика владельца стоит ДО ответа.
        val history = listOf(owner("Рубанок починил вчера", agent = "А стамеску?"))
        assertEquals(Holder.OWNER, InitiativeHolder.read(history)!!.holder)
    }

    @Test
    fun `цитата обрезается до объявленной длины`() {
        val long = "это очень длинное утверждение о том, как прошёл день"
        val reading = InitiativeHolder.read(listOf(owner(long)))!!
        assertEquals(long.take(InitiativeHolder.FRAGMENT_CHARS).trimEnd() + "…", reading.fragment)
    }
}
