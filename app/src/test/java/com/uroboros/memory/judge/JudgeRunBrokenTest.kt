package com.uroboros.memory.judge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Причина обрыва суда, когда движок вместо ответа прислал ошибку
 * ([JudgeRun.brokenReason]).
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что движок после обрыва сторожем и правда помнит
 * причину сторожа. Это видно на телефоне: строка «Дом:» в группе «Сторож» и
 * строка «Разбор оборван: …» должны говорить об одном.
 */
class JudgeRunBrokenTest {

    @Test
    fun `сторож оборвал посреди обсчёта — называется сторож, а не текст движка`() {
        assertEquals(
            "исчерпан потолок непрерывной работы",
            JudgeRun.brokenReason("исчерпан потолок непрерывной работы", "Failed to evaluate prompt"),
        )
    }

    @Test
    fun `сторож не вмешивался — текст движка остаётся как есть`() {
        assertEquals("Failed to evaluate prompt", JudgeRun.brokenReason(null, "Failed to evaluate prompt"))
    }

    @Test
    fun `ни сторожа, ни текста — сбой движка словами`() {
        assertEquals("сбой движка", JudgeRun.brokenReason(null, null))
    }
}
