package com.uroboros.memory

import com.uroboros.memory.dream.DreamRecall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дословные подписи для модели. Остальные тесты берут подписи из
 * [ProvenanceLabels] ссылкой и проверяют устройство строк; здесь закреплён сам
 * текст, потому что малая модель переписывает подпись в ответ как есть и
 * собеседник читает именно его (см. шапку [ProvenanceLabels]).
 */
class ProvenanceWordingTest {

    @Test
    fun `подписи — речь агента к собеседнику, от первого лица`() {
        assertEquals("Твои слова", ProvenanceLabels.forModel(SourceKind.USER_STATED.name))
        assertEquals("Я сам вывел", ProvenanceLabels.forModel(SourceKind.AGENT_INFERRED.name))
        assertEquals("Мне снилось", ProvenanceLabels.DREAM_FOR_MODEL)
    }

    @Test
    fun `подписи не обращаются к модели и не называют собеседника в третьем лице`() {
        val labels = listOf(
            ProvenanceLabels.forModel(SourceKind.USER_STATED.name),
            ProvenanceLabels.forModel(SourceKind.AGENT_INFERRED.name),
            ProvenanceLabels.DREAM_FOR_MODEL,
        )
        for (label in labels) {
            assertFalse(label, label.startsWith("Тебе") || label.startsWith("Ты "))
            assertFalse(label, label.contains("Пользователь"))
        }
    }

    @Test
    fun `строка сна с прежней подписью узнаётся как сон, запись о снах — нет`() {
        assertTrue(DreamRecall.isDreamLine("Тебе снилось: «а» → «б»."))
        assertTrue(DreamRecall.isDreamLine("Тебе снилось, что рядом с «а» было: «б»."))
        assertFalse(DreamRecall.isDreamLine("Твои слова вчера: «Тебе снилось, что нет»."))
    }
}
