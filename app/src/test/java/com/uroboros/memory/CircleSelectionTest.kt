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
        // Как SQL: сперва слой, слова и исключённые тексты, потом самые новые,
        // потом лимит. Исключённые подделка отдаёт через записанный вызов — см.
        // KDoc onSearchAnyCase.
        onSearchAnyCase = { q, qCap, limit, layers ->
            val excluded = searchCalls.last().excluded
            records.filter {
                !it.reviewPending && it.layer in layers && it.content !in excluded &&
                    (it.content.contains(q) || it.content.contains(qCap))
            }.sortedByDescending { it.createdAt }.take(limit)
        }
        onSearchHiddenAnyCase = { _, _, _, _ -> emptyList() }
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

    /**
     * Холод не делит кандидатов с горячими. Лимит базы режет самые новые
     * первыми, а холодная запись самая старая: если бы слой отсекался после
     * лимита, двадцать пять горячих с тем же словом вытеснили бы её всегда.
     */
    @Test
    fun `холодная запись находится и при двадцати с лишним горячих с тем же словом`() = runBlocking {
        val blue = sticker(1, "рубанок старый", Layer.BLUE)
        val hot = (2L..26L).map { sticker(it, "рубанок $it", Layer.GREEN) }
        val dao = dao(blue, *hot.toTypedArray())
        val result = HourglassMemory(dao).getContextWithSummary(RetrievalPurpose.ANSWERING_USER, "рубанок", 5)

        assertEquals(setOf(blue.id), result.coldIds)
        assertTrue(blue.id in result.stickers.map { it.id })
        assertTrue(result.circle.contains("холод — нашёл 1, мест 1"))
    }

    /** Каждое окно спрашивает базу только о своих слоях, и красного среди них нет. */
    @Test
    fun `поиск в базе спрашивает слои окна, без красного`() = runBlocking {
        val dao = dao(sticker(1, "рубанок из клёна", Layer.BLUE), sticker(2, "рубанок из дуба", Layer.GREEN))
        HourglassMemory(dao).getContextWithSummary(
            RetrievalPurpose.ANSWERING_USER, "рубанок", 5, recentQuestions = listOf("колодка берёза"),
        )
        val hot = listOf(Layer.ORANGE.name, Layer.YELLOW.name, Layer.GREEN.name)
        val cold = listOf(Layer.BLUE.name, Layer.PURPLE.name)
        val asked = (dao.searchCalls + dao.hiddenSearchCalls).map { it.layers }.toSet()
        assertEquals(setOf(hot, cold), asked)
        assertTrue(asked.none { Layer.RED.name in it })
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

    // --- Реплики, которые модель видит в этом запросе ---

    @Test
    fun `реплика ленты и текущая реплика не попадают ни в одно окно`() = runBlocking {
        val ribbonReply = sticker(3, "Мой рубанок с деревянной колодкой", Layer.GREEN)
        val currentReply = sticker(2, "Рубанок у меня старый", Layer.GREEN)
        val live = sticker(1, "рубанок с колодкой из берёзы", Layer.GREEN)
        val coldReply = sticker(4, "Мой рубанок с деревянной колодкой", Layer.BLUE)
        val dao = dao(live, currentReply, ribbonReply, coldReply)
        val excluded = listOf("Мой рубанок с деревянной колодкой", "Рубанок у меня старый")
        val result = HourglassMemory(dao).getContextWithSummary(
            RetrievalPurpose.ANSWERING_USER, "Рубанок у меня старый", 5,
            recentQuestions = listOf("Мой рубанок с деревянной колодкой"),
            excludedTexts = excluded,
        )
        assertEquals(listOf(live.id), result.stickers.map { it.id })
        // Список дошёл до запроса каждого окна, а не только до одного.
        assertTrue(dao.searchCalls.isNotEmpty())
        assertTrue(dao.searchCalls.all { it.excluded == excluded })
        assertTrue(result.circle.contains("реплик ленты в исключении 2"))
    }

    /** Запись того же текста другой формы запрос не ловит — ловит второй забор. */
    @Test
    fun `запись реплики с другой заглавной и знаком в конце тоже исключена`() = runBlocking {
        val oldForm = sticker(2, "мой рубанок с деревянной колодкой.", Layer.GREEN)
        val live = sticker(1, "рубанок с колодкой из берёзы", Layer.GREEN)
        val result = HourglassMemory(dao(live, oldForm)).getContextWithSummary(
            RetrievalPurpose.ANSWERING_USER, "Мой рубанок с деревянной колодкой", 5,
            excludedTexts = listOf("Мой рубанок с деревянной колодкой"),
        )
        assertEquals(listOf(live.id), result.stickers.map { it.id })
    }

    /** Просмотр ленты не знает: человек ищет запись по своим словам и находит её. */
    @Test
    fun `просмотр с пустым списком находит ту же запись`() = runBlocking {
        val reply = sticker(1, "Мой рубанок с деревянной колодкой", Layer.GREEN)
        val result = HourglassMemory(dao(reply)).getContextWithSummary(
            RetrievalPurpose.BROWSING, "Мой рубанок с деревянной колодкой", 20,
        )
        assertEquals(listOf(reply.id), result.stickers.map { it.id })
        assertFalse(result.circle.contains("в исключении"))
    }

    /**
     * Исключение внутри поиска, а не после: двадцать с лишним самых новых
     * записей по слову — реплики ленты, и лимит базы, отсекай их после него,
     * вытеснил бы живую запись; окно сказало бы «пусто».
     */
    @Test
    fun `реплики ленты не вытесняют лимитом старую живую запись`() = runBlocking {
        val live = sticker(1, "рубанок с колодкой из берёзы", Layer.GREEN)
        val replies = (2L..26L).map { sticker(it, "рубанок $it мой", Layer.GREEN) }
        val dao = dao(live, *replies.toTypedArray())
        val result = HourglassMemory(dao).getContextWithSummary(
            RetrievalPurpose.ANSWERING_USER, "рубанок", 5,
            recentQuestions = replies.map { it.content },
            excludedTexts = replies.map { it.content } + "рубанок",
        )
        assertEquals(listOf(live.id), result.stickers.map { it.id })
        assertTrue(result.circle.contains("вопрос — нашёл 1, мест 1"))
        assertTrue(result.circle.contains("реплик ленты в исключении 26"))
    }
}
