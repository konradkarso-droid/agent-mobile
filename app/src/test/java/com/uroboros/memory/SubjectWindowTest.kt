package com.uroboros.memory

import com.uroboros.memory.nav.TestVerbs
import com.uroboros.util.TextFold
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Отбор целиком: запись, совпавшая с вопросом только словами без предмета, в ответ не идёт (nav.SubjectWords). */
class SubjectWindowTest {

    @Before
    fun verbs() = TestVerbs.install()

    private fun sticker(id: Long, content: String) = Sticker(
        id = id, content = content, contentFolded = TextFold.fold(content),
        createdAt = id * 1_000L, lastAccessedAt = id * 1_000L, layer = Layer.GREEN.name,
    )

    private fun dao(vararg records: Sticker) = FakeStickerDao().apply {
        onGetExpired = { emptyList() }
        onGetRanked = { layers, limit -> records.filter { it.layer in layers }.sortedByDescending { it.createdAt }.take(limit) }
        onSearchAnyCase = { q, _, limit, layers ->
            val excluded = searchCalls.last().excluded
            records.filter { it.layer in layers && it.content !in excluded && it.contentFolded!!.contains(q) }
                .sortedByDescending { it.createdAt }.take(limit)
        }
        onSearchHiddenAnyCase = { _, _, _, _ -> emptyList() }
        onCountInLayer = { layer -> records.count { it.layer == layer } }
        onIdentityWall = { emptyList() }
        onCountInLayers = { layers -> records.count { it.layer in layers } }
        onCountContaining = { q, layers -> records.count { it.layer in layers && it.contentFolded!!.contains(q) } }
    }

    @Test
    fun `общее слово без предмета запись не приводит`() = runBlocking {
        val filler = sticker(1, "Хорошо. Правильно делаешь.")
        val result = HourglassMemory(dao(filler), lemmas = { TestVerbs.lemmas })
            .getContextWithSummary(RetrievalPurpose.ANSWERING_USER, "Хорошо, попробую", 5)
        assertTrue(result.stickers.isEmpty())
        assertTrue(result.summary, result.summary.contains("отсеяно без предмета 1"))
    }

    @Test
    fun `та же запись приходит, когда словаря нет — правило молчит`() = runBlocking {
        val filler = sticker(1, "Хорошо. Правильно делаешь.")
        val result = HourglassMemory(dao(filler), lemmas = { null })
            .getContextWithSummary(RetrievalPurpose.ANSWERING_USER, "Хорошо, попробую", 5)
        assertEquals(listOf(1L), result.stickers.map { it.id })
    }

    @Test
    fun `запись с предметом вопроса приходит, и общее слово ей не мешает`() = runBlocking {
        val towel = sticker(1, "Хорошо бы всегда носить с собой полотенце.")
        val filler = sticker(2, "Хорошо. Правильно делаешь.")
        val result = HourglassMemory(dao(towel, filler), lemmas = { TestVerbs.lemmas })
            .getContextWithSummary(RetrievalPurpose.ANSWERING_USER, "Хорошо, а полотенце?", 5)
        assertEquals(listOf(1L), result.stickers.map { it.id })
    }

    @Test
    fun `просмотр записей правило не трогает`() = runBlocking {
        val filler = sticker(1, "Хорошо. Правильно делаешь.")
        val result = HourglassMemory(dao(filler), lemmas = { TestVerbs.lemmas })
            .getContextWithSummary(RetrievalPurpose.BROWSING, "Хорошо, попробую", 5)
        assertEquals(listOf(1L), result.stickers.map { it.id })
    }
}
