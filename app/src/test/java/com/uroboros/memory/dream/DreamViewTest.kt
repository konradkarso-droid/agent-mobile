package com.uroboros.memory.dream

import com.uroboros.memory.FakeStickerDao
import com.uroboros.memory.Sticker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слова раздела «Сны» на экране «Показать».
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: ночь без снов, ночь, чьи сны все до
 * одного замолчали, и число замолчавших. Каждая из них закрепляет разницу между
 * «показывать нечего» и «показ сломался» — на экране это одно и то же пустое
 * место, если его не подписать.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что скрытая запись действительно не доходит до
 * экрана. Отбор молчащих снов работает с базой и виден только на устройстве;
 * здесь проверяются слова, которыми он отчитывается.
 */
class DreamViewTest {

    private fun night(
        dreams: Int = 0,
        dreamers: Int = 0,
        dreamersCold: Int = 0,
        dreamersArchive: Int = 0,
        skippedHidden: Int = 0,
        skippedQuestions: Int = 0,
        skippedAgentReports: Int = 0,
        ceilingHit: Boolean = false,
    ) = DreamNight(
        nightAt = 1_789_923_154_023L,
        dreams = dreams,
        dreamers = dreamers,
        dreamersCold = dreamersCold,
        dreamersArchive = dreamersArchive,
        coldDreams = 0,
        archiveDreams = 0,
        skippedHidden = skippedHidden,
        skippedQuestions = skippedQuestions,
        skippedAgentReports = skippedAgentReports,
        ceilingHit = ceilingHit,
    )

    private fun shown(vararg texts: String, kind: DreamWeaver.Kind = DreamWeaver.Kind.TIME) =
        DreamView.Shown(kind.name, texts.toList())

    @Test
    fun `ночь всегда названа временем`() {
        val text = DreamView.render(night(dreams = 0, dreamers = 4), emptyList(), 0, 0)
        assertTrue(text, text.contains("Ночь: 20.09"))
    }

    @Test
    fun `ночь без снов говорит об этом прямо`() {
        val text = DreamView.render(night(dreams = 0, dreamers = 4), emptyList(), 0, 0)
        assertTrue(text, text.contains("ничего не приснилось"))
        assertFalse(text, text.contains("молчат"))
    }

    @Test
    fun `сны были, но все замолчали — это не то же самое, что снов не было`() {
        val text = DreamView.render(night(dreams = 3, dreamers = 5), emptyList(), silent = 3, dreamtRecords = 4)
        assertTrue(text, text.contains("Не показано снов: 3"))
        assertTrue(text, text.contains("все сны этой ночи молчат"))
        assertFalse(text, text.contains("ничего не приснилось"))
    }

    @Test
    fun `часть снов замолчала — остальные показаны и число названо`() {
        val text = DreamView.render(
            night(dreams = 2, dreamers = 5),
            listOf(shown("Алеет солнце", "Меркурий на закате")),
            silent = 1,
            dreamtRecords = 4,
        )
        assertTrue(text, text.contains("Не показано снов: 1"))
        assertTrue(text, text.contains("Алеет солнце → Меркурий на закате"))
    }

    @Test
    fun `участвовавшие, но не приснившиеся сосчитаны`() {
        val text = DreamView.render(
            night(dreams = 1, dreamers = 32),
            listOf(shown("раз", "два")),
            silent = 0,
            dreamtRecords = 23,
        )
        assertTrue(text, text.contains("Участвовали, но не приснились: 9"))
    }

    @Test
    fun `нули не печатаются`() {
        val text = DreamView.render(
            night(dreams = 1, dreamers = 2),
            listOf(shown("раз", "два")),
            silent = 0,
            dreamtRecords = 2,
        )
        assertFalse(text, text.contains("Участвовали"))
        assertFalse(text, text.contains("Не снились вовсе"))
        assertFalse(text, text.contains("Не показано"))
        assertFalse(text, text.contains("Потолок"))
        assertFalse(text, text.contains("холодных"))
    }

    @Test
    fun `виды снов названы по-русски`() {
        val text = DreamView.render(
            night(dreams = 3, dreamers = 6),
            listOf(
                shown("а", "б", kind = DreamWeaver.Kind.BRIDGE),
                shown("в", "г", kind = DreamWeaver.Kind.TIME),
                shown("д", "е", kind = DreamWeaver.Kind.PLOT),
            ),
            silent = 0,
            dreamtRecords = 6,
        )
        assertTrue(text, text.contains("мост: а → б"))
        assertTrue(text, text.contains("по времени: в → г"))
        assertTrue(text, text.contains("сюжет: д → е"))
    }

    @Test
    fun `длинная запись обрезается`() {
        val long = "с".repeat(200)
        val text = DreamView.render(
            night(dreams = 1, dreamers = 2),
            listOf(shown(long, "коротко")),
            silent = 0,
            dreamtRecords = 2,
        )
        assertTrue(text, text.contains("…"))
        assertFalse("целиком длинная запись на экран не идёт", text.contains(long))
        val line = text.lines().first { it.startsWith("по времени") }
        assertEquals(
            "по времени: ".length + DreamView.MAX_TEXT + " → коротко".length,
            line.length,
        )
    }

    // --- Отбор молчащих снов ---

    /**
     * Подставной DreamDao: ночь и её сны задаёт тест. Записи не пишутся вовсе —
     * показ только читает, и запись здесь означала бы, что подделка умеет
     * больше проверяемого пути.
     */
    private class FakeDreamDao(
        private val night: DreamNight?,
        private val rows: List<Dream>,
    ) : DreamDao {
        override suspend fun insertAll(dreams: List<Dream>) = error("показ не пишет")
        override suspend fun insertNight(night: DreamNight) = error("показ не пишет")
        override suspend fun lastNight(): DreamNight? = night
        override suspend fun ofNight(nightAt: Long): List<Dream> = rows
    }

    private fun record(id: Long, hidden: Boolean = false, rejected: Boolean = false) = Sticker(
        id = id,
        content = "запись $id",
        reviewPending = hidden,
        rejectedAt = if (rejected) 1L else null,
    )

    private fun view(rows: List<Dream>, records: List<Sticker>, night: DreamNight?): String {
        val stickers = FakeStickerDao().apply { onGetAll = { records } }
        return runBlocking { DreamView(FakeDreamDao(night, rows), stickers).section() }
    }

    private fun dream(vararg ids: Long) =
        Dream(nightAt = 1L, recordIds = ids.joinToString(","), kind = DreamWeaver.Kind.TIME.name)

    @Test
    fun `прохода не было — так и сказано`() {
        val text = view(emptyList(), emptyList(), null)
        assertEquals(DreamView.NO_NIGHT, text)
    }

    @Test
    fun `сон со скрытым звеном не показывается, но сосчитан`() {
        val text = view(
            rows = listOf(dream(1, 2), dream(1, 3)),
            records = listOf(record(1), record(2, hidden = true), record(3)),
            night = night(dreams = 2, dreamers = 3),
        )
        assertFalse("скрытая запись просочилась через сон", text.contains("запись 2"))
        assertTrue(text, text.contains("запись 1 → запись 3"))
        assertTrue(text, text.contains("Не показано снов: 1"))
    }

    @Test
    fun `сон с отвергнутым звеном тоже молчит`() {
        val text = view(
            rows = listOf(dream(1, 2)),
            records = listOf(record(1), record(2, rejected = true)),
            night = night(dreams = 1, dreamers = 2),
        )
        assertFalse(text, text.contains("запись 2"))
        assertTrue(text, text.contains("Не показано снов: 1"))
    }

    @Test
    fun `сон с исчезнувшей записью молчит целиком`() {
        val text = view(
            rows = listOf(dream(1, 9)),
            records = listOf(record(1)),
            night = night(dreams = 1, dreamers = 2),
        )
        assertFalse("половина цепочки не показывается", text.contains("запись 1 →"))
        assertTrue(text, text.contains("Не показано снов: 1"))
    }

    @Test
    fun `целые сны показываются как есть`() {
        val text = view(
            rows = listOf(dream(1, 2)),
            records = listOf(record(1), record(2)),
            night = night(dreams = 1, dreamers = 2),
        )
        assertTrue(text, text.contains("запись 1 → запись 2"))
        assertFalse(text, text.contains("Не показано"))
    }

    // --- Схлопывание вариантов ---

    private fun record(id: Long, text: String) = Sticker(id = id, content = text)

    private fun dreamOf(vararg ids: Long) =
        Dream(nightAt = 1L, recordIds = ids.joinToString(","), kind = DreamWeaver.Kind.TIME.name)

    private val sunset = record(1, "Алеет солнце на закате")
    private val white = record(2, "Меркурий светит белым на закате")
    private val scarlet = record(3, "Меркурий светит алым на закате")
    private val towel = record(4, "Всегда носи с собой полотенце")

    @Test
    fun `сны, отличающиеся записью из одной семьи, показываются одной строкой`() {
        val text = view(
            rows = listOf(dreamOf(1, 2), dreamOf(1, 3)),
            records = listOf(sunset, white, scarlet),
            night = night(dreams = 2, dreamers = 3),
        )
        assertTrue(text, text.contains("Снов: 2 · различных: 1"))
        assertTrue(text, text.contains("Алеет солнце на закате → Меркурий светит белым на закате"))
        assertTrue("вариант должен быть назван текстом", text.contains("то же с: Меркурий светит алым на закате"))
    }

    @Test
    fun `сны с непохожими записями не склеиваются`() {
        val text = view(
            rows = listOf(dreamOf(1, 2), dreamOf(1, 4)),
            records = listOf(sunset, white, towel),
            night = night(dreams = 2, dreamers = 3),
        )
        assertFalse("склеивать разное нельзя", text.contains("то же с"))
        assertFalse(text, text.contains("различных"))
        assertTrue(text, text.contains("Меркурий светит белым на закате"))
        assertTrue(text, text.contains("Всегда носи с собой полотенце"))
    }

    @Test
    fun `сны разного вида не склеиваются, даже если записи одной семьи`() {
        val bridge = Dream(nightAt = 1L, recordIds = "1,3", kind = DreamWeaver.Kind.BRIDGE.name)
        val text = view(
            rows = listOf(dreamOf(1, 2), bridge),
            records = listOf(sunset, white, scarlet),
            night = night(dreams = 2, dreamers = 3),
        )
        assertFalse(text, text.contains("то же с"))
        assertTrue(text, text.contains("по времени: "))
        assertTrue(text, text.contains("мост: "))
    }

    @Test
    fun `строка «различных» молчит, когда схлопывать нечего`() {
        val text = view(
            rows = listOf(dreamOf(1, 2)),
            records = listOf(sunset, white),
            night = night(dreams = 1, dreamers = 2),
        )
        assertFalse(text, text.contains("различных"))
    }
}
