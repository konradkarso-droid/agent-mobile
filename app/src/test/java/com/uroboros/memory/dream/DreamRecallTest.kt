package com.uroboros.memory.dream

import com.uroboros.memory.FakeStickerDao
import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ассоциация к ответу: отбор снов и принесённые ими записи.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: сон со скрытым звеном ничего не
 * приносит; сон, не приносящий новой записи, не отбирается; к ответу без
 * записей не приносится ничего; отбор не трогает записи ничем, кроме чтения.
 * Каждая закрепляет границу так, что случайная «починка» её сломает заметно.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что модель пользуется принесённым. Это видно
 * только в её ответах на устройстве.
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

    @Test
    fun `отбор приносит записи, которых нет в ответе, по порядку и без повторов`() {
        val rows = listOf(dream(8, 10), dream(8, 9, 10, kind = DreamWeaver.Kind.PLOT), dream(9, 11))
        val offer = pick(rows, live, setOf(8, 9))
        // (8,10) и (9,11) короче сюжета и идут первыми; сюжет нового не несёт.
        assertEquals(listOf(10L, 11L), offer.brought.map { it.id })
        assertEquals(listOf(listOf(10L), listOf(11L)), offer.picked.map { p -> p.brought.map { it.id } })
    }

    @Test
    fun `записи ответа не приносятся заново`() {
        val offer = pick(listOf(dream(8, 9, 10, kind = DreamWeaver.Kind.PLOT)), live, setOf(8, 10))
        assertEquals(listOf(9L), offer.brought.map { it.id })
    }

    @Test
    fun `ничего не приносится, если все звенья уже в ответе`() {
        val offer = pick(listOf(dream(8, 9), dream(9, 10)), live, setOf(8, 9, 10))
        assertTrue(offer.brought.isEmpty())
    }

    @Test
    fun `при скрытом звене ничего не приносится`() {
        val records = live.map { if (it.id == 9L) record(9, hidden = true) else it }
        val offer = pick(listOf(dream(8, 9, 10, kind = DreamWeaver.Kind.PLOT)), records, setOf(8))
        assertTrue(offer.brought.isEmpty())
    }

    @Test
    fun `принесённое ограничено квотой`() {
        val rows = listOf(dream(1, 2), dream(1, 3), dream(1, 4))
        assertEquals(listOf(2L), pick(rows, live, setOf(1), quota = 1).brought.map { it.id })
    }

    @Test
    fun `прибор при каждом исходе`() {
        val none = DreamRecall.Offer(nightAt = null, silence = DreamRecall.Silence.NO_NIGHT)
        assertEquals("Ассоциации: ночей ещё не было", DreamRecall.meter(none, 0))

        val empty = DreamRecall.Offer(nightAt = NIGHT, silence = DreamRecall.Silence.NO_RECORDS)
        val emptyText = DreamRecall.meter(empty, 0)
        assertTrue(emptyText, emptyText.startsWith("Ассоциации: к ответу нет записей"))

        val nothing = pick(listOf(dream(9, 10)), live, setOf(8))
        val text = DreamRecall.meter(nothing, 0)
        assertTrue(text, text.startsWith("Ассоциации: к этим записям не подошёл ни один сон"))
        assertTrue(text, text.contains("ночь 20.09"))

        val records = live.map { if (it.id == 9L) record(9, hidden = true) else it }
        val silenced = DreamRecall.meter(pick(listOf(dream(8, 9)), records, setOf(8)), 0)
        assertTrue(silenced, silenced.contains("молчат из-за скрытых записей: 1"))

        val found = DreamRecall.meter(pick(listOf(dream(8, 10)), live, setOf(8)), 0)
        assertTrue(found, found.startsWith("Ассоциации: снов подходило 1 · записей принесено 1 · ночь"))
    }

    @Test
    fun `прибор отделяет принесённое от лежащего в ленте`() {
        val offer = pick(listOf(dream(8, 9), dream(8, 10)), live, setOf(8))
        val text = DreamRecall.meter(offer, alreadyInRibbon = 1)
        assertTrue(text, text.contains("снов подходило 2 · записей принесено 1 · уже в ленте 1"))
    }

    // --- Прибор: одна запись во многих снах ---

    @Test
    fun `чаще всех в снах ночи — считает`() {
        val rows = listOf(dream(8, 9), dream(8, 10), dream(8, 11), dream(9, 10))
        val hub = DreamRecall.hub(rows, live.associateBy { it.id })
        assertEquals(8L, hub?.record?.id)
        assertEquals(3, hub?.dreams)
    }

    @Test
    fun `чаще всех в снах ночи — молчащий сон не считается`() {
        val records = live.map { if (it.id == 11L) record(11, hidden = true) else it }
        // Без молчащих снов (9,11) и (10,11) лидер — 8 в двух снах, а не 11 в трёх.
        val rows = listOf(dream(8, 9), dream(8, 10), dream(8, 11), dream(9, 11), dream(10, 11))
        val hub = DreamRecall.hub(rows, records.associateBy { it.id })
        assertEquals(8L, hub?.record?.id)
        assertEquals(2, hub?.dreams)
    }

    @Test
    fun `чаще всех в снах ночи — пустая ночь без хвоста`() {
        assertEquals(null, DreamRecall.hub(emptyList(), live.associateBy { it.id }))
        val records = live.map { if (it.id == 9L) record(9, hidden = true) else it }
        assertEquals(null, DreamRecall.hub(listOf(dream(8, 9)), records.associateBy { it.id }))

        val offer = pick(listOf(dream(8, 10)), live, setOf(8))
        assertFalse(DreamRecall.meter(offer, 0).contains("чаще всех"))
    }

    @Test
    fun `чаще всех в снах ночи — хвост строки прибора`() {
        val hub = DreamRecall.Hub(record(8).copy(content = "Всегда носи с собой полотенце."), 3)
        val offer = pick(listOf(dream(8, 10)), live, setOf(8)).copy(hub = hub)
        assertTrue(
            DreamRecall.meter(offer, 0).endsWith(" · чаще всех в снах ночи: «Всегда носи с собой полотенце.» — в 3 снах")
        )
        assertTrue(DreamRecall.meter(offer.copy(hub = hub.copy(dreams = 1)), 0).endsWith("— в 1 сне"))
        assertTrue(DreamRecall.meter(offer.copy(hub = hub.copy(dreams = 11)), 0).endsWith("— в 11 снах"))
        val silent = DreamRecall.Offer(nightAt = NIGHT, silence = DreamRecall.Silence.NO_RECORDS, hub = hub)
        assertTrue(DreamRecall.meter(silent, 0).contains("чаще всех в снах ночи"))
    }

    @Test
    fun `строка сна в старых ходах узнаётся как сон, запись с теми же словами — нет`() {
        val dreamLabel = ProvenanceLabels.DREAM_FOR_MODEL
        val userLabel = ProvenanceLabels.forModel(SourceKind.USER_STATED.name)
        assertTrue(DreamRecall.isDreamLine("$dreamLabel, что рядом с «а» было: «б»."))
        assertTrue(DreamRecall.isDreamLine("$dreamLabel: «а» → «б»."))
        assertFalse(DreamRecall.isDreamLine("$userLabel: «$dreamLabel, что нет»."))
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
        override suspend fun lastUnpromptedLeaders(limit: Int): List<Long?> = error("отбор не читает ряд лидеров")
        override suspend fun setSelfLineOutcome(nightAt: Long, outcome: String): Int = error("отбор не пишет итог шага")
        override suspend fun lastSelfLineOutcome(): String? = error("отбор не читает итог шага")
        override suspend fun setMirrorOutcome(nightAt: Long, outcome: String): Int = error("отбор не пишет итог зеркала")
        override suspend fun lastMirrorOutcome(): String? = error("отбор не читает итог зеркала")
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
        assertEquals(listOf(9L), offer.brought.map { it.id })
        assertEquals(8L, offer.hub?.record?.id)
        assertTrue(served.marked.isEmpty())
    }

    @Test
    fun `к ответу без записей ничего не приносится`() {
        val stickers = FakeStickerDao().apply { onGetAll = { live } }
        val recall = DreamRecall(FakeDreamDao(night(), listOf(dream(8, 9))), FakeServedDao(), stickers)
        val offer = runBlocking { recall.offer(emptySet()) }
        assertEquals(DreamRecall.Silence.NO_RECORDS, offer.silence)
        assertTrue(offer.brought.isEmpty())
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
