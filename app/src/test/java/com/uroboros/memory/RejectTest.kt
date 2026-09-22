package com.uroboros.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отвержение записи: один запрос, только по названному номеру и с названным
 * путём, и ничего сверх.
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
    fun `отвергается названная запись одним запросом с моментом и путём решения`() = runBlocking {
        val dao = FakeStickerDao()
        val outcome = HourglassMemory(dao).reject(16, RejectPath.MISTAKE, now = 1_000L)
        assertEquals(RejectOutcome.DONE, outcome)
        assertEquals(listOf(Triple(16L, 1_000L, "MISTAKE")), dao.rejected)
    }

    @Test
    fun `путь записывается тот, что назван, а не какой-то один`() = runBlocking {
        val dao = FakeStickerDao()
        val memory = HourglassMemory(dao)
        memory.reject(1, RejectPath.QUEUE, now = 1L)
        memory.reject(2, RejectPath.DISPUTE, now = 2L)
        memory.reject(3, RejectPath.MISTAKE, now = 3L)
        assertEquals(listOf("QUEUE", "DISPUTE", "MISTAKE"), dao.rejected.map { it.third })
    }

    @Test
    fun `ничего не изменилось — это не успех и не сбой`() = runBlocking {
        // Ноль изменённых записей: номера нет или запись уже отвергнута.
        // Сказать «отвергнута» здесь значило бы соврать, сказать «память не
        // ответила» — послать человека чинить исправную базу.
        val dao = FakeStickerDao().apply { rejectChanges = 0 }
        assertEquals(RejectOutcome.UNCHANGED, HourglassMemory(dao).reject(16, RejectPath.MISTAKE))
    }

    @Test
    fun `отвержение ничего другого не трогает`() = runBlocking {
        val dao = FakeStickerDao()
        HourglassMemory(dao).reject(16, RejectPath.MISTAKE)
        assertTrue("бит ставит сам запрос отвержения, отдельного скрытия нет", dao.reviewPendingSet.isEmpty())
        assertTrue("слои не трогаются", dao.layerUpdates.isEmpty())
        assertTrue("отвержение — не обращение", dao.touchedAccess.isEmpty())
        assertTrue(dao.inserted.isEmpty())
    }

    @Test
    fun `новая запись не отвергнута и пути у неё нет`() {
        val sticker = Sticker(content = "У паука восемь ног")
        assertNull(sticker.rejectedAt)
        assertNull(sticker.rejectedVia)
    }

    @Test
    fun `сбой базы — отказ, а не молчаливый успех`() = runBlocking {
        val dao = object : StickerDao by FakeStickerDao() {
            override suspend fun reject(id: Long, at: Long, via: String): Int =
                throw IllegalStateException("база недоступна")
        }
        assertEquals(RejectOutcome.FAILED, HourglassMemory(dao).reject(16, RejectPath.MISTAKE))
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
