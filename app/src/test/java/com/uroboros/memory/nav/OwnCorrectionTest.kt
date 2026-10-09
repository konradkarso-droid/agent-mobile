package com.uroboros.memory.nav

import com.uroboros.memory.FakeStickerDao
import com.uroboros.memory.HourglassMemory
import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Поправка о себе (OwnCorrection): поздняя фраза собеседника о себе снимает
 * его же прежнюю, с которой спорит; всё прочее не трогается.
 */
class OwnCorrectionTest {

    /** Таблица глаголов — как на телефоне (см. TestVerbs). */
    @Before
    fun verbs() = TestVerbs.install()

    private fun rec(id: Long, text: String, source: SourceKind = SourceKind.USER_STATED, rejected: Boolean = false) =
        Sticker(id = id, content = text, createdAt = id, source = source.name, rejectedAt = if (rejected) 1L else null)

    private fun snimaet(new: String, vararg old: Sticker): List<Long> =
        OwnCorrection.superseded(rec(100, new), old.toList()).map { it.id }

    @Test
    fun `поздняя фраза о себе снимает спорящую прежнюю`() {
        assertEquals(listOf(1L), snimaet("Я не работаю по субботам", rec(1, "Я работаю по субботам")))
        assertEquals(listOf(1L), snimaet("Нет, я живу в Самаре, а не в Казани", rec(1, "Я живу в Казани")))
    }

    // Проверки на молчание.

    @Test
    fun `новый факт без спора ничего не снимает`() {
        assertTrue(snimaet("Я не стоматолог.", rec(1, "Я был у стоматолога, сейчас вот код сочиняю, пишу.")).isEmpty())
    }

    @Test
    fun `факт о мире не снимается`() {
        assertTrue(snimaet("Меркурий не светит алым на закате", rec(1, "Меркурий светит алым на закате")).isEmpty())
    }

    @Test
    fun `слова агента не снимаются и не снимают`() {
        assertTrue(snimaet("Я не работаю по субботам", rec(1, "Я работаю по субботам", SourceKind.AGENT_INFERRED)).isEmpty())
        val agentNew = rec(100, "Я не работаю по субботам", SourceKind.AGENT_INFERRED)
        assertTrue(OwnCorrection.superseded(agentNew, listOf(rec(1, "Я работаю по субботам"))).isEmpty())
    }

    @Test
    fun `обращение к агенту и вопрос - не о себе`() {
        assertFalse(OwnCorrection.aboutSelf("Ты не работаешь по субботам"))
        assertFalse(OwnCorrection.aboutSelf("Я работаю по субботам?"))
        assertTrue(snimaet("Я работаю по субботам?", rec(1, "Я не работаю по субботам")).isEmpty())
    }

    @Test
    fun `уже отвергнутая и сама новая запись не трогаются`() {
        assertTrue(snimaet("Я не работаю по субботам", rec(1, "Я работаю по субботам", rejected = true)).isEmpty())
        assertTrue(snimaet("Я не работаю по субботам", rec(100, "Я работаю по субботам")).isEmpty())
    }

    // Граница (KDoc «ЧЕГО НЕ УМЕЕТ»): ложный спор снимает верную запись.

    @Test
    fun `граница - поправка с повтором факта снимает верную прежнюю`() {
        assertEquals(
            listOf(1L),
            snimaet("Я не стоматолог, я просто был у стоматолога.", rec(1, "Я был у стоматолога.")),
        )
    }

    @Test
    fun `сохранение снимает прежнюю путём «поправлено» и сообщает номер`() = runBlocking {
        val old = rec(1, "Я работаю по субботам")
        val dao = FakeStickerDao().apply {
            onInsert = { 2L }
            onGetExpired = { emptyList() }
            onGetByTagInLayers = { _, _ -> listOf(old) }
            onGetAll = { listOf(old, rec(2, "Я не работаю по субботам")) }
        }
        val outcome = HourglassMemory(dao).saveEventChecked(
            Sticker(content = "Я не работаю по субботам", source = SourceKind.USER_STATED.name),
        )
        assertEquals(HourglassMemory.SaveOutcome.Saved(2L, listOf(1L)), outcome)
        assertEquals(listOf(1L to "CORRECTED"), dao.rejected.map { it.first to it.third })
    }

    @Test
    fun `сбой поиска поправки не срывает сохранение`() = runBlocking {
        val dao = FakeStickerDao().apply {
            onInsert = { 2L }
            onGetExpired = { emptyList() }
            onGetByTagInLayers = { _, _ -> emptyList() }
            onGetAll = { throw IllegalStateException("база недоступна") }
        }
        val outcome = HourglassMemory(dao).saveEventChecked(
            Sticker(content = "Я не работаю по субботам", source = SourceKind.USER_STATED.name),
        )
        assertEquals(HourglassMemory.SaveOutcome.Saved(2L), outcome)
        assertTrue(dao.rejected.isEmpty())
    }
}
