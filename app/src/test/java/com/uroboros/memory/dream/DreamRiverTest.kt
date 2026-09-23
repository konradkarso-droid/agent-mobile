package com.uroboros.memory.dream

import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Река.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРЕДЕЛЫ ПРОТИВ ЭХА: без новой записи продолжения нет,
 * продолжений на приток не больше двух, длинная цепочка не растёт, приток со
 * скрытым звеном молчит.
 */
class DreamRiverTest {

    private val byId = (1L..9L).associateWith { Sticker(id = it, content = "запись $it") }

    private fun tributary(ids: String, at: Long = 1L) =
        Dream(nightAt = 0, recordIds = ids, kind = "TIME", lastRecalledAt = at)

    private fun woven(vararg ids: Long) = DreamWeaver.Woven(ids.toList(), DreamWeaver.Kind.TIME)

    @Test
    fun `приток продолжается сном с общей записью`() {
        val river = DreamRiver.weave(listOf(tributary("1,2")), listOf(woven(2, 5)), byId)
        assertEquals(listOf(listOf(1L, 2L, 5L)), river)
    }

    @Test
    fun `без общей записи или без новой — продолжения нет`() {
        assertTrue(DreamRiver.weave(listOf(tributary("1,2")), listOf(woven(3, 4)), byId).isEmpty())
        assertTrue(DreamRiver.weave(listOf(tributary("1,2")), listOf(woven(2, 1)), byId).isEmpty())
    }

    @Test
    fun `на приток не больше двух продолжений`() {
        val tonight = listOf(woven(2, 5), woven(2, 6), woven(2, 7))
        assertEquals(DreamRiver.PER_TRIBUTARY, DreamRiver.weave(listOf(tributary("1,2")), tonight, byId).size)
    }

    @Test
    fun `длинная цепочка перестаёт расти`() {
        val long = tributary("1,2,3,4,5")
        assertTrue(DreamRiver.weave(listOf(long), listOf(woven(5, 6)), byId).isEmpty())
    }

    @Test
    fun `приток со скрытым звеном молчит`() {
        val hidden = byId + (1L to Sticker(id = 1, content = "запись 1", reviewPending = true))
        assertTrue(DreamRiver.weave(listOf(tributary("1,2")), listOf(woven(2, 5)), hidden).isEmpty())
    }

    @Test
    fun `сон реки не повторяет сон этой ночи`() {
        val tonight = listOf(woven(2, 5), woven(1, 2, 5))
        assertTrue(DreamRiver.weave(listOf(tributary("1,2")), tonight, byId).isEmpty())
    }
}
