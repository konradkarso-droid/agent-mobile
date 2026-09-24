package com.uroboros.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Круг дольменов в пути чтения: окна ищут каждое в своих слоях, красный не
 * возвращается ни одним, холод не греется за то, что всплыл.
 *
 * ГРАНИЦА та же, что у [LadderSearchTest]: подделка DAO не воспроизводит
 * SQLite, поиск здесь — простое «содержит подстроку». Доказуемо, что делает
 * круг с тем, что база отдала, а не что база найдёт на устройстве.
 */
class CircleSelectionTest {

    private fun sticker(id: Long, content: String, layer: Layer) = Sticker(
        id = id,
        content = content,
        createdAt = id * 1_000L,
        lastAccessedAt = id * 1_000L,
        layer = layer.name,
    )

    private fun dao(vararg records: Sticker) = FakeStickerDao().apply {
        onGetExpired = { emptyList() }
        onGetRanked = { layers, limit ->
            records.filter { it.layer in layers && !it.reviewPending }.sortedByDescending { it.createdAt }.take(limit)
        }
        onSearchAnyCase = { q, qCap, limit ->
            records.filter { !it.reviewPending && (it.content.contains(q) || it.content.contains(qCap)) }
                .sortedByDescending { it.createdAt }.take(limit)
        }
        onSearchHiddenAnyCase = { _, _, _ -> emptyList() }
        onCountInLayer = { layer -> records.count { it.layer == layer } }
    }

    // --- Красный: проверка на молчание ---

    @Test
    fun `красный не возвращается ни одним окном`() = runBlocking {
        val red = sticker(1, "рубанок колодка берёза", Layer.RED)
        val blue = sticker(2, "старый лес у реки", Layer.BLUE)
        val dao = dao(red, blue)
        val memory = HourglassMemory(dao)

        // Вопрос и тема со словами, холод не пуст — все три окна ищут.
        val byQuestion = memory.getContextWithSummary(
            RetrievalPurpose.ANSWERING_USER, "рубанок", 5, recentQuestions = listOf("колодка берёза"),
        )
        assertTrue(byQuestion.stickers.none { it.id == red.id })
        assertTrue(byQuestion.circle.contains("красный: 1 — в ответ не идут"))

        // У вопроса слов нет — холод ищет словами темы; красного нет и там.
        val byTheme = memory.getContextWithSummary(
            RetrievalPurpose.ANSWERING_USER, "да, ну", 5, recentQuestions = listOf("рубанок колодка берёза"),
        )
        assertTrue(byTheme.stickers.none { it.id == red.id })
        assertTrue(dao.layerUpdates.none { it.id == red.id })
    }

    // --- Холод ---

    @Test
    fun `холод не греется при отборе, горячее греется как было`() = runBlocking {
        val green = sticker(1, "рубанок из дуба", Layer.GREEN)
        val blue = sticker(2, "рубанок из клёна", Layer.BLUE)
        val dao = dao(green, blue)
        val result = HourglassMemory(dao).getContextWithSummary(RetrievalPurpose.ANSWERING_USER, "рубанок", 5)

        assertEquals(listOf(green.id, blue.id), result.stickers.map { it.id })
        assertEquals(setOf(blue.id), result.coldIds)
        assertTrue("холод слоя не меняет", dao.layerUpdates.none { it.id == blue.id })
        assertEquals(Layer.BLUE.name, result.stickers.single { it.id == blue.id }.layer)
        assertTrue("горячее греется", dao.layerUpdates.any { it.id == green.id && it.layer == Layer.YELLOW.name })
        assertTrue(result.circle.contains("холод — нашёл 1, мест 1"))
    }

    @Test
    fun `в холоде синий раньше фиолетового`() = runBlocking {
        // Фиолетовая свежее — по рангу стояла бы первой.
        val blue = sticker(1, "рубанок из клёна", Layer.BLUE)
        val purple = sticker(2, "рубанок из липы", Layer.PURPLE)
        val result = HourglassMemory(dao(blue, purple)).getContextWithSummary(
            RetrievalPurpose.ANSWERING_USER, "рубанок", 5,
        )
        assertEquals(listOf(blue.id, purple.id), result.stickers.map { it.id })
    }

    @Test
    fun `пустой холод не ищется и называется пустым`() = runBlocking {
        val dao = dao(sticker(1, "рубанок из дуба", Layer.GREEN))
        val result = HourglassMemory(dao).getContextWithSummary(RetrievalPurpose.ANSWERING_USER, "рубанок", 5)
        assertTrue(result.circle.contains("холод — слой пуст"))
        assertTrue(result.coldIds.isEmpty())
        // Искало только окно вопроса: одна лестница, без второго прохода холодом.
        assertEquals(listOf("рубанок", "рубан", "руба"), dao.searchedPrefixes)
    }

    // --- Тема ---

    /** Главный случай: у реплики нет значимых слов, тема ясна из прошлых ходов. */
    @Test
    fun `короткая реплика находит записи по теме`() = runBlocking {
        val onTopic = sticker(1, "рубанок с колодкой из берёзы", Layer.GREEN)
        val oneWord = sticker(2, "рубанок сломан", Layer.GREEN)
        val dao = dao(onTopic, oneWord)
        val result = HourglassMemory(dao).getContextWithSummary(
            RetrievalPurpose.ANSWERING_USER, "да, ну", 5,
            recentQuestions = listOf("расскажи про рубанок и колодку"),
        )
        assertEquals(listOf(onTopic.id), result.stickers.map { it.id })
        assertTrue(result.circle.contains("вопрос — нечем искать (нет слов)"))
        assertTrue(result.circle.contains("тема (расскажи, рубанок, колодку) — нашёл 1, мест 1"))
        // Тема с вопросом не совпадала — пользы ей не засчитывается.
        assertTrue(dao.touchedUserMatch.isEmpty())
    }

    @Test
    fun `польза засчитывается окну вопроса, но не теме`() = runBlocking {
        val byQuestion = sticker(1, "рубанок из дуба", Layer.GREEN)
        val byTheme = sticker(2, "колодка из берёзы", Layer.GREEN)
        val dao = dao(byQuestion, byTheme)
        val result = HourglassMemory(dao).getContextWithSummary(
            RetrievalPurpose.ANSWERING_USER, "рубанок", 5,
            recentQuestions = listOf("колодка берёзы"),
        )
        assertEquals(listOf(byQuestion.id, byTheme.id), result.stickers.map { it.id })
        assertEquals(listOf(byQuestion.id), dao.touchedUserMatch)
    }

    @Test
    fun `просмотр идёт без темы`() = runBlocking {
        val result = HourglassMemory(dao(sticker(1, "колодка из берёзы", Layer.GREEN))).getContextWithSummary(
            RetrievalPurpose.BROWSING, "рубанок", 20,
        )
        assertTrue(result.stickers.isEmpty())
        assertTrue(result.circle.contains("тема — нечем искать (нет слов)"))
    }

    @Test
    fun `без вопроса круг не собирается`() = runBlocking {
        val result = HourglassMemory(dao(sticker(1, "колодка", Layer.GREEN))).getContextWithSummary(
            RetrievalPurpose.BROWSING, null, 20,
        )
        assertEquals(DolmenCircle.NOT_GATHERED, result.circle)
        assertFalse(result.stickers.isEmpty())
    }
}
