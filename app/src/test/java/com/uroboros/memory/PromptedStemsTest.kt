package com.uroboros.memory

import com.uroboros.memory.dream.SelfLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило подсказки ([promptedStems]): касание записи подсказано, если основа,
 * общая у вопроса владельца и записи, стояла в прошлом ответе агента.
 *
 * Пример взят из живого разговора: агент спросил о радуге, владелец ответил
 * словами вопроса. Проверки на молчание — не менее важная половина: правило,
 * признающее подсказанным всё подряд, обнулило бы чистый счётчик молча.
 *
 * Стена — тоже подсказка: основы тем принятых строк «о себе» (SelfLine.topicStems).
 *
 * Чего эти тесты НЕ проверяют: доходит ли касание до базы, передаёт ли экран
 * прошлый ответ и читает ли отбор стену. Это проводка, а не правило.
 */
class PromptedStemsTest {

    private val agentAsked =
        "Понял, ты продолжаешь работу над радугой цветов. Снова активирован, " +
            "память проверить нужно. Как с цветами радуги?"

    @Test
    fun `ответ словами вопроса агента — подсказано, основа названа`() {
        assertEquals(
            setOf("цвет"),
            promptedStems(
                question = "Цвета на месте, не переживай",
                record = "Мнемоническое правило помогает запомнить все цвета радуги.",
                previousAnswer = agentAsked,
            )
        )
    }

    @Test
    fun `вопрос на свою тему — не подсказано`() {
        assertTrue(
            promptedStems(
                question = "Что можешь обо мне сказать?",
                record = "Ты знаешь о биологии больше, чем я думал.",
                previousAnswer = agentAsked,
            ).isEmpty()
        )
    }

    @Test
    fun `прошлого ответа нет — не подсказано`() {
        assertTrue(
            promptedStems(
                question = "Цвета на месте, не переживай",
                record = "Мнемоническое правило помогает запомнить все цвета радуги.",
                previousAnswer = null,
            ).isEmpty()
        )
    }

    @Test
    fun `у записи нет общих основ с вопросом — не подсказано, даже если прошлый ответ о ней`() {
        assertTrue(
            promptedStems(
                question = "Цвета на месте, не переживай",
                record = "Радуга появляется после дождя.",
                previousAnswer = agentAsked,
            ).isEmpty()
        )
    }

    // ---- Стена ----

    private val wall = SelfLine.topicStems(SelfLine.compose("цветах радуги"))

    @Test
    fun `касание по теме стены подсказано и без прошлого ответа`() {
        assertEquals(
            setOf("цвет"),
            promptedStems(
                question = "Цвета на месте, не переживай",
                record = "Мнемоническое правило помогает запомнить все цвета радуги.",
                previousAnswer = null,
                wallTopicStems = wall,
            )
        )
    }

    @Test
    fun `касание по слову рамки не подсказано`() {
        assertTrue(
            promptedStems(
                question = "Мы долго говорили про рубанок",
                record = "Про рубанок мы говорили вчера.",
                previousAnswer = null,
                wallTopicStems = wall,
            ).isEmpty()
        )
    }

    @Test
    fun `без строк в стене всё как прежде`() {
        assertTrue(
            promptedStems(
                question = "Цвета на месте, не переживай",
                record = "Мнемоническое правило помогает запомнить все цвета радуги.",
                previousAnswer = null,
                wallTopicStems = emptySet(),
            ).isEmpty()
        )
        assertEquals(
            setOf("цвет"),
            promptedStems(
                question = "Цвета на месте, не переживай",
                record = "Мнемоническое правило помогает запомнить все цвета радуги.",
                previousAnswer = agentAsked,
                wallTopicStems = emptySet(),
            )
        )
    }
}
