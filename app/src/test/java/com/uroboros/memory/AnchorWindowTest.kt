package com.uroboros.memory

import com.uroboros.memory.nav.TestVerbs
import com.uroboros.util.TextFold
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Окно якорей в пути чтения (nav.Anchors, HourglassMemory.searchByAnchors):
 * запись с именем или предметом вопроса садится в окно вопроса первой, даже
 * когда доли слов вопроса у неё нет; без якорей отбор прежний.
 *
 * ГРАНИЦА та же, что у [CircleSelectionTest]: подделка DAO не воспроизводит
 * SQLite, поиск здесь — «содержит подстроку». Словарь — настоящий файл
 * приложения (TestVerbs.lemmas), подаётся памяти явно: в тестах словарь
 * приложения (nav.LemmaHolder) не загружен.
 */
class AnchorWindowTest {

    @Before
    fun verbs() = TestVerbs.install()

    private fun sticker(id: Long, content: String, layer: Layer = Layer.GREEN) = Sticker(
        id = id,
        content = content,
        contentFolded = TextFold.fold(content),
        createdAt = id * 1_000L,
        lastAccessedAt = id * 1_000L,
        layer = layer.name,
    )

    /**
     * Подделка базы над [records]. [honorLayers] false — база отдаёт записи
     * любого слоя: так проверяется, что заборы слоя стоят в самом отборе.
     * Счёт для редкости слова подготовлен, только если [rarity]: без него
     * обращение к счёту — ошибка теста (проверка, что счёт не спрашивался).
     */
    private fun dao(vararg records: Sticker, honorLayers: Boolean = true, rarity: Boolean = true) = FakeStickerDao().apply {
        fun inLayers(s: Sticker, layers: List<String>) = !honorLayers || s.layer in layers
        onGetExpired = { emptyList() }
        onGetRanked = { layers, limit ->
            records.filter { it.layer in layers && !it.reviewPending }.sortedByDescending { it.createdAt }.take(limit)
        }
        onSearchAnyCase = { q, _, limit, layers ->
            val excluded = searchCalls.last().excluded
            records.filter { !it.reviewPending && inLayers(it, layers) && it.content !in excluded && it.contentFolded!!.contains(q) }
                .sortedByDescending { it.createdAt }.take(limit)
        }
        onSearchHiddenAnyCase = { _, _, _, _ -> emptyList() }
        onCountInLayer = { layer -> records.count { it.layer == layer } }
        onIdentityWall = { emptyList() }
        if (rarity) {
            onCountInLayers = { layers -> records.count { !it.reviewPending && it.layer in layers } }
            onCountContaining = { q, layers -> records.count { !it.reviewPending && it.layer in layers && it.contentFolded!!.contains(q) } }
        }
    }

    private fun withDictionary(dao: FakeStickerDao) = HourglassMemory(dao, lemmas = { TestVerbs.lemmas })
    private fun withoutDictionary(dao: FakeStickerDao) = HourglassMemory(dao, lemmas = { null })

    private val longQuestion =
        "Да, как раз про Клода и Praxis. Твои первые разговоры с другими. Именно после попытки их " +
            "вспомнить, мы выяснили, что у тебя в памяти туман."

    // --- Срабатывает ---

    @Test
    fun `запись с именем садится первой, хотя доли слов вопроса у неё нет`() = runBlocking {
        val claude = sticker(1, "Клод сказал, что тоже не знает, как устроен изнутри.")
        val noise = sticker(2, "Первые разговоры с другими было трудно вспомнить.")

        val with = withDictionary(dao(claude, noise)).getContextWithSummary(RetrievalPurpose.ANSWERING_USER, longQuestion, 5)
        assertEquals(claude.id, with.stickers.first().id)
        assertTrue(with.circle, with.circle.contains("якоря (клода — имя, praxis — имя, туман) — нашли 1, мест 1"))

        // Без словаря Клод не якорь, и окно вопроса запись не берёт: одно слово из шести.
        val without = withoutDictionary(dao(claude, noise)).getContextWithSummary(RetrievalPurpose.ANSWERING_USER, longQuestion, 5)
        assertFalse(without.stickers.any { it.id == claude.id })
    }

    @Test
    fun `имя важнее предмета`() = runBlocking {
        val fog = sticker(1, "туман над рекой")
        val claude = sticker(2, "Клод любит длинные разговоры")
        val result = withDictionary(dao(fog, claude))
            .getContextWithSummary(RetrievalPurpose.ANSWERING_USER, "Помнишь, что Клод говорил про туман?", 5)
        assertEquals(listOf(claude.id, fog.id), result.stickers.take(2).map { it.id })
    }

    @Test
    fun `среди записей с якорем выше та, где редкое слово вопроса`() = runBlocking {
        // «зеленое» есть ещё в пяти записях, «деревянное» — только здесь. Без
        // редкости первой была бы короткая запись.
        val green = sticker(1, "Клод: зеленое")
        val wooden = sticker(2, "Клод: деревянное")
        val fields = (3L..7L).map { sticker(it, "зеленое поле $it") }
        val result = withDictionary(dao(green, wooden, *fields.toTypedArray()))
            .getContextWithSummary(RetrievalPurpose.ANSWERING_USER, "Что Клод говорил про зеленое и деревянное?", 5)
        assertEquals(listOf(wooden.id, green.id), result.stickers.take(2).map { it.id })
    }

    // --- Молчит ---

    @Test
    fun `без якорей отбор тот же, что без словаря, и счёт редкости не спрашивается`() = runBlocking {
        val records = arrayOf(
            sticker(1, "разговор о памяти шёл долго"),
            sticker(2, "наш разговор про память"),
            sticker(3, "рубанок из дуба"),
        )
        val query = "Помнишь наш разговор о памяти?"
        val daoWith = dao(*records, rarity = false)
        val daoWithout = dao(*records, rarity = false)
        val with = withDictionary(daoWith).getContextWithSummary(RetrievalPurpose.ANSWERING_USER, query, 5)
        val without = withoutDictionary(daoWithout).getContextWithSummary(RetrievalPurpose.ANSWERING_USER, query, 5)

        assertEquals(without.stickers.map { it.id }, with.stickers.map { it.id })
        assertEquals(daoWithout.searchedPrefixes, daoWith.searchedPrefixes)
        assertTrue(with.circle, with.circle.startsWith("Круг: якоря: нет · вопрос —"))
        assertTrue(without.circle, without.circle.contains("якоря: нет (словарь не загружен"))
    }

    @Test
    fun `окно якорей не берёт красного, реплик ленты и записей из одних вопросов`() = runBlocking {
        val red = sticker(1, "Клод — мой собеседник", Layer.RED)
        val ribbon = sticker(2, "Клод говорил про туман и реку")
        val onlyQuestion = sticker(3, "А Клод кто?")
        val good = sticker(4, "Клод работает с текстами")
        val result = withDictionary(dao(red, ribbon, onlyQuestion, good, honorLayers = false)).getContextWithSummary(
            RetrievalPurpose.ANSWERING_USER, "Что говорил Клод?", 5, excludedTexts = listOf(ribbon.content),
        )
        val ids = result.stickers.map { it.id }
        assertTrue(good.id in ids)
        assertFalse("красный", red.id in ids)
        assertFalse("реплика ленты", ribbon.id in ids)
        assertFalse("запись из одних вопросов", onlyQuestion.id in ids)
        assertTrue(result.circle, result.circle.contains("якоря (клод — имя) — нашли 1, мест 1"))
    }

    @Test
    fun `якорь в середине чужого слова записи не находит`() = runBlocking {
        val inside = sticker(1, "перерубанный ствол лежал у дороги")
        val result = withDictionary(dao(inside))
            .getContextWithSummary(RetrievalPurpose.ANSWERING_USER, "Что случилось с моим рубанком?", 5)
        assertTrue(result.circle, result.circle.contains("якоря (рубанком) — нашли 0, мест 0"))
    }
}
