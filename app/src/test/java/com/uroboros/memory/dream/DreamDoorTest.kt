package com.uroboros.memory.dream

import com.uroboros.memory.Layer
import com.uroboros.memory.Sticker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Дверь сна.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: вопрос не о записи — её нет в найденных,
 * скрытая и отвергнутая за дверью не выходят, дверь закрывается сама.
 * Дверь — одиночка на процесс, поэтому каждый тест начинает и кончает с
 * закрытыми дверями.
 */
class DreamDoorTest {

    @Before fun open() = DreamDoor.closeAll()
    @After fun close() = DreamDoor.closeAll()

    private fun rec(id: Long, text: String) = Sticker(id = id, content = text, layer = Layer.PURPLE.name)

    private val towel = rec(1, "Всегда носи с собой полотенце")

    @Test
    fun `вопрос о записи находит её за дверью`() {
        val got = DreamDoor.pick(listOf(towel), emptyList(), "А полотенце зачем?")
        assertEquals(listOf(1L), got.map { it.id })
    }

    @Test
    fun `вопрос не о записи — её нет`() {
        assertTrue(DreamDoor.pick(listOf(towel), emptyList(), "Какая погода?").isEmpty())
    }

    @Test
    fun `скрытая, отвергнутая и уже найденная не выходят`() {
        val q = "А полотенце зачем?"
        assertTrue(DreamDoor.pick(listOf(towel.copy(reviewPending = true)), emptyList(), q).isEmpty())
        assertTrue(DreamDoor.pick(listOf(towel.copy(rejectedAt = 1L)), emptyList(), q).isEmpty())
        assertTrue(DreamDoor.pick(listOf(towel), listOf(towel), q).isEmpty())
    }

    @Test
    fun `не больше двух за ход`() {
        val many = (1L..5L).map { rec(it, "Полотенце номер $it") }
        assertEquals(DreamDoor.MAX_PER_TURN, DreamDoor.pick(many, emptyList(), "Где полотенце?").size)
    }

    @Test
    fun `дверь открыта три хода и закрывается сама`() {
        DreamDoor.afterTurn(listOf(1L))
        repeat(DreamDoor.DOOR_TURNS - 1) {
            DreamDoor.afterTurn(emptyList())
            assertEquals(listOf(1L), DreamDoor.openIds())
        }
        DreamDoor.afterTurn(emptyList())
        assertTrue(DreamDoor.openIds().isEmpty())
    }

    @Test
    fun `повторно принесённая открывается на полный срок`() {
        DreamDoor.afterTurn(listOf(1L))
        DreamDoor.afterTurn(emptyList())
        DreamDoor.afterTurn(listOf(1L))
        repeat(DreamDoor.DOOR_TURNS - 1) { DreamDoor.afterTurn(emptyList()) }
        assertEquals(listOf(1L), DreamDoor.openIds())
    }
}
