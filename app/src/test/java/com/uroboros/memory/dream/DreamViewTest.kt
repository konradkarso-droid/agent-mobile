package com.uroboros.memory.dream

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
}
