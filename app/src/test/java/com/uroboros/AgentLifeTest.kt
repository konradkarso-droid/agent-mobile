package com.uroboros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слова прибора жизни.
 *
 * ГЛАВНОЕ ЗДЕСЬ: мёртвая служба не выдаётся за живую, и провал подъёма виден
 * первым. Время в строке зависит от часового пояса машины, поэтому
 * проверяется его наличие, а не значение.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что служба действительно поднимается после
 * перезагрузки и установки. Это видно только по счёту на устройстве.
 */
class AgentLifeTest {

    private val born = AgentLife.Record(starts = 1, lastStartAt = 1_000L)

    @Test
    fun `первый подъём — не кома`() {
        assertEquals(0, born.revivals)
        assertEquals(0, AgentLife.Record(starts = 0, lastStartAt = null).revivals)
        assertEquals(2, AgentLife.Record(starts = 3, lastStartAt = 1_000L).revivals)
    }

    @Test
    fun `живой говорит, с какого момента`() {
        val text = AgentLife.line(alive = true, record = born, batteryOptimized = false, failure = null)
        assertTrue(text, text.startsWith("Жив с "))
        assertTrue(text, text.contains("подъёмов после комы: 0"))
        assertTrue(text, text.endsWith("оптимизация батареи: выкл"))
    }

    @Test
    fun `мёртвая служба не выдаётся за живую`() {
        val text = AgentLife.line(alive = false, record = born, batteryOptimized = true, failure = null)
        assertFalse(text, text.contains("Жив с"))
        assertTrue(text, text.startsWith("Агент не жив"))
        assertTrue(text, text.contains("может усыплять"))
    }

    @Test
    fun `у мёртвой службы названа причина, у живой — нет`() {
        val dead = AgentLife.line(alive = false, record = born, batteryOptimized = null, failure = "запрет системы")
        assertTrue(dead, dead.startsWith("Агент не поднялся: запрет системы"))
        assertTrue(dead, dead.endsWith("оптимизация батареи: ?"))
        val alive = AgentLife.line(alive = true, record = born, batteryOptimized = null, failure = "запрет системы")
        assertTrue(alive, alive.startsWith("Жив с "))
    }
}
