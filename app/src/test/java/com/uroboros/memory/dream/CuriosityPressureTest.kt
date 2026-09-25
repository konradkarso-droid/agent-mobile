package com.uroboros.memory.dream

import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пружина любопытства.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: пустая память, старые сны, поданные,
 * но не тронутые сны, молчащие сны и спрошенные сны без ответа не сжимают
 * пружину ничем.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что веса верны. Они объявлены; настоящие — из
 * замеров по прибору на устройстве.
 */
class CuriosityPressureTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun record(id: Long, hidden: Boolean = false) =
        Sticker(id = id, content = "Запись номер $id лежит в памяти", reviewPending = hidden)

    private val live = (1L..9L).associateWith { record(it) }

    private fun dream(
        vararg ids: Long,
        nightAt: Long = now - day,
        picked: Int = 0,
        recalled: Int = 0,
        served: Int = 0,
        askedAt: Long? = null,
        answered: Int = 0,
    ) = Dream(
        nightAt = nightAt,
        recordIds = ids.joinToString(","),
        kind = DreamWeaver.Kind.TIME.name,
        servedCount = served,
        recalledCount = recalled,
        pickedUpCount = picked,
        askedAt = askedAt,
        answeredCount = answered,
    )

    private fun measure(
        dreams: List<Dream>,
        records: Map<Long, Sticker> = live,
        concluded: Set<ConclusionKey> = emptySet(),
    ) = CuriosityPressure.measure(dreams, { records[it] }, now, concluded)

    @Test
    fun `снов нет — давление ноль и лидера нет`() {
        val got = measure(emptyList())
        assertEquals(0, got.pressure)
        assertNull(got.leader)
    }

    @Test
    fun `сны старше трёх ночей не считаются`() {
        val old = dream(1, 2, nightAt = now - CuriosityPressure.WINDOW_MS - 1, picked = 4, recalled = 4)
        assertEquals(0, measure(listOf(old)).pressure)
        val edge = dream(1, 2, nightAt = now - CuriosityPressure.WINDOW_MS, recalled = 1)
        assertEquals("на самой границе окна сон ещё считается", 2, measure(listOf(edge)).pressure)
    }

    @Test
    fun `поданный, но не вспомненный и не подхваченный сон даёт ноль`() {
        val got = measure(listOf(dream(1, 2, served = 7)))
        assertEquals(0, got.pressure)
        assertNull(got.leader)
    }

    @Test
    fun `молчащий сон не даёт ничего, даже с ненулевыми счетами`() {
        val hidden = live + (2L to record(2, hidden = true))
        val got = measure(listOf(dream(1, 2, picked = 3, recalled = 3)), hidden)
        assertEquals(0, got.pressure)
        assertEquals(0, got.pickedUp)
        assertEquals(0, got.recalled)
        assertNull(got.leader)

        val gone = live - 2L
        assertEquals("удалённое звено тоже заставляет молчать", 0, measure(listOf(dream(1, 2, picked = 1)), gone).pressure)
    }

    @Test
    fun `подхваченный весит больше вспомненного`() {
        val picked = measure(listOf(dream(1, 2, picked = 1)))
        val recalled = measure(listOf(dream(1, 2, recalled = 1)))
        assertTrue(picked.pressure > recalled.pressure)
        assertEquals(3, picked.pressure)
        assertEquals(2, recalled.pressure)
    }

    @Test
    fun `давление — сумма слагаемых, и слагаемые видны порознь`() {
        val got = measure(listOf(dream(1, 2, picked = 2, recalled = 1), dream(3, 4, recalled = 3)))
        assertEquals(2, got.pickedUp)
        assertEquals(4, got.recalled)
        assertEquals(0, got.own)
        assertEquals(3 * 2 + 2 * 4, got.pressure)
    }

    @Test
    fun `сон-лидер — с наибольшим вкладом`() {
        val got = measure(
            listOf(
                dream(1, 2, recalled = 2), // вклад 4
                dream(3, 4, picked = 1, recalled = 1), // вклад 5
                dream(5, 6, picked = 1), // вклад 3
            )
        )
        assertEquals(5, got.leader?.contribution)
        assertEquals(listOf(live.getValue(3).content, live.getValue(4).content), got.leader?.brief?.texts)
    }

    @Test
    fun `при равном вкладе лидер — сон более поздней ночи`() {
        val got = measure(
            listOf(
                dream(1, 2, nightAt = now - 2 * day, picked = 1),
                dream(3, 4, nightAt = now - day, picked = 1),
            )
        )
        assertEquals(live.getValue(3).content, got.leader?.brief?.texts?.first())
    }

    @Test
    fun `молчащий сон не становится лидером`() {
        val records = live + (1L to record(1, hidden = true))
        val got = measure(listOf(dream(1, 2, picked = 9), dream(3, 4, recalled = 1)), records)
        assertEquals(2, got.leader?.contribution)
    }

    @Test
    fun `строка прибора печатается и при нуле, со своим слагаемым и без лидера`() {
        val line = CuriosityPressure.meter(measure(emptyList()))
        assertEquals(
            "Любопытство: давление 0 — подхвачено 0 (×3), вспомнено 0 (×2), " +
                "своё 0 (×1) · разряжено выводом: 0 · за 3 ночи",
            line,
        )
    }

    @Test
    fun `при давлении больше нуля второй строкой лидер, подхват хода — в первой`() {
        val result = measure(listOf(dream(1, 2, picked = 1)))
        val line = CuriosityPressure.meter(
            result,
            listOf(CuriosityPressure.Brief(DreamWeaver.Kind.TIME.name, listOf("Кот спит", "Дождь идёт"))),
        )
        val rows = line.lines()
        assertTrue(rows.size >= 2)
        assertTrue(rows[0].startsWith("Любопытство: давление 3"))
        assertTrue(rows[0].contains("в этом ходе подхвачен сон «по времени: Кот спит → Дождь идёт»"))
        assertTrue(rows[1].contains("вклад 3"))
        assertFalse(rows[0].contains("вклад"))
    }

    @Test
    fun `сон столбиком — вид, затем каждое звено своей строкой`() {
        assertEquals(
            "мост:\n    · Кот спит\n    · Дождь идёт\n    · Гром",
            DreamView.column(DreamWeaver.Kind.BRIDGE.name, listOf("Кот спит", "Дождь идёт", "Гром"), indent = "    "),
        )
    }

    @Test
    fun `спрошенный сон с ненулевыми счетами даёт только своё`() {
        val got = measure(listOf(dream(1, 2, picked = 3, recalled = 2, askedAt = now - 1, answered = 1)))
        assertEquals(0, got.pickedUp)
        assertEquals(0, got.recalled)
        assertEquals(1, got.own)
        assertEquals("ответ весит ×1", 1, got.pressure)
    }

    @Test
    fun `спрошенный сон не становится лидером`() {
        val got = measure(
            listOf(
                dream(1, 2, picked = 9, askedAt = now - 1, answered = 5),
                dream(3, 4, recalled = 1),
            )
        )
        assertEquals(2, got.leader?.contribution)
        assertEquals(live.getValue(3).content, got.leader?.brief?.texts?.first())

        val alone = measure(listOf(dream(1, 2, picked = 9, askedAt = now - 1, answered = 5)))
        assertNull("кроме спрошенного сжимать некому — лидера нет", alone.leader)
    }

    @Test
    fun `молчащий спрошенный сон не даёт ничего`() {
        val silentAnswer = measure(listOf(dream(1, 2, picked = 3, askedAt = now - 1)))
        assertEquals("спрошен, но не ответили — ноль", 0, silentAnswer.pressure)

        val hidden = live + (2L to record(2, hidden = true))
        val got = measure(listOf(dream(1, 2, askedAt = now - 1, answered = 4)), hidden)
        assertEquals("скрытое звено глушит и ответы", 0, got.pressure)
        assertEquals(0, got.own)
    }

    @Test
    fun `в строке прибора своё без оговорки и ответ этого хода`() {
        val result = measure(listOf(dream(1, 2, askedAt = now - 1, answered = 2)))
        val line = CuriosityPressure.meter(
            result,
            answeredThisTurn = listOf(CuriosityPressure.Brief(DreamWeaver.Kind.TIME.name, listOf("Кот спит", "Дождь идёт"))),
        )
        assertTrue(line.contains("своё 2 (×1)"))
        assertFalse(line.contains("источника"))
        assertTrue(line.contains("в этом ходе ответ о спрошенном сне «по времени: Кот спит → Дождь идёт»"))
        assertFalse("ответ не выдаётся за подхват", line.contains("подхвачен сон"))
    }

    // ---- Ряд и разрядка выводом ----

    @Test
    fun `ряд — все сны с вкладом в порядке вклада, первый — лидер`() {
        val got = measure(
            listOf(
                dream(1, 2, recalled = 2), // вклад 4
                dream(3, 4, picked = 1, recalled = 1), // вклад 5
                dream(5, 6, served = 3), // вклад 0 — не в ряду
                dream(7, 8, nightAt = now - 2 * day, picked = 1), // вклад 3, ночь раньше
                dream(8, 9, picked = 1), // вклад 3
            )
        )
        assertEquals(listOf(5, 4, 3, 3), got.ranked.map { it.contribution })
        assertEquals(listOf("3,4", "1,2", "8,9", "7,8"), got.ranked.map { it.dream.recordIds })
        assertEquals(got.leader, got.ranked.first())
    }

    @Test
    fun `сон с принятым выводом не даёт давления, не лидер и считается разряженным`() {
        val done = dream(1, 2, picked = 9)
        val got = measure(listOf(done, dream(3, 4, recalled = 1)), concluded = setOf(ConclusionKey.of(done)))
        assertEquals(0, got.pickedUp)
        assertEquals(1, got.recalled)
        assertEquals(2, got.pressure)
        assertEquals(1, got.concluded)
        assertEquals(listOf("3,4"), got.ranked.map { it.dream.recordIds })
        assertEquals("3,4", got.leader?.dream?.recordIds)
        assertTrue(CuriosityPressure.meter(got).lines()[0].contains("· разряжено выводом: 1 ·"))
    }

    @Test
    fun `без выводов результат прежний, и ноль разряженных печатается`() {
        val dreams = listOf(dream(1, 2, picked = 2, recalled = 1), dream(3, 4, recalled = 3))
        val before = measure(dreams)
        val after = measure(dreams, concluded = setOf(ConclusionKey(now - day, "5,6")))
        assertEquals(before, after)
        assertEquals(0, after.concluded)
        assertTrue(CuriosityPressure.meter(after).contains("разряжено выводом: 0"))
    }

    @Test
    fun `спрошенный сон с выводом считается один раз, как спрошенный`() {
        val asked = dream(1, 2, picked = 3, askedAt = now - 1, answered = 2)
        val got = measure(listOf(asked), concluded = setOf(ConclusionKey.of(asked)))
        assertEquals(2, got.own)
        assertEquals(0, got.concluded)
        assertTrue(got.ranked.isEmpty())
    }

    @Test
    fun `молчащий сон с выводом не считается разряженным`() {
        val done = dream(1, 2, picked = 1)
        val hidden = live + (2L to record(2, hidden = true))
        assertEquals(0, measure(listOf(done), hidden, setOf(ConclusionKey.of(done))).concluded)
    }
}
