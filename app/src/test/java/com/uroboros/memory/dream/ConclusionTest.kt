package com.uroboros.memory.dream

import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выводы из снов ([Conclusion]): разбор ответа, проверка слов, выбор снов на
 * ночь, строки итога и подпись показа; слова раздела ([ConclusionView]).
 * Проверки на молчание стоят рядом со срабатыванием: лишний принятый вывод
 * разрядил бы пружину любопытства без основания.
 *
 * Чего эти тесты НЕ проверяют: что модель на деле даст фразу, что строки дойдут
 * до базы и что шаг стоит в ночи после зеркала. Это проводка (ConclusionStep,
 * AgentService, MainActivity), её проверяет владелец на телефоне.
 */
class ConclusionTest {

    private val cat = "Кошка спит на диване"
    private val rain = "Дождь идёт весь день"

    // ---- Разбор ----

    private fun text(answer: String) = (Conclusion.parse(answer) as Conclusion.Parsed.Text).text
    private fun refused(answer: String) = Conclusion.parse(answer) as Conclusion.Parsed.Refused

    @Test
    fun `берётся первая непустая строка`() {
        assertEquals("кот спит, пока идёт дождь", text("\n  кот спит, пока идёт дождь\nпояснение"))
    }

    @Test
    fun `кавычки по краям снимаются`() {
        assertEquals("кот спит, пока идёт дождь", text("«кот спит, пока идёт дождь»"))
        assertEquals("кот спит", text("\"кот спит\""))
    }

    @Test
    fun `ведущее «Я подумал, что» снимается, с запятой и без, в любом регистре`() {
        assertEquals("кот спит", text("Я подумал, что кот спит."))
        assertEquals("кот спит", text("я подумала что кот спит"))
        assertEquals("кот спит", text("«Я ПОДУМАЛ, ЧТО кот спит»"))
    }

    @Test
    fun `точка в конце снимается`() {
        assertEquals("кот спит", text("кот спит."))
    }

    @Test
    fun `пустой ответ — отказ, первая строка как есть`() {
        val got = refused("  \n ")
        assertEquals("", got.raw)
        assertEquals("модель не дала вывода", got.reason)
        assertEquals("модель не дала вывода", refused("«Я подумал, что.»").reason)
    }

    @Test
    fun `длиннее объявленных слов — отказ`() {
        val long = (1..Conclusion.MAX_WORDS + 1).joinToString(" ") { "кот" }
        val got = refused(long)
        assertEquals(long, got.raw)
        assertEquals("вывод длиннее ${Conclusion.MAX_WORDS} слов", got.reason)
        text((1..Conclusion.MAX_WORDS).joinToString(" ") { "кот" })
    }

    @Test
    fun `длиннее объявленных знаков — отказ`() {
        val long = "а".repeat(Conclusion.MAX_CHARS + 1)
        assertEquals("вывод длиннее ${Conclusion.MAX_CHARS} знаков", refused(long).reason)
    }

    // ---- Проверка ----

    @Test
    fun `вывод из слов записей проходит`() {
        assertNull(Conclusion.check("кошка спит весь день", listOf(cat, rain)))
    }

    @Test
    fun `лишние основы названы`() {
        val reason = Conclusion.check("кошка боится грозы", listOf(cat, rain))!!
        assertTrue(reason, reason.endsWith("нет в записях сна"))
        for (stem in RiskTrigger.significantStems("боится грозы")) {
            assertTrue(reason, reason.contains("«$stem»"))
        }
        assertFalse(reason, reason.contains("«${RiskTrigger.significantStems("кошка").single()}»"))
    }

    @Test
    fun `связующие слова не считаются лишними`() {
        assertNull(Conclusion.check("кошка и дождь связаны", listOf(cat, rain)))
        assertNull(Conclusion.check("общее: кошка и дождь вместе", listOf(cat, rain)))
    }

    @Test
    fun `одни связующие слова — отказ`() {
        assertEquals("в выводе нет слов записей", Conclusion.check("записи связаны вместе", listOf(cat, rain)))
        assertEquals("в выводе нет слов записей", Conclusion.check("и так", listOf(cat, rain)))
    }

    @Test
    fun `запрос — записи в ёлочках, каждая с новой строки, по порядку`() {
        assertEquals("«$cat»\n«$rain»", Conclusion.request(listOf(" $cat ", rain)))
    }

    // ---- Выбор снов ----

    private fun leader(nightAt: Long, ids: String, contribution: Int = 3) = CuriosityPressure.Leader(
        brief = CuriosityPressure.Brief(DreamWeaver.Kind.TIME.name, listOf(cat)),
        contribution = contribution,
        dream = Dream(nightAt = nightAt, recordIds = ids, kind = DreamWeaver.Kind.TIME.name),
        records = listOf(Sticker(id = 1, content = cat)),
    )

    @Test
    fun `давление ноль — не делаю, связывать нечего`() {
        assertEquals(
            Conclusion.Pick.Silent("давление любопытства ноль — связывать нечего"),
            Conclusion.pick(emptyList(), emptySet()),
        )
    }

    @Test
    fun `ряд пуст, а давление есть — оно от ответов о спрошенных снах`() {
        assertEquals(
            Conclusion.Pick.Silent("давление только от ответов о спрошенных снах — связывать нечего"),
            Conclusion.pick(emptyList(), emptySet(), pressure = 2),
        )
    }

    @Test
    fun `давление не мешает выбору, когда ряд не пуст`() {
        val ranked = listOf(leader(1, "1,2"))
        val got = Conclusion.pick(ranked, emptySet(), pressure = 7) as Conclusion.Pick.Dreams
        assertEquals(ranked, got.dreams)
    }

    @Test
    fun `не больше объявленного числа снов, начиная с лидера`() {
        val ranked = (1..5).map { leader(it.toLong(), "1,$it") }
        val got = Conclusion.pick(ranked, emptySet()) as Conclusion.Pick.Dreams
        assertEquals(ranked.take(Conclusion.MAX_PER_NIGHT), got.dreams)
    }

    @Test
    fun `пробовавшиеся сны пропускаются`() {
        val ranked = (1..4).map { leader(it.toLong(), "1,$it") }
        val tried = setOf(ConclusionKey(1, "1,1"), ConclusionKey(3, "1,3"))
        val got = Conclusion.pick(ranked, tried) as Conclusion.Pick.Dreams
        assertEquals(listOf(ranked[1], ranked[3]), got.dreams)
    }

    @Test
    fun `все пробовались — своя причина`() {
        val ranked = listOf(leader(1, "1,2"))
        assertEquals(
            Conclusion.Pick.Silent("все сны, дающие давление, уже пробовались"),
            Conclusion.pick(ranked, setOf(ConclusionKey(1, "1,2"))),
        )
    }

    // ---- Строки итога и показ ----

    @Test
    fun `итог молчания`() {
        assertEquals("Выводы: не делаю — модель не загружена", Conclusion.silentOutcome("Модель не загружена"))
    }

    @Test
    fun `итог без отброшенных`() {
        assertEquals("Выводы: сделано 2, отброшено 0", Conclusion.doneOutcome(2, emptyList()))
    }

    @Test
    fun `итог с отброшенными — причины через точку с запятой, длинные обрезаны`() {
        assertEquals(
            "Выводы: сделано 1, отброшено 2 — в выводе нет слов записей; модель не дала вывода",
            Conclusion.doneOutcome(1, listOf("в выводе нет слов записей", "модель не дала вывода")),
        )
        val long = Conclusion.doneOutcome(0, listOf("х".repeat(Conclusion.OUTCOME_CHARS + 50)))
        assertTrue(long, long.endsWith("…"))
        assertEquals("Выводы: сделано 0, отброшено 1 — ".length + Conclusion.OUTCOME_CHARS + 1, long.length)
    }

    @Test
    fun `остановка на условиях модели — в хвосте итога`() {
        assertEquals(
            "Выводы: сделано 1, отброшено 0 — дальше не делаю: зона критическая",
            Conclusion.doneOutcome(1, emptyList(), "зона критическая"),
        )
    }

    @Test
    fun `подпись показа`() {
        assertEquals("Я подумал, что кот спит.", Conclusion.shown("кот спит"))
    }

    // ---- Раздел на экране ----

    private val dream = Dream(nightAt = 5, recordIds = "1,2", kind = DreamWeaver.Kind.TIME.name)

    private fun row(accepted: Boolean, text: String, reason: String? = null) = ConclusionRow(
        nightAt = 1_789_923_154_023L,
        dreamNightAt = dream.nightAt,
        dreamRecordIds = dream.recordIds,
        text = text,
        accepted = accepted,
        reason = reason,
    )

    private val live = listOf(Sticker(id = 1, content = cat), Sticker(id = 2, content = rain))

    @Test
    fun `принятый и отброшенный показаны со сном под ними`() {
        val shown = ConclusionView.render(
            "Выводы: сделано 1, отброшено 1 — в выводе нет слов записей",
            listOf(
                ConclusionView.Item(row(true, "кот спит весь день"), dream, live),
                ConclusionView.Item(row(false, "записи связаны", "в выводе нет слов записей"), dream, live),
            ),
        )
        val lines = shown.lines()
        assertEquals("ВЫВОДЫ", lines[0])
        assertEquals("Последняя ночь: сделано 1, отброшено 1 — в выводе нет слов записей", lines[1])
        assertEquals("Мысли агента о связи записей сна. Пока никуда не подаются — только здесь.", lines[2])
        assertTrue(shown, shown.contains("Я подумал, что кот спит весь день.\n    по времени: $cat → $rain"))
        assertTrue(shown, shown.contains("отброшено: «записи связаны» — в выводе нет слов записей\n    по времени:"))
    }

    @Test
    fun `молчащее звено скрывает текст вывода и сон`() {
        val hidden = listOf(live[0], Sticker(id = 2, content = rain, reviewPending = true))
        val shown = ConclusionView.render(null, listOf(ConclusionView.Item(row(true, "кот спит весь день"), dream, hidden)))
        assertTrue(shown, shown.contains(ConclusionView.SILENT))
        assertFalse(shown, shown.contains("кот спит весь день"))
        assertFalse(shown, shown.contains(rain))

        val gone = ConclusionView.render(null, listOf(ConclusionView.Item(row(true, "кот"), dream, listOf(live[0], null))))
        assertTrue("удалённое звено тоже заставляет молчать", gone.contains(ConclusionView.SILENT))
    }

    @Test
    fun `ночей с выводами не было — сказано, где они делаются`() {
        val shown = ConclusionView.render(null, emptyList())
        assertTrue(shown, shown.contains("ночей с выводами не было"))
        // Где делаются выводы, говорит сам текст показа — здесь закреплено
        // только устройство строки, чтобы подсказка жила в одном месте.
        val meter = ConclusionView.meter(null, 0)
        assertTrue(meter, meter.startsWith("Выводы: последняя ночь — ночей с выводами не было — "))
        assertTrue(meter, meter.endsWith(" · всего сделано 0"))
        assertEquals(
            "Выводы: последняя ночь — не делаю — давление любопытства ноль — связывать нечего · всего сделано 3",
            ConclusionView.meter("Выводы: не делаю — давление любопытства ноль — связывать нечего", 3),
        )
    }
}
