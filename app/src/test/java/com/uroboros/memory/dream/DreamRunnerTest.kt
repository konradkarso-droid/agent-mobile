package com.uroboros.memory.dream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слова, которыми ночь отчитывается человеку.
 *
 * ГЛАВНОЕ ЗДЕСЬ: ночь без снов должна выглядеть как ночь без снов, а не как
 * отсутствие ночи, и строки с нулями не должны засорять отчёт — иначе их
 * перестают читать ровно тогда, когда в них появляется число.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что сам проход записал ночь в базу. Это видно только
 * на устройстве и в выгрузке.
 */
class DreamRunnerTest {

    private fun row(
        dreams: Int = 0,
        dreamers: Int = 0,
        dreamersCold: Int = 0,
        dreamersArchive: Int = 0,
        coldDreams: Int = 0,
        archiveDreams: Int = 0,
        skippedHidden: Int = 0,
        skippedQuestions: Int = 0,
        skippedAgentReports: Int = 0,
        ceilingHit: Boolean = false,
    ) = DreamNight(
        nightAt = 1L,
        dreams = dreams,
        dreamers = dreamers,
        dreamersCold = dreamersCold,
        dreamersArchive = dreamersArchive,
        coldDreams = coldDreams,
        archiveDreams = archiveDreams,
        skippedHidden = skippedHidden,
        skippedQuestions = skippedQuestions,
        skippedAgentReports = skippedAgentReports,
        ceilingHit = ceilingHit,
    )

    @Test
    fun `ночь без снов говорит, что ничего не приснилось`() {
        val text = DreamRunner.describe(row(dreams = 0, dreamers = 3))
        assertTrue(text, text.contains("ничего не приснилось"))
        assertTrue(text, text.contains("Снилось записей: 3"))
    }

    @Test
    fun `нули не печатаются`() {
        val text = DreamRunner.describe(row(dreams = 2, dreamers = 5))
        assertFalse(text, text.contains("Не снились"))
        assertFalse(text, text.contains("холодных"))
        assertFalse(text, text.contains("архив"))
        assertFalse(text, text.contains("Потолок"))
    }

    @Test
    fun `пропущенные и слои печатаются, когда они есть`() {
        val text = DreamRunner.describe(
            row(
                dreams = 7, dreamers = 40, dreamersCold = 4, dreamersArchive = 1,
                coldDreams = 2, archiveDreams = 1,
                skippedHidden = 3, skippedQuestions = 23, ceilingHit = true,
            )
        )
        assertTrue(text, text.contains("из них холодных 4"))
        assertTrue(text, text.contains("из архива 1"))
        assertTrue(text, text.contains("скрытых 3"))
        assertTrue(text, text.contains("вопросов 23"))
        assertFalse("отчётов агента не было", text.contains("отчётов агента"))
        assertTrue(text, text.contains("Снов со старым: 3"))
        assertTrue(text, text.contains("Потолок"))
    }

    @Test
    fun `срыв сна называет ошибку и говорит, что ночь пропущена`() {
        val text = DreamRunner.describeFailure(IllegalStateException("база закрыта"))
        assertTrue(text, text.contains("IllegalStateException"))
        assertTrue(text, text.contains("база закрыта"))
        assertTrue(text, text.contains("пропущена"))
    }
}
