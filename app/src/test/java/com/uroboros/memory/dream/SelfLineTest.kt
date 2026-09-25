package com.uroboros.memory.dream

import com.uroboros.memory.RiskTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строка о себе ([SelfLine]): рамка, решение ночи, разбор ответа модели,
 * проверка темы и основы темы. Проверки на молчание — половина файла: шаг,
 * предлагающий строку там, где должен молчать, положил бы в очередь то, чего
 * не просили.
 *
 * Чего эти тесты НЕ проверяют: что модель на деле ответит на вопрос о теме и
 * что строка дойдёт до базы и стены. Это проводка (SelfLineStep, LlmEngine), её
 * проверяет владелец на телефоне.
 */
class SelfLineTest {

    // ---- Рамка ----

    @Test
    fun `рамка с «о» перед согласной`() {
        assertEquals(
            "Долгое время мы с собеседником чаще всего говорили о радуге.",
            SelfLine.compose("радуге"),
        )
    }

    @Test
    fun `рамка с «об» перед гласной`() {
        assertEquals(
            "Долгое время мы с собеседником чаще всего говорили об истории рубанков.",
            SelfLine.compose("истории рубанков"),
        )
        assertTrue(SelfLine.compose("Олеге").contains(" об Олеге."))
    }

    // ---- Решение ночи ----

    /** Лидер №7 с условием; ночей-лидеров [led] из пяти. */
    private fun inputs(
        leaderId: Long? = 7,
        led: Int = 3,
        pendingId: Long? = null,
        accepted: Int = 0,
        settled: SelfLine.Settled? = null,
    ) = SelfLine.Inputs(
        standing = UnpromptedLeader.Standing(
            topId = 7, topCount = 9, secondCount = 2, tiedAtTop = 1, leaderId = leaderId,
        ),
        lastNights = List(led) { 7L } + List(5 - led) { null },
        pendingId = pendingId,
        acceptedCount = accepted,
        leaderSettled = settled,
    )

    @Test
    fun `кандидат без помех — предложить`() {
        assertEquals(SelfLine.Decision.Propose(7), SelfLine.decide(inputs()))
    }

    @Test
    fun `лидер без кандидатства — проба`() {
        assertEquals(SelfLine.Decision.Probe(7), SelfLine.decide(inputs(led = 2)))
    }

    @Test
    fun `проба не смотрит на очередь и потолок — она ничего не сохраняет`() {
        assertEquals(
            SelfLine.Decision.Probe(7),
            SelfLine.decide(inputs(led = 1, pendingId = 40, accepted = 3)),
        )
    }

    @Test
    fun `молчу — лидера нет`() {
        assertEquals(SelfLine.Decision.Silent("лидера нет"), SelfLine.decide(inputs(leaderId = null)))
    }

    @Test
    fun `молчу — строка на проверке`() {
        assertEquals(
            SelfLine.Decision.Silent("строка №40 ждёт решения"),
            SelfLine.decide(inputs(pendingId = 40)),
        )
    }

    @Test
    fun `молчу — потолок стены`() {
        assertEquals(
            SelfLine.Decision.Silent("в стене уже 3 из 3"),
            SelfLine.decide(inputs(accepted = 3)),
        )
    }

    @Test
    fun `молчу — по основанию строка уже принята`() {
        assertEquals(
            SelfLine.Decision.Silent("по №7 строка уже была принята"),
            SelfLine.decide(inputs(settled = SelfLine.Settled.ACCEPTED)),
        )
    }

    @Test
    fun `молчу — по основанию строка уже отвергнута`() {
        assertEquals(
            SelfLine.Decision.Silent("по №7 строка уже была отвергнута"),
            SelfLine.decide(inputs(settled = SelfLine.Settled.REJECTED)),
        )
    }

    // ---- Разбор ответа ----

    private fun topic(answer: String): String =
        (SelfLine.parseTopic(answer) as SelfLine.Parsed.Topic).text

    private fun refused(answer: String): String =
        (SelfLine.parseTopic(answer) as SelfLine.Parsed.Refused).reason

    @Test
    fun `кавычки и точка снимаются`() {
        assertEquals("цветах радуги", topic("«цветах радуги»."))
        assertEquals("цветах радуги", topic("\"цветах радуги\""))
    }

    @Test
    fun `ведущее «о» и «об» снимаются`() {
        assertEquals("цветах радуги", topic("о цветах радуги"))
        assertEquals("истории рубанков", topic("Об истории рубанков."))
    }

    @Test
    fun `слово на «о» не принимается за предлог`() {
        assertEquals("обработке дерева", topic("обработке дерева"))
    }

    @Test
    fun `из двух строк берётся первая непустая`() {
        assertEquals("цветах радуги", topic("\n  цветах радуги\nЭто тема записи."))
    }

    @Test
    fun `пустой ответ — отказ`() {
        assertEquals("модель не назвала тему", refused("  \n "))
        assertEquals("модель не назвала тему", refused("«»."))
    }

    @Test
    fun `семь слов — отказ`() {
        assertTrue(refused("одном двух трёх четырёх пяти шести семи").startsWith("тема длиннее 6 слов"))
    }

    @Test
    fun `длиннее шестидесяти знаков — отказ`() {
        assertTrue(
            refused("чрезвычайнейших непреодолимейших обстоятельствах человеконенавистничества")
                .startsWith("тема длиннее 60 знаков")
        )
    }

    // ---- Проверка темы ----

    private val record = "Мнемоническое правило помогает запомнить все цвета радуги."

    @Test
    fun `тема из слов записи проходит`() {
        assertNull(SelfLine.checkTopic("цветах радуги", 7, record))
    }

    @Test
    fun `лишняя основа — отказ, основа названа`() {
        assertEquals("основ «дожд» нет в №7", SelfLine.checkTopic("радуге после дождя", 7, record))
    }

    @Test
    fun `тема из одних служебных слов — отказ`() {
        val reason = SelfLine.checkTopic("всё это", 7, record)
        assertEquals("в теме «всё это» нет значимых слов", reason)
    }

    // ---- Основы темы ----

    @Test
    fun `основы темы не содержат основ рамки`() {
        val stems = SelfLine.topicStems(SelfLine.compose("цветах радуги"))
        val frame = RiskTrigger.significantStems(SelfLine.FRAME)
        assertTrue(frame.isNotEmpty())
        assertTrue(stems.none { it in frame })
        assertEquals(RiskTrigger.significantStems("цветах радуги"), stems)
    }
}
