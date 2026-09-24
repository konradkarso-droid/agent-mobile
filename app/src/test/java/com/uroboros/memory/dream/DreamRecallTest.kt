package com.uroboros.memory.dream

import com.uroboros.memory.FakeStickerDao
import com.uroboros.memory.Sticker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отбор снов к ответу.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: сон со скрытым звеном не подаётся; сон,
 * не приносящий новой записи, не подаётся; отбор не трогает записи ничем, кроме
 * чтения. Каждая закрепляет границу так, что случайная «починка» её сломает
 * заметно.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что модель говорит о поданном сне «мне снилось».
 * Это видно только в её ответах на устройстве.
 */
class DreamRecallTest {

    private fun record(id: Long, hidden: Boolean = false, rejected: Boolean = false) = Sticker(
        id = id,
        content = "запись $id",
        reviewPending = hidden,
        rejectedAt = if (rejected) 1L else null,
    )

    private fun dream(vararg ids: Long, kind: DreamWeaver.Kind = DreamWeaver.Kind.TIME) =
        Dream(nightAt = NIGHT, recordIds = ids.joinToString(","), kind = kind.name)

    private fun pick(rows: List<Dream>, records: List<Sticker>, answer: Set<Long>, quota: Int = 2) =
        DreamRecall.pick(NIGHT, rows, records.associateBy { it.id }, answer, quota)

    private val live = (1L..11L).map { record(it) }

    @Test
    fun `сон без записей ответа не подаётся`() {
        val offer = pick(listOf(dream(9, 10)), live, setOf(8))
        assertTrue(offer.picked.isEmpty())
        assertEquals(DreamRecall.Silence.NOTHING_FITS, offer.silence)
    }

    @Test
    fun `сон со скрытым звеном молчит, но сосчитан`() {
        val records = live.map { if (it.id == 9L) record(9, hidden = true) else it }
        val offer = pick(listOf(dream(8, 9)), records, setOf(8))
        assertTrue(offer.picked.isEmpty())
        assertEquals(1, offer.silenced)
    }

    @Test
    fun `сон с отвергнутым или исчезнувшим звеном молчит`() {
        val rejected = live.map { if (it.id == 9L) record(9, rejected = true) else it }
        assertTrue(pick(listOf(dream(8, 9)), rejected, setOf(8)).picked.isEmpty())
        val gone = live.filter { it.id != 9L }
        assertTrue(pick(listOf(dream(8, 9)), gone, setOf(8)).picked.isEmpty())
    }

    @Test
    fun `сон целиком из записей ответа не подаётся`() {
        val offer = pick(listOf(dream(8, 9)), live, setOf(8, 9))
        assertTrue(offer.picked.isEmpty())
        assertEquals(0, offer.fitting)
    }

    @Test
    fun `пачка подряд не съедает квоту пересказами`() {
        // Четыре записи, набранные подряд, снятся «все со всеми».
        val rows = listOf(dream(8, 9), dream(8, 10), dream(8, 11), dream(9, 10), dream(9, 11), dream(10, 11))
        val offer = pick(rows, live, setOf(8, 9))
        // (8,9) ничего не приносит; остальные с 8 или 9 приносят 10 или 11.
        assertEquals(4, offer.fitting)
        assertEquals(2, offer.picked.size)
        val brought = offer.picked.flatMap { it.dream.ids() }.filter { it !in setOf(8L, 9L) }.toSet()
        assertEquals(setOf(10L, 11L), brought)
    }

    @Test
    fun `следующий сон обязан принести новое`() {
        val rows = listOf(dream(8, 10), dream(9, 10))
        val offer = pick(rows, live, setOf(8, 9))
        assertEquals(1, offer.picked.size)
    }

    @Test
    fun `квота соблюдается`() {
        val rows = listOf(dream(1, 2), dream(1, 3), dream(1, 4))
        assertEquals(1, pick(rows, live, setOf(1), quota = 1).picked.size)
    }

    @Test
    fun `отбор детерминирован и короткое раньше длинного`() {
        val plot = dream(1, 5, 6, kind = DreamWeaver.Kind.PLOT)
        val time = dream(1, 7)
        val a = pick(listOf(plot, time), live, setOf(1), quota = 1)
        val b = pick(listOf(time, plot), live, setOf(1), quota = 1)
        assertEquals(time, a.picked.single().dream)
        assertEquals(a, b)
    }

    private fun text(id: Long, content: String) = record(id).copy(content = content)

    @Test
    fun `сон не повторяет найденное целиком, а приносит новое`() {
        val records = live.map {
            when (it.id) {
                8L -> text(8, "Всегда носи с собой полотенце.")
                10L -> text(10, "Делать нужно хорошо, а плохо - не делать.")
                11L -> text(11, "Четвёртое предложение будет немного длиннее.")
                else -> it
            }
        }
        val offer = pick(listOf(dream(8, 10), dream(8, 11)), records, setOf(8))
        val lines = DreamRecall.lines(offer.picked, setOf(8))
        assertEquals(1, lines.size)
        assertEquals(2, lines.single().dreams.size)
        assertEquals(
            "Тебе снилось, что рядом с «Всегда носи с собой…» было: " +
                "«Делать нужно хорошо, а плохо - не делать.», " +
                "«Четвёртое предложение будет немного длиннее.».",
            lines.single().text,
        )
        assertFalse(lines.single().text.contains("полотенце"))
    }

    @Test
    fun `короткая запись ответа называется целиком`() {
        val records = live.map { if (it.id == 8L) text(8, "Я работаю по субботам") else it }
        val line = DreamRecall.lines(pick(listOf(dream(8, 9)), records, setOf(8)).picked, setOf(8)).single()
        assertTrue(line.text, line.text.contains("рядом с «Я работаю по субботам» было: «запись 9»."))
    }

    @Test
    fun `разные записи ответа — разные строки`() {
        val offer = pick(listOf(dream(1, 5), dream(2, 6)), live, setOf(1, 2))
        assertEquals(2, DreamRecall.lines(offer.picked, setOf(1, 2)).size)
    }

    @Test
    fun `строка сна узнаётся как сон, запись с теми же словами — нет`() {
        val offer = pick(listOf(dream(8, 9)), live, setOf(8))
        val line = DreamRecall.lines(offer.picked, setOf(8)).single().text
        assertTrue(line, line.startsWith("Тебе снилось"))
        assertTrue(DreamRecall.isDreamLine(line))
        assertTrue(DreamRecall.isDreamLine("Тебе снилось: «а» → «б»."))
        assertFalse(DreamRecall.isDreamLine("Пользователь сказал: «Тебе снилось, что нет»."))
    }

    @Test
    fun `мост называет, через что связалось, и не повторяет найденное`() {
        val bridge = dream(1, 2, 3, kind = DreamWeaver.Kind.BRIDGE)
        val line = DreamRecall.lines(pick(listOf(bridge), live, setOf(1)).picked, setOf(1)).single()
        assertEquals("Тебе снилось, что «запись 1» и «запись 3» связались через «запись 2».", line.text)
    }

    @Test
    fun `прибор говорит при любом исходе`() {
        val none = DreamRecall.Offer(nightAt = null, silence = DreamRecall.Silence.NO_NIGHT)
        assertEquals("Снов: ночей ещё не было", DreamRecall.meter(none, 0))

        val empty = DreamRecall.Offer(nightAt = NIGHT, silence = DreamRecall.Silence.NO_RECORDS)
        assertTrue(DreamRecall.meter(empty, 0).contains("к ответу нет записей"))

        val nothing = pick(listOf(dream(9, 10)), live, setOf(8))
        val text = DreamRecall.meter(nothing, 0)
        assertTrue(text, text.contains("не подошёл ни один") && text.contains("ночь 20.09"))
    }

    @Test
    fun `прибор отделяет поданное от лежащего в ленте`() {
        val offer = pick(listOf(dream(8, 9), dream(8, 10)), live, setOf(8))
        val text = DreamRecall.meter(offer, alreadyInRibbon = 1)
        assertTrue(text, text.contains("подходило 2 · подано 1 · уже в ленте 1"))
    }

    @Test
    fun `прибор называет строки, когда сны легли вместе`() {
        val offer = pick(listOf(dream(8, 9), dream(8, 10)), live, setOf(8))
        val text = DreamRecall.meter(offer, alreadyInRibbon = 0, lineCount = 1)
        assertTrue(text, text.contains("подано 2 · строк 1"))
        assertFalse(DreamRecall.meter(offer, 0, lineCount = 2).contains("строк"))
    }

    // --- Путь с базой: отбор только читает ---

    private class FakeDreamDao(
        private val night: DreamNight?,
        private val rows: List<Dream>,
    ) : DreamDao {
        override suspend fun insertAll(dreams: List<Dream>) = error("отбор не пишет")
        override suspend fun insertNight(night: DreamNight) = error("отбор не пишет")
        override suspend fun lastNight(): DreamNight? = night
        override suspend fun ofNight(nightAt: Long): List<Dream> = rows
        override suspend fun stirredSince(since: Long): List<Dream> = error("отбор не читает пружину")
        override suspend fun lastAskedAt(): Long? = error("отбор не читает выход любопытства")
    }

    private class FakeServedDao : DreamServedDao {
        val marked = mutableListOf<Triple<Long, String, Long>>()
        override suspend fun markServed(nightAt: Long, recordIds: String, at: Long): Int {
            marked += Triple(nightAt, recordIds, at)
            return 1
        }
        override suspend fun markPickedUp(nightAt: Long, recordIds: String, at: Long) =
            error("отбор не отмечает подхват")
        override suspend fun askedAt(nightAt: Long, recordIds: String): Long? =
            error("отбор не читает вопрос")
        override suspend fun markAsked(nightAt: Long, recordIds: String, at: Long): Int =
            error("отбор не спрашивает")
        override suspend fun markAnswered(nightAt: Long, recordIds: String, at: Long) =
            error("отбор не отмечает ответ")
    }

    private fun night() = DreamNight(
        nightAt = NIGHT, dreams = 1, dreamers = 2, dreamersCold = 0, dreamersArchive = 0,
        coldDreams = 0, archiveDreams = 0, skippedHidden = 0, skippedQuestions = 0,
        skippedAgentReports = 0, ceilingHit = false,
    )

    @Test
    fun `отбор читает записи без отметки обращения и ничего не отмечает`() {
        // Подделка записей падает на любом неподготовленном методе: прогрев
        // (touchAccess) или любое другое обращение уронили бы тест с его именем.
        val stickers = FakeStickerDao().apply { onGetAll = { live } }
        val served = FakeServedDao()
        val recall = DreamRecall(FakeDreamDao(night(), listOf(dream(8, 9))), served, stickers)
        val offer = runBlocking { recall.offer(setOf(8)) }
        assertEquals(1, offer.picked.size)
        assertTrue(served.marked.isEmpty())
    }

    @Test
    fun `без ночи база записей не читается вовсе`() {
        val recall = DreamRecall(FakeDreamDao(null, emptyList()), FakeServedDao(), FakeStickerDao())
        assertEquals(DreamRecall.Silence.NO_NIGHT, runBlocking { recall.offer(setOf(8)) }.silence)
    }

    @Test
    fun `отметка ставится ровно на поданные`() {
        val served = FakeServedDao()
        val recall = DreamRecall(FakeDreamDao(night(), emptyList()), served, FakeStickerDao())
        val p = DreamRecall.Picked(dream(8, 9), listOf(record(8), record(9)))
        runBlocking { recall.markServed(listOf(p), at = 5L) }
        assertEquals(listOf(Triple(NIGHT, "8,9", 5L)), served.marked)
    }

    private companion object {
        const val NIGHT = 1_789_923_154_023L
    }
}
