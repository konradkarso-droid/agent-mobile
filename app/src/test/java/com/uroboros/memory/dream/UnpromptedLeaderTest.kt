package com.uroboros.memory.dream

import com.uroboros.memory.Prism
import com.uroboros.memory.UnpromptedTouch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Условие лидера по касаниям без подсказки и условие «3 ночи из 5».
 *
 * Проверяется и срабатывание, и молчание: лидер, который не должен был
 * выйти, стал бы основанием строки «о себе» на ровном месте.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что ночь записала лидера в базу и что прибор
 * прочитал базу. Это видно только на устройстве.
 */
class UnpromptedLeaderTest {

    private fun touch(
        id: Long,
        count: Int,
        tag: String = "general",
        rejected: Boolean = false,
        pending: Boolean = false,
    ) = UnpromptedTouch(id, count, tag, if (rejected) 1L else null, pending)

    // --- Условие лидера ---

    @Test
    fun `пять касаний и обгон полтора — лидер`() {
        assertEquals(1L, UnpromptedLeader.leaderOf(listOf(touch(1, 6), touch(2, 4))))
    }

    @Test
    fun `четыре касания — не лидер`() {
        assertNull(UnpromptedLeader.leaderOf(listOf(touch(1, 4))))
    }

    @Test
    fun `обгон в 1,4 раза — не лидер`() {
        assertNull(UnpromptedLeader.leaderOf(listOf(touch(1, 7), touch(2, 5))))
    }

    @Test
    fun `второго нет — лидер при пяти`() {
        assertEquals(1L, UnpromptedLeader.leaderOf(listOf(touch(1, 5))))
    }

    @Test
    fun `второй с нулём — обгон выполнен`() {
        assertEquals(1L, UnpromptedLeader.leaderOf(listOf(touch(1, 5), touch(2, 0))))
    }

    @Test
    fun `ничья на первом месте — лидера нет`() {
        val standing = UnpromptedLeader.standing(listOf(touch(1, 8), touch(2, 8), touch(3, 1)))
        assertNull(standing.leaderId)
        assertEquals(2, standing.tiedAtTop)
    }

    @Test
    fun `касаний нет ни у кого — лидера нет`() {
        assertNull(UnpromptedLeader.leaderOf(emptyList()))
    }

    @Test
    fun `запись с меткой identity не учитывается`() {
        val touches = listOf(touch(1, 20, tag = Prism.IDENTITY_TAG), touch(2, 5))
        assertEquals(2L, UnpromptedLeader.leaderOf(touches))
    }

    @Test
    fun `отвергнутая запись не учитывается`() {
        val touches = listOf(touch(1, 20, rejected = true), touch(2, 5))
        assertEquals(2L, UnpromptedLeader.leaderOf(touches))
    }

    @Test
    fun `запись на проверке не учитывается`() {
        val touches = listOf(touch(1, 20, pending = true), touch(2, 5))
        assertEquals(2L, UnpromptedLeader.leaderOf(touches))
    }

    @Test
    fun `неучтённая запись не мешает обгону и сама не лидер`() {
        // Одна отвергнутая — и лидера нет: второй она быть тоже не может.
        assertNull(UnpromptedLeader.leaderOf(listOf(touch(1, 20, rejected = true))))
        assertEquals(2L, UnpromptedLeader.leaderOf(listOf(touch(1, 9, pending = true), touch(2, 6))))
    }

    // --- «3 из 5» ---

    @Test
    fun `три совпадения из пяти — кандидат`() {
        assertTrue(UnpromptedLeader.isCandidate(7L, listOf(7L, 2L, 7L, null, 7L)))
    }

    @Test
    fun `два совпадения из пяти — не кандидат`() {
        assertFalse(UnpromptedLeader.isCandidate(7L, listOf(7L, 2L, 7L, null, 3L)))
    }

    @Test
    fun `null в ночах не совпадает ни с чем`() {
        assertFalse(UnpromptedLeader.isCandidate(null, listOf(null, null, null, null, null)))
        assertEquals(0, UnpromptedLeader.nightsLed(7L, listOf(null, null, null)))
    }

    @Test
    fun `ночей две и обе с этим лидером — не кандидат`() {
        assertFalse(UnpromptedLeader.isCandidate(7L, listOf(7L, 7L)))
    }

    @Test
    fun `ночи старше пятой не считаются`() {
        assertFalse(UnpromptedLeader.isCandidate(7L, listOf(1L, 2L, 3L, 7L, 7L, 7L)))
    }

    // --- Прибор ---

    @Test
    fun `прибор без касаний называет причину`() {
        val line = UnpromptedLeader.meter(UnpromptedLeader.standing(emptyList()), null, emptyList())
        assertEquals("Нажитое о себе: молчу — касаний без подсказки нет", line)
    }

    @Test
    fun `прибор показывает лидера, не прошедшего условие, и насколько далеко`() {
        val standing = UnpromptedLeader.standing(listOf(touch(4, 3), touch(9, 2)))
        val line = UnpromptedLeader.meter(standing, "Про рубанок", listOf(null, 4L))
        assertEquals(
            "Нажитое о себе: молчу — лидер №4 «Про рубанок», касаний 3 из 5, " +
                "обгон ×1.5 из 1.5, ночей 1 из 3 (из последних 2)",
            line,
        )
    }

    @Test
    fun `прибор при лидере без второго говорит «второго нет»`() {
        val standing = UnpromptedLeader.standing(listOf(touch(4, 5)))
        val line = UnpromptedLeader.meter(standing, "Про рубанок", listOf(4L))
        assertTrue(line, line.contains("обгон — второго нет"))
        assertTrue(line, line.startsWith("Нажитое о себе: молчу — лидер №4"))
    }

    @Test
    fun `прибор при ничьей так и говорит`() {
        val standing = UnpromptedLeader.standing(listOf(touch(4, 5), touch(9, 5)))
        val line = UnpromptedLeader.meter(standing, "Про рубанок", emptyList())
        assertTrue(line, line.contains("ничья на первом месте"))
        assertFalse(line, line.contains("кандидат"))
    }

    @Test
    fun `прибор называет кандидата, когда оба условия выполнены`() {
        val standing = UnpromptedLeader.standing(listOf(touch(4, 6), touch(9, 2)))
        val line = UnpromptedLeader.meter(standing, "Про рубанок", listOf(4L, 4L, null, 4L))
        assertEquals(
            "Нажитое о себе: кандидат №4 «Про рубанок» — 6 касаний, обгон ×3.0, ночей 3 из 4",
            line,
        )
    }

    @Test
    fun `прибор режет длинный текст`() {
        val standing = UnpromptedLeader.standing(listOf(touch(4, 1)))
        val line = UnpromptedLeader.meter(standing, "а".repeat(100), emptyList())
        assertTrue(line, line.contains("а".repeat(UnpromptedLeader.PREVIEW_CHARS) + "…»"))
    }
}
