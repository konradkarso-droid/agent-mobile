package com.uroboros.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отвержение записи: один запрос, только по названному номеру, и ничего сверх.
 *
 * ГЛАВНАЯ ПРОВЕРКА — на молчание: отвержение не трогает ни очередь другим
 * путём, ни доступ, ни слои. Всё, что должно случиться с отвергнутой записью,
 * случается одним запросом StickerDao.reject.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ: что SQL очереди действительно исключает
 * отвергнутые, а приём и сброс их не выпускают. Подделка отвечает то, что
 * положил тест; это проверяется на устройстве — отвергнутая запись уходит из
 * списка очереди, число «на проверке» убывает, «отвергнуто» растёт.
 */
class RejectTest {

    @Test
    fun `отвергается названная запись одним запросом с моментом решения`() = runBlocking {
        val dao = FakeStickerDao()
        val ok = HourglassMemory(dao).reject(16, now = 1_000L)
        assertTrue(ok)
        assertEquals(listOf(16L to 1_000L), dao.rejected)
    }

    @Test
    fun `отвержение ничего другого не трогает`() = runBlocking {
        val dao = FakeStickerDao()
        HourglassMemory(dao).reject(16)
        assertTrue("бит ставит сам запрос отвержения, отдельного скрытия нет", dao.reviewPendingSet.isEmpty())
        assertTrue("слои не трогаются", dao.layerUpdates.isEmpty())
        assertTrue("отвержение — не обращение", dao.touchedAccess.isEmpty())
        assertTrue(dao.inserted.isEmpty())
    }

    @Test
    fun `новая запись не отвергнута`() {
        assertNull(Sticker(content = "У паука восемь ног").rejectedAt)
    }

    @Test
    fun `сбой базы — отказ, а не молчаливый успех`() = runBlocking {
        val dao = object : StickerDao by FakeStickerDao() {
            override suspend fun reject(id: Long, at: Long) = throw IllegalStateException("база недоступна")
        }
        assertFalse(HourglassMemory(dao).reject(16))
    }

    @Test
    fun `канарейка называет отвергнутые рядом с очередью`() {
        val text = MemoryCanary(FakeStickerDao()).format(
            MemorySnapshot(
                takenAt = 0, total = 74, expired = 0, byLayer = emptyMap(),
                pendingReview = 1, rejected = 2, oldestExpiredAt = null, nextExpiryAt = null,
                withoutExpiry = 0, userMatchesTotal = 0, withUserMatches = 0, maxUserMatches = 0,
            )
        )
        assertTrue(text, text.contains("На проверке:") && text.contains("1 · отвергнуто 2"))
    }
}
