package com.uroboros.memory.dream

import com.uroboros.memory.FakeStickerDao
import com.uroboros.memory.Sticker
import kotlinx.coroutines.runBlocking
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

    /**
     * Подделка отметок: спрошен ли сон, задаёт тест; записанные отметки
     * копятся порознь, чтобы видеть, куда ушла реплика.
     */
    private class FakeServedDao(private val asked: Map<String, Long>) : DreamServedDao {
        val pickedUp = mutableListOf<String>()
        val answered = mutableListOf<String>()
        override suspend fun markServed(nightAt: Long, recordIds: String, at: Long): Int = error("подхват не подаёт")
        override suspend fun markPickedUp(nightAt: Long, recordIds: String, at: Long) {
            pickedUp += recordIds
        }
        override suspend fun askedAt(nightAt: Long, recordIds: String): Long? = asked[recordIds]
        override suspend fun markAsked(nightAt: Long, recordIds: String, at: Long): Int = error("подхват не спрашивает")
        override suspend fun markAnswered(nightAt: Long, recordIds: String, at: Long) {
            answered += recordIds
        }
    }

    private fun mark(asked: Map<String, Long>): Pair<DreamPickupMarker.Caught, FakeServedDao> {
        val served = FakeServedDao(asked)
        val stickers = FakeStickerDao().apply { onGetById = { id -> listOf(towel, sunset).find { it.id == id } } }
        // Снимок сна взят при подаче, до вопроса: askedAt в нём пуст.
        val previous = DreamPickup.Previous("Какой был закат?", listOf(dream))
        val caught = runBlocking { DreamPickupMarker(served, stickers).pickUp(previous, "А полотенце где?", now = 50) }
        return caught to served
    }

    @Test
    fun `одинаковая реплика даёт подхват у неспрошенного сна и ответ у спрошенного`() {
        val (plain, plainDao) = mark(asked = emptyMap())
        assertEquals(listOf(dream), plain.pickedUp.map { it.first })
        assertTrue(plain.answered.isEmpty())
        assertEquals(listOf("1,2"), plainDao.pickedUp)
        assertTrue(plainDao.answered.isEmpty())

        val (asked, askedDao) = mark(asked = mapOf("1,2" to 40L))
        assertTrue("спрошенный сон подхватом не считается", asked.pickedUp.isEmpty())
        assertEquals(listOf(dream), asked.answered.map { it.first })
        assertTrue(askedDao.pickedUp.isEmpty())
        assertEquals(listOf("1,2"), askedDao.answered)
    }
}
