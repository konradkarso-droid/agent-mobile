package com.uroboros.memory.dream

import com.uroboros.memory.Layer
import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import com.uroboros.memory.dream.DreamWeaver.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дешёвый сон: кто снится, из чего, и чем ограничено.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: скрытая запись, вопрос и отчёт агента
 * не снятся; записи с общими словами не снятся; длинная запись не служит мостом;
 * сюжет из связей одного признака не строится. Каждая закрепляет границу,
 * которую легко «починить» в сторону затопления снами.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что сны выходят стоящими. Это видно только на
 * утреннем экране, на живой памяти.
 */
class DreamWeaverTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun rec(
        id: Long,
        text: String,
        at: Long = id * hour,
        source: String = SourceKind.USER_STATED.name,
        hidden: Boolean = false,
        layer: Layer = Layer.GREEN,
    ) = Sticker(
        id = id, content = text, createdAt = at, source = source, reviewPending = hidden,
        layer = layer.name,
    )

    private fun DreamWeaver.Night.of(kind: Kind) = dreams.filter { it.kind == kind }

    // --- Кто не снится ---

    @Test
    fun `скрытая запись, вопрос и отчёт агента не снятся и сосчитаны`() {
        val night = DreamWeaver.weave(
            listOf(
                rec(1, "Алеет солнце на закате", at = 0),
                rec(2, "Бетономешалка мешает бетон", at = minute, hidden = true),
                rec(3, "Какого ты пола?", at = 2 * minute),
                rec(4, "[TOTE] Успех за 2 итераций", at = 3 * minute, source = SourceKind.AGENT_INFERRED.name),
            )
        )
        assertTrue("снам не с кем связаться", night.dreams.isEmpty())
        assertEquals(1, night.dreamers)
        assertEquals(1, night.skippedHidden)
        assertEquals(1, night.skippedQuestions)
        assertEquals(1, night.skippedAgentReports)
    }

    // --- По времени ---

    @Test
    fun `рядом по времени и без общих слов — сон по времени`() {
        val night = DreamWeaver.weave(
            listOf(
                rec(1, "Чёрный чай заваривают кипятком", at = 0),
                rec(2, "Бетономешалка мешает бетон", at = 5 * minute),
            )
        )
        assertEquals(listOf(DreamWeaver.Woven(listOf(1L, 2L), Kind.TIME)), night.dreams)
    }

    @Test
    fun `далеко по времени — не снится`() {
        val night = DreamWeaver.weave(
            listOf(
                rec(1, "Чёрный чай заваривают кипятком", at = 0),
                rec(2, "Бетономешалка мешает бетон", at = DreamWeaver.TIME_WINDOW_MS + 1),
            )
        )
        assertTrue(night.dreams.isEmpty())
    }

    @Test
    fun `общие слова — не сон, их и так сводит поиск`() {
        val night = DreamWeaver.weave(
            listOf(
                rec(1, "Мой любимый инструмент — рубанок", at = 0),
                rec(2, "У рубанка деревянная колодка", at = minute),
            )
        )
        assertTrue("«рубанок» и «рубанка» сходятся по первым буквам", night.dreams.isEmpty())
    }

    // --- Мост ---

    @Test
    fun `две записи без общих слов сходятся через короткую третью`() {
        val night = DreamWeaver.weave(
            listOf(
                rec(1, "Алеет солнце на закате"),
                rec(2, "Меркурий светит белым на закате"),
                rec(3, "Меркурий светит красным на восходе"),
            )
        )
        assertEquals(listOf(DreamWeaver.Woven(listOf(1L, 2L, 3L), Kind.BRIDGE)), night.of(Kind.BRIDGE))
    }

    @Test
    fun `длинная запись мостом не служит`() {
        val long = "Мастерская станок подача шпиндель станина кожух закате восходе светит Меркурий"
        val night = DreamWeaver.weave(
            listOf(
                rec(1, "Алеет солнце на закате"),
                rec(2, long),
                rec(3, "Меркурий светит красным на восходе"),
            )
        )
        assertTrue(
            "длинный текст делит слово почти с кем угодно — это совпадение, а не мост",
            night.of(Kind.BRIDGE).isEmpty()
        )
    }

    // --- Сюжеты ---

    @Test
    fun `сюжет сплетает время и мост`() {
        val night = DreamWeaver.weave(
            listOf(
                rec(1, "Алеет солнце на закате", at = 0),
                rec(2, "ставка семь с половиной", at = minute),
                rec(3, "Меркурий светит белым на закате", at = 5 * hour),
                rec(4, "Меркурий светит красным на восходе", at = 9 * hour),
            )
        )
        // 2 —время— 1 —мост через 3— 4
        assertEquals(listOf(DreamWeaver.Woven(listOf(2L, 1L, 4L), Kind.PLOT)), night.of(Kind.PLOT))
    }

    @Test
    fun `цепочка из связей одного признака сюжетом не становится`() {
        // Пачка фраз, набранных подряд: все связаны со всеми по времени.
        val night = DreamWeaver.weave(
            listOf(
                rec(1, "Всегда носи с собой полотенце", at = 0),
                rec(2, "Написание тестовых фраз ощущается странно", at = minute),
                rec(3, "Четвёртое предложение будет длиннее", at = 2 * minute),
            )
        )
        assertEquals(3, night.of(Kind.TIME).size)
        assertTrue("перестановки одной пачки — не сюжеты", night.of(Kind.PLOT).isEmpty())
    }

    // --- Потолок ---

    @Test
    fun `потолок режет и сообщает, что резал`() {
        // Двенадцать несвязанных слов подряд: связей по времени больше потолка.
        val words = listOf(
            "бетон", "ветер", "горох", "дождь", "ежевика", "жираф",
            "замок", "искра", "капля", "лимон", "магнит", "облако",
        )
        val records = words.mapIndexed { i, w -> rec(i + 1L, w, at = (i + 1) * minute) }
        val night = DreamWeaver.weave(records)
        assertEquals(DreamWeaver.MAX_DREAMS_PER_NIGHT, night.dreams.size)
        assertTrue(night.ceilingHit)
    }

    @Test
    fun `под потолком — потолок не сработал`() {
        val night = DreamWeaver.weave(
            listOf(
                rec(1, "Чёрный чай заваривают кипятком", at = 0),
                rec(2, "Бетономешалка мешает бетон", at = minute),
            )
        )
        assertFalse(night.ceilingHit)
    }

    // --- Слои ---

    @Test
    fun `холодное и архив снятся и сосчитаны по самому холодному звену`() {
        val night = DreamWeaver.weave(
            listOf(
                // 1–2: горячие, рядом по времени.
                rec(1, "Чёрный чай заваривают кипятком", at = 0),
                rec(2, "Бетономешалка мешает бетон", at = minute),
                // 3–4: холодная и горячая, рядом по времени.
                rec(3, "Алеет солнце на закате", at = hour, layer = Layer.BLUE),
                rec(4, "Жираф ест листья акации", at = hour + minute),
                // 5–6: архивная и холодная, рядом по времени.
                rec(6, "Облако похоже на кита", at = 3 * hour, layer = Layer.BLUE),
                rec(5, "Магнит тянет железо", at = 3 * hour + minute, layer = Layer.PURPLE),
            )
        )
        assertEquals(3, night.dreams.size)
        assertEquals(6, night.dreamers)
        assertEquals(2, night.dreamersCold)
        assertEquals(1, night.dreamersArchive)
        assertEquals("сон с холодной и горячей", 1, night.coldDreams)
        assertEquals("сон с архивной и холодной — архивный, не холодный", 1, night.archiveDreams)
    }

    @Test
    fun `сон слоя не меняет`() {
        val cold = rec(1, "Алеет солнце на закате", at = 0, layer = Layer.BLUE)
        val hot = rec(2, "Бетономешалка мешает бетон", at = minute)
        val night = DreamWeaver.weave(listOf(cold, hot))
        assertEquals(1, night.dreams.size)
        assertEquals(Layer.BLUE.name, cold.layer)
        assertEquals(Layer.GREEN.name, hot.layer)
    }

    // --- Итог ночи ---

    @Test
    fun `ночь без снов оставляет итог с нулём, а не ничего`() {
        val night = DreamWeaver.weave(listOf(rec(1, "Алеет солнце на закате")))
        val row = DreamNight.of(nightAt = 42L, night = night)
        assertEquals(42L, row.nightAt)
        assertEquals(0, row.dreams)
        assertEquals(1, row.dreamers)
        assertFalse(row.ceilingHit)
    }

    @Test
    fun `итог ночи переносит все числа прохода`() {
        val words = listOf(
            "бетон", "ветер", "горох", "дождь", "ежевика", "жираф",
            "замок", "искра", "капля", "лимон", "магнит", "облако",
        )
        val records = words.mapIndexed { i, w ->
            rec(i + 1L, w, at = (i + 1) * minute, layer = if (i == 0) Layer.BLUE else Layer.GREEN)
        } + rec(20, "Какого ты пола?") + rec(21, "скрыта", hidden = true) +
            rec(22, "[TOTE] отчёт", source = SourceKind.AGENT_INFERRED.name)
        val night = DreamWeaver.weave(records)
        val row = DreamNight.of(1L, night)
        assertEquals(night.dreams.size, row.dreams)
        assertEquals(night.dreamers, row.dreamers)
        assertEquals(night.dreamersCold, row.dreamersCold)
        assertEquals(night.dreamersArchive, row.dreamersArchive)
        assertEquals(night.coldDreams, row.coldDreams)
        assertEquals(night.archiveDreams, row.archiveDreams)
        assertEquals(1, row.skippedHidden)
        assertEquals(1, row.skippedQuestions)
        assertEquals(1, row.skippedAgentReports)
        assertTrue(row.ceilingHit)
        assertEquals(1, row.dreamersCold)
        assertTrue("холодная запись в чём-то снилась", row.coldDreams > 0)
    }
}
