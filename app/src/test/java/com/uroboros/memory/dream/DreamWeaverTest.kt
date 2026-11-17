package com.uroboros.memory.dream

import com.uroboros.memory.Layer
import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import com.uroboros.memory.dream.DreamWeaver.Kind
import com.uroboros.memory.nav.TestVerbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * Плетение без сюжетов: здесь проверяются простые сны и потолок, словарь
     * им не нужен. Сюжеты — в блоке «Сюжеты» и в DreamPlotTest.
     */
    private fun weave(records: List<Sticker>, recent: Set<List<Long>> = emptySet(), lastDreamt: Map<Long, Long> = emptyMap()) =
        DreamWeaver.weave(records, recent, lastDreamt, plotWords = null)

    // --- Кто не снится ---

    @Test
    fun `скрытая запись, вопрос и отчёт агента не снятся и сосчитаны`() {
        val night = weave(
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

    @Test
    fun `просьба не снится и сосчитана отдельно от вопросов`() {
        // Из живой памяти: короткая просьба служила мостом между всем подряд.
        val night = weave(
            listOf(
                rec(1, "Алеет солнце на закате", at = 0),
                rec(2, "Расскажи про правило", at = minute),
                rec(3, "Какого ты пола?", at = 2 * minute),
            )
        )
        assertTrue("снам не с кем связаться", night.dreams.isEmpty())
        assertEquals(1, night.dreamers)
        assertEquals(1, night.skippedQuestions)
        assertEquals(1, night.skippedRequests)
        assertEquals(1, DreamNight.of(1L, night).skippedRequests)
    }

    @Test
    fun `запись из одного обычая не снится и сосчитана отдельно`() {
        // Из живой памяти: сон связал «Понял, спасибо» с «Доброй тебе ночи»,
        // и ассоциация принесла «спасибо» к ответу.
        val night = weave(
            listOf(
                rec(1, "Алеет солнце на закате", at = 0),
                rec(2, "Доброй тебе ночи.", at = minute),
                rec(3, "Понял, спасибо.", at = 2 * minute),
            )
        )
        assertTrue("снам не с кем связаться", night.dreams.isEmpty())
        assertEquals(1, night.dreamers)
        assertEquals(2, night.skippedSocial)
        assertEquals(0, night.skippedRequests)
        assertEquals(2, DreamNight.of(1L, night).skippedSocial)
    }

    // --- По времени ---

    @Test
    fun `рядом по времени и без общих слов — сон по времени`() {
        val night = weave(
            listOf(
                rec(1, "Чёрный чай заваривают кипятком", at = 0),
                rec(2, "Бетономешалка мешает бетон", at = 5 * minute),
            )
        )
        assertEquals(listOf(DreamWeaver.Woven(listOf(1L, 2L), Kind.TIME)), night.dreams)
    }

    @Test
    fun `далеко по времени — не снится`() {
        val night = weave(
            listOf(
                rec(1, "Чёрный чай заваривают кипятком", at = 0),
                rec(2, "Бетономешалка мешает бетон", at = DreamWeaver.TIME_WINDOW_MS + 1),
            )
        )
        assertTrue(night.dreams.isEmpty())
    }

    @Test
    fun `общие слова — не сон, их и так сводит поиск`() {
        val night = weave(
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
        val night = weave(
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
        val night = weave(
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

    private val plotWords by lazy { DreamPlot.Words.of(TestVerbs.lemmas, listOf("Александр"))!! }

    /** Три записи о Клоде в разные дни и центр: из них складывается сюжет (DreamPlotTest). */
    private fun claude() = listOf(
        rec(1, "Продолжаем с Клодом работу, сегодня насыщенный день.", at = 0),
        rec(2, "Кодом занимается другой агент - Клод.", at = 10 * hour),
        rec(3, "Я передавал тебе слова Клода.", at = 20 * hour),
        rec(4, "Разобрались с Клодом, почему ты не весь работал.", at = 30 * hour),
    )

    @Test
    fun `сюжет входит в ночь с ядром, почти-дубль — нет`() {
        val night = DreamWeaver.weave(claude(), plotWords = plotWords)
        // Центры 4, 3, 2 дают тот же набор записей — сюжет один.
        assertEquals(listOf(DreamWeaver.Woven(listOf(1L, 2L, 3L, 4L), Kind.PLOT, "клод")), night.of(Kind.PLOT))
        assertNull(DreamWeaver.plotsWhy(night))
    }

    @Test
    fun `без словаря сюжеты не плетутся, и это сказано`() {
        val night = weave(claude())
        assertTrue(night.of(Kind.PLOT).isEmpty())
        assertNull(night.plotCenters)
        assertEquals("словарь начальных форм не загружен, без него у записей нет слов-тем", DreamWeaver.plotsWhy(night))
    }

    @Test
    fun `ни у одной записи нет ядра — сказано иначе, чем без словаря`() {
        val night = DreamWeaver.weave(
            listOf(rec(1, "Лопата сломалась."), rec(2, "Бетон застыл.", at = 5 * hour)),
            plotWords = plotWords,
        )
        assertEquals(0, night.plotCenters)
        assertEquals("ни у одной записи из 2 нет ядра", DreamWeaver.plotsWhy(night))
    }

    @Test
    fun `сюжеты получают места, даже когда простых снов больше потолка`() {
        // Пачка из двенадцати несвязанных слов подряд даёт простых снов больше потолка.
        val burst = listOf(
            "бетон", "ветер", "горох", "дождь", "кастрюля", "лопата",
            "замок", "искра", "капля", "лимон", "магнит", "облако",
        ).mapIndexed { i, w -> rec(50L + i, w, at = 200 * hour + i * minute) }
        val night = DreamWeaver.weave(claude() + burst, plotWords = plotWords)
        assertTrue(night.ceilingHit)
        assertEquals(DreamWeaver.MAX_DREAMS_PER_NIGHT, night.dreams.size)
        assertTrue("сюжет на месте", night.of(Kind.PLOT).isNotEmpty())
        assertTrue(night.of(Kind.PLOT).size <= DreamWeaver.PLOT_PLACES)
    }

    @Test
    fun `сюжетов нет — мест простым столько же, сколько было`() {
        val words = listOf(
            "бетон", "ветер", "горох", "дождь", "кастрюля", "лопата",
            "замок", "искра", "капля", "лимон", "магнит", "облако",
        )
        val night = weave(words.mapIndexed { i, w -> rec(i + 1L, w, at = (i + 1) * minute) })
        assertTrue(night.of(Kind.PLOT).isEmpty())
        assertEquals(DreamWeaver.MAX_DREAMS_PER_NIGHT, night.dreams.size)
    }

    @Test
    fun `строка о сюжетах говорит и про ноль`() {
        assertEquals("Сюжетов: ни одного", DreamWeaver.plotsLine(emptyList()))
        assertEquals("Сюжетов: ни одного — почему", DreamWeaver.plotsLine(emptyList(), why = "почему"))
        assertEquals("Сюжетов: 2, длиной 4 записей", DreamWeaver.plotsLine(listOf(4, 4)))
        assertEquals(
            "Сюжетов: 3, длиной от 3 до 10 записей; ядра: «клод», «переезд», «пинг»",
            DreamWeaver.plotsLine(listOf(10, 3, 7), listOf("клод", "переезд", "пинг")),
        )
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
        val night = weave(records)
        assertEquals(DreamWeaver.MAX_DREAMS_PER_NIGHT, night.dreams.size)
        assertTrue(night.ceilingHit)
    }

    /**
     * Живой случай: записей больше, чем мест на них по старшинству. Все
     * сказаны в одну минуту и связаны по времени каждая с каждой. Прежний
     * порядок отдавал первые места парам самой старой записи — она была в
     * каждом сне, а новые не снились вовсе.
     */
    @Test
    fun `при нехватке мест снится каждая запись, и никто не собирает лишнего`() {
        val letters = "бвгдклмнпрстфхц"
        val records = (0 until 70).map { i ->
            val word = "${letters[i / letters.length]}${letters[i % letters.length]}ааа"
            rec(i + 1L, word, at = 0)
        }
        val night = weave(records)
        assertEquals(DreamWeaver.MAX_DREAMS_PER_NIGHT, night.dreams.size)
        assertTrue(night.ceilingHit)
        val uses = night.dreams.flatMap { it.recordIds }.groupingBy { it }.eachCount()
        assertEquals("каждая запись в каком-то сне", 70, uses.size)
        assertTrue("никто не больше чем в двух: $uses", uses.values.all { it <= 2 })
    }

    @Test
    fun `мест хватает — делёж ничего не меняет`() {
        val dreams = listOf(
            DreamWeaver.Woven(listOf(1L, 2L), Kind.TIME),
            DreamWeaver.Woven(listOf(1L, 3L), Kind.TIME),
        )
        assertEquals(dreams, DreamWeaver.fairShare(dreams, 2))
    }

    @Test
    fun `делёж возвращает сны в прежнем порядке`() {
        val dreams = listOf(
            DreamWeaver.Woven(listOf(1L, 2L), Kind.TIME),
            DreamWeaver.Woven(listOf(1L, 3L), Kind.TIME),
            DreamWeaver.Woven(listOf(4L, 5L), Kind.TIME),
        )
        assertEquals(
            listOf(dreams[0], dreams[2]),
            DreamWeaver.fairShare(dreams, 2),
        )
    }

    // --- Очередь свежих снов ---

    private val crowdedWords = listOf(
        "бетон", "ветер", "горох", "дождь", "ежевика", "жираф",
        "замок", "искра", "капля", "лимон", "магнит", "облако",
    )

    @Test
    fun `при потолке свежие сны идут первыми, повторы — в остаток`() {
        // Сказаны в одну минуту: 66 снов по времени на 60 мест, вчерашние
        // 60 — повторы, 6 — свежие.
        val records = crowdedWords.mapIndexed { i, w -> rec(i + 1L, w, at = 0) }
        val first = weave(records)
        val recent = first.dreams.map { it.recordIds }.toSet()
        val second = weave(records, recent)
        val fresh = second.dreams.filter { it.recordIds !in recent }
        assertEquals("все свежие вошли", 66 - 60, fresh.size)
        assertEquals("остаток добран повторами, а не пуст", DreamWeaver.MAX_DREAMS_PER_NIGHT, second.dreams.size)
        assertEquals(54, second.repeats)
        assertEquals(0, first.repeats)
    }

    @Test
    fun `без потолка недавние сны ничего не меняют`() {
        val records = listOf(
            rec(1, "Алеет солнце на закате", at = 0),
            rec(2, "Бетономешалка мешает бетон", at = minute),
            rec(3, "Чёрный чай заваривают кипятком", at = 2 * minute),
        )
        val first = weave(records)
        val again = weave(records, first.dreams.map { it.recordIds }.toSet())
        assertEquals(first.dreams, again.dreams)
        assertEquals("повторы сосчитаны честно", first.dreams.size, again.repeats)
    }

    @Test
    fun `свежесть сильнее дележа поровну`() {
        val dreams = listOf(
            DreamWeaver.Woven(listOf(1L, 2L), Kind.TIME),
            DreamWeaver.Woven(listOf(1L, 3L), Kind.TIME),
            DreamWeaver.Woven(listOf(4L, 5L), Kind.TIME),
        )
        // Без очереди взялись бы первый и третий (см. «делёж возвращает сны в
        // прежнем порядке»); третий снился недавно и уступает.
        assertEquals(
            listOf(dreams[0], dreams[1]),
            DreamWeaver.fairShare(dreams, 2, setOf(listOf(4L, 5L))),
        )
    }

    @Test
    fun `сны реки не считаются плетением ночи`() {
        val rows = listOf(
            Dream(1L, "1,2", Kind.TIME.name),
            Dream(1L, "1, 3", Kind.TIME.name),
            Dream(1L, "1,2,3", DreamRiver.KIND),
        )
        assertEquals(setOf(listOf(1L, 2L), listOf(1L, 3L)), DreamWeaver.chainsOf(rows))
    }

    @Test
    fun `под потолком — потолок не сработал`() {
        val night = weave(
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
        val night = weave(
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
        val night = weave(listOf(cold, hot))
        assertEquals(1, night.dreams.size)
        assertEquals(Layer.BLUE.name, cold.layer)
        assertEquals(Layer.GREEN.name, hot.layer)
    }

    // --- Итог ночи ---

    @Test
    fun `ночь без снов оставляет итог с нулём, а не ничего`() {
        val night = weave(listOf(rec(1, "Алеет солнце на закате")))
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
        val night = weave(records)
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

    // --- Очередь записей между ночами ---

    @Test
    fun `не снившаяся запись получает место раньше снившихся`() {
        val dreams = listOf(
            DreamWeaver.Woven(listOf(1L, 2L), Kind.TIME),
            DreamWeaver.Woven(listOf(3L, 4L), Kind.TIME),
            DreamWeaver.Woven(listOf(5L, 6L), Kind.TIME),
        )
        // Без очереди место взял бы первый сон — прежний порядок.
        assertEquals(listOf(dreams[0]), DreamWeaver.fairShare(dreams, 1))
        // Записи 1–4 снились, 5 и 6 — ни разу.
        val last = mapOf(1L to 10L, 2L to 10L, 3L to 20L, 4L to 20L)
        assertEquals(listOf(dreams[2]), DreamWeaver.fairShare(dreams, 1, lastDreamt = last))
    }

    @Test
    fun `из снившихся первой идёт та, что ждёт дольше`() {
        val dreams = listOf(
            DreamWeaver.Woven(listOf(1L, 2L), Kind.TIME),
            DreamWeaver.Woven(listOf(3L, 4L), Kind.TIME),
        )
        val last = mapOf(1L to 30L, 2L to 30L, 3L to 20L, 4L to 30L)
        assertEquals(listOf(dreams[1]), DreamWeaver.fairShare(dreams, 1, lastDreamt = last))
    }

    @Test
    fun `очередь выбирает среди равных, свежесть и делёж поровну — раньше неё`() {
        val dreams = listOf(
            DreamWeaver.Woven(listOf(1L, 2L), Kind.TIME),
            DreamWeaver.Woven(listOf(1L, 3L), Kind.TIME),
            DreamWeaver.Woven(listOf(4L, 5L), Kind.TIME),
        )
        // Запись 3 не снилась ни разу — её сон берётся первым; второй сон
        // записи 1 после этого не берётся: поровну — раньше очереди.
        val last = mapOf(1L to 10L, 2L to 10L, 4L to 10L, 5L to 10L)
        assertEquals(listOf(dreams[1], dreams[2]), DreamWeaver.fairShare(dreams, 2, lastDreamt = last))
        // Сон 4–5 не снился вовсе, но он недавний повтор: свежесть — раньше очереди.
        assertEquals(
            listOf(dreams[0], dreams[1]),
            DreamWeaver.fairShare(dreams, 2, setOf(listOf(4L, 5L)), mapOf(1L to 10L, 2L to 10L, 3L to 10L)),
        )
    }

    @Test
    fun `мест хватает — очередь ничего не меняет`() {
        val records = listOf(
            rec(1, "Алеет солнце на закате", at = 0),
            rec(2, "Бетономешалка мешает бетон", at = minute),
            rec(3, "Чёрный чай заваривают кипятком", at = 2 * minute),
        )
        assertEquals(weave(records).dreams, weave(records, lastDreamt = mapOf(1L to 5L)).dreams)
    }

    @Test
    fun `за несколько ночей с очередью снятся все записи, без неё — одни и те же`() {
        // 200 несвязанных слов в одну минуту: мест за ночь — 60 снов, 120 записей.
        val letters = "бвгджзкл"
        val records = (0 until 200).map {
            rec(it + 1L, "${letters[it % 8]}о${letters[it / 8 % 8]}и${letters[it / 64]}ка", at = 0)
        }
        fun nights(queue: Boolean): Set<Long> {
            val rows = ArrayList<Dream>()
            val seen = HashSet<Long>()
            for (n in 1..4) {
                val stamps = rows.map { it.nightAt }.distinct().sorted().takeLast(DreamWeaver.FRESH_NIGHTS)
                val night = weave(
                    records,
                    DreamWeaver.chainsOf(rows.filter { it.nightAt in stamps }),
                    if (queue) DreamWeaver.lastDreamtOf(rows) else emptyMap(),
                )
                night.dreams.forEach { d ->
                    seen += d.recordIds
                    rows += Dream(n.toLong(), d.recordIds.joinToString(","), d.kind.name)
                }
            }
            return seen
        }
        assertEquals("с очередью приснились все", 200, nights(queue = true).size)
        assertTrue("без очереди — не все: ${nights(queue = false).size}", nights(queue = false).size < 200)
    }

    @Test
    fun `когда запись снилась — по последней ночи, сны реки не в счёт`() {
        val rows = listOf(
            Dream(10L, "1,2", Kind.TIME.name),
            Dream(20L, "2,3", Kind.TIME.name),
            Dream(30L, "1,4", DreamRiver.KIND),
        )
        assertEquals(mapOf(1L to 10L, 2L to 20L, 3L to 20L), DreamWeaver.lastDreamtOf(rows))
    }
}
