package com.uroboros.memory.judge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Потолок выдачи судьи не сдвинулся оттого, что у переходника появился
 * параметр `answerTokens` (его берёт тема строки о себе, SelfLineStep).
 *
 * Чего тест НЕ проверяет: что умолчание параметра — именно эта константа.
 * Экземпляр переходника без движка модели в JVM-тесте не собрать; связь
 * умолчания с константой видна в объявлении класса глазами.
 */
class EngineJudgeLlmTest {

    @Test
    fun `потолок судьи по умолчанию прежний — два токена`() {
        assertEquals(2, EngineJudgeLlm.ANSWER_TOKENS)
    }
}
