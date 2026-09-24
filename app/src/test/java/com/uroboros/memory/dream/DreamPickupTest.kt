package com.uroboros.memory.dream

import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подхват сна владельцем.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: слово, бывшее в прошлом вопросе,
 * молчащий сон и реплика без значимых слов подхватом не считаются.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что совпадение слов значит интерес владельца. Это
 * сверяется на устройстве по строке «в этом ходе подхвачен сон …».
 */
class DreamPickupTest {

    private val towel = Sticker(id = 1, content = "Полотенце висит в ванной")
    private val sunset = Sticker(id = 2, content = "Закат был алым")

    private val dream = Dream(nightAt = 10, recordIds = "1,2", kind = DreamWeaver.Kind.TIME.name)

    private fun picked(reply: String, question: String, records: List<Sticker?> = listOf(towel, sunset)) =
        DreamPickup.pickedUp(listOf(dream to records), question, reply)

    @Test
    fun `слово из сна, которого не было в вопросе, — подхват`() {
        val got = picked(reply = "А полотенце где?", question = "Какой был закат?")
        assertEquals(listOf(dream), got.map { it.first })
        assertEquals(listOf(towel, sunset), got.single().second)
    }

    @Test
    fun `слово из сна, бывшее в прошлом вопросе, подхватом не считается`() {
        val got = picked(reply = "Закат и правда был красивый", question = "Какой был закат?")
        assertTrue("владелец продолжает свою тему, а не подхватывает сон", got.isEmpty())
    }

    @Test
    fun `молчащий сон не подхватывается`() {
        val hidden = towel.copy(reviewPending = true)
        assertTrue(picked("А полотенце где?", "Какой был закат?", listOf(hidden, sunset)).isEmpty())
        assertTrue("удалённое звено", picked("А полотенце где?", "Какой был закат?", listOf(null, sunset)).isEmpty())
    }

    @Test
    fun `реплика без общих слов ничего не подхватывает`() {
        assertTrue(picked(reply = "Спасибо, понятно", question = "Какой был закат?").isEmpty())
        assertTrue(picked(reply = "", question = "").isEmpty())
    }

    @Test
    fun `поданное на ходе забирается один раз`() {
        DreamPickup.afterTurn("Какой был закат?", listOf(dream))
        assertEquals(listOf(dream), DreamPickup.take()?.dreams)
        assertNull("вторая реплика того же хода проверки не получает", DreamPickup.take())
    }

    @Test
    fun `ход без поданных снов стирает прежнее, закрытый разговор тоже`() {
        DreamPickup.afterTurn("Какой был закат?", listOf(dream))
        DreamPickup.afterTurn("А дальше?", emptyList())
        assertNull(DreamPickup.take())

        DreamPickup.afterTurn("Какой был закат?", listOf(dream))
        DreamPickup.forget()
        assertNull(DreamPickup.take())
    }
}
