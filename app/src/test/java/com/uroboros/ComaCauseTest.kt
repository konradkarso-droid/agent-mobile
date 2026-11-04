package com.uroboros

import com.uroboros.ComaCause.Exit
import com.uroboros.ComaCause.Kind
import com.uroboros.ComaCause.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слова причины комы.
 *
 * ГЛАВНОЕ ЗДЕСЬ: прошлая кома — самая новая запись, а не первая попавшаяся;
 * «записей нет» и «спросить не удалось» не выдаются за благополучие и не
 * смешиваются друг с другом.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что Android на этом телефоне отдаёт записи и какими
 * причинами прошивка их помечает. Это видно только на устройстве.
 */
class ComaCauseTest {

    private val moment: (Long) -> String = { "t$it" }

    private fun exit(at: Long, kind: Kind, status: Int = 0, description: String? = null, rssKb: Long = 0) =
        Exit(at, kind, status, description, rssKb)

    @Test
    fun `прошлая кома — первая в списке, со временем, причиной и памятью`() {
        val text = ComaCause.lines(
            Reading.Exits(listOf(exit(300, Kind.LOW_MEMORY, rssKb = 4_300_000), exit(200, Kind.USER_REQUESTED))),
            moment,
        )
        val first = text.lines().first()
        assertEquals("Прошлая кома t300 — нехватка памяти, занимал 4.1 ГБ", first)
    }

    @Test
    fun `счёт причин — по всем записям, больше первыми`() {
        val text = ComaCause.lines(
            Reading.Exits(
                listOf(
                    exit(500, Kind.USER_REQUESTED),
                    exit(400, Kind.LOW_MEMORY),
                    exit(300, Kind.LOW_MEMORY),
                    exit(200, Kind.SIGNALED, status = 9),
                    exit(100, Kind.LOW_MEMORY),
                )
            ),
            moment,
        )
        assertEquals("Из последних 5: память 3, вручную 1, сигнал 9 1", text.lines()[1])
    }

    @Test
    fun `при равном счёте выше та причина, что случилась позже`() {
        val text = ComaCause.lines(Reading.Exits(listOf(exit(2, Kind.CRASH_NATIVE), exit(1, Kind.LOW_MEMORY))), moment)
        assertEquals("Из последних 2: сбой движка 1, память 1", text.lines()[1])
    }

    @Test
    fun `сигнал и код выхода называются числом`() {
        assertEquals("убит сигналом 9", ComaCause.word(Kind.SIGNALED, 9))
        assertEquals("вышел сам (код 1)", ComaCause.word(Kind.EXIT_SELF, 1))
    }

    @Test
    fun `пояснение прошивки печатается как есть и режется по пределу`() {
        val short = ComaCause.lines(Reading.Exits(listOf(exit(1, Kind.SIGNALED, 9, "remove task"))), moment)
        assertTrue(short, short.lines().first().endsWith("(«remove task»)"))
        val long = "x".repeat(ComaCause.DESCRIPTION_CHARS + 10)
        val cut = ComaCause.lines(Reading.Exits(listOf(exit(1, Kind.OTHER, 0, long))), moment).lines().first()
        assertTrue(cut, cut.endsWith("x".repeat(ComaCause.DESCRIPTION_CHARS) + "…»)"))
    }

    // --- Молчание: нечего сказать — так и сказано, без выдуманной причины ---

    @Test
    fun `без памяти и пояснения — ни «занимал», ни скобок`() {
        val text = ComaCause.lines(Reading.Exits(listOf(exit(1, Kind.ANR, description = "  "))), moment).lines().first()
        assertEquals("Прошлая кома t1 — завис, не отвечал системе", text)
        assertFalse(text, text.contains("занимал"))
    }

    @Test
    fun `записей нет, спросить не удалось и старый Android — три разные строки`() {
        val none = ComaCause.lines(Reading.Exits(emptyList()), moment)
        val failed = ComaCause.lines(Reading.Failed("SecurityException"), moment)
        val old = ComaCause.lines(Reading.TooOld, moment)
        assertEquals("Причина комы: Android записей не отдал", none)
        assertEquals("Причина комы: не узнал — SecurityException", failed)
        assertEquals("Причина комы: Android до 11 её не хранит", old)
        assertFalse(none.contains("Прошлая кома"))
        assertFalse(failed.contains("Прошлая кома"))
    }

    @Test
    fun `память меньше гигабайта — в мегабайтах`() {
        assertEquals("850 МБ", ComaCause.size(850L * 1024))
        assertEquals("1.0 ГБ", ComaCause.size(1024L * 1024))
    }

    @Test
    fun `у каждой причины есть слово`() {
        for (k in Kind.entries) {
            assertTrue(k.name, ComaCause.word(k, 0).isNotBlank())
            assertTrue(k.name, ComaCause.short(k, 0).isNotBlank())
        }
    }
}
