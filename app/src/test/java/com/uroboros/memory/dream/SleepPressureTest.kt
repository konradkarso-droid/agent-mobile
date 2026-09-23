package com.uroboros.memory.dream

import com.uroboros.memory.Layer
import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Давление сна.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: неподвижная память, добавленный вопрос,
 * остывшая запись и запись, ни с кем не связавшаяся, давления не дают. Каждая
 * закрепляет границу так, что «починка» в сторону лишних ночей сломает её
 * заметно.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что давление растёт с живым разговором так, как
 * ждём. Это видно только по строке на экране.
 */
class SleepPressureTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun rec(id: Long, text: String, at: Long, hidden: Boolean = false, layer: Layer = Layer.GREEN) =
        Sticker(id = id, content = text, createdAt = at, reviewPending = hidden, layer = layer.name)

    /** Три утверждения подряд без общих слов — три сна «по времени». */
    private val base = listOf(
        rec(1, "Всегда носи с собой полотенце", at = 0),
        rec(2, "Делать нужно хорошо", at = minute),
        rec(3, "Алеет солнце на закате", at = 2 * minute),
    )

    /** Ночь над этими записями — так, как её положил бы ночной проход. */
    private fun nightOver(records: List<Sticker>): Pair<DreamNight, List<Dream>> {
        val woven = DreamWeaver.weave(records)
        val night = DreamNight.of(NIGHT, woven)
        val rows = woven.dreams.map { Dream(NIGHT, it.recordIds.joinToString(","), it.kind.name) }
        return night to rows
    }

    private fun measure(now: List<Sticker>, then: List<Sticker>): SleepPressure.Reading {
        val (night, rows) = nightOver(then)
        return SleepPressure.measure(now, night, rows)
    }

    @Test
    fun `неподвижная память — давления нет`() {
        val r = measure(base, base)
        assertEquals(0, r.changed)
        assertTrue(SleepPressure.line(r), SleepPressure.line(r).contains("не сплёл бы ничего иначе"))
    }

    @Test
    fun `добавленный вопрос давления не даёт`() {
        val r = measure(base + rec(4, "Где обедал воробей?", at = 3 * minute), base)
        assertEquals(0, r.changed)
    }

    @Test
    fun `остывшая запись давления не даёт`() {
        val cooled = base.map { if (it.id == 2L) it.copy(layer = Layer.BLUE.name) else it }
        assertEquals(0, measure(cooled, base).changed)
    }

    @Test
    fun `запись, ни с кем не связавшаяся, давления не даёт, и это сказано`() {
        // Через сутки, без общих слов: ни времени, ни моста.
        val r = measure(base + rec(4, "Бетономешалка мешает бетон", at = 24 * hour), base)
        assertEquals(0, r.changed)
        assertEquals(4, r.dreamersNow)
        assertEquals(3, r.dreamersThen)
        assertTrue(SleepPressure.line(r), SleepPressure.line(r).contains("ни с чем не связалась"))
    }

    @Test
    fun `новое утверждение рядом по времени — новые сны`() {
        val r = measure(base + rec(4, "Бетономешалка мешает бетон", at = 3 * minute), base)
        // Четвёртая запись связывается по времени с каждой из трёх.
        assertEquals(3, r.appeared)
        assertEquals(0, r.gone)
        assertTrue(SleepPressure.line(r), SleepPressure.line(r).startsWith("Давление сна: 3 — "))
    }

    @Test
    fun `скрытая после ночи запись уносит свои сны`() {
        val hidden = base.map { if (it.id == 3L) it.copy(reviewPending = true) else it }
        val r = measure(hidden, base)
        // Запись 3 была в двух снах: с 1 и со 2.
        assertEquals(0, r.appeared)
        assertEquals(2, r.gone)
    }

    @Test
    fun `ночей не было — давление равно первой ночи`() {
        val r = SleepPressure.measure(base, null, emptyList())
        assertEquals(3, r.appeared)
        assertEquals(null, r.dreamersThen)
        assertTrue(SleepPressure.line(r), SleepPressure.line(r).contains("ночей ещё не было"))
    }

    @Test
    fun `сравнение идёт по цепочкам, а не по строке базы`() {
        // Та же ночь, записанная с пробелами после запятых, — те же сны.
        val (night, rows) = nightOver(base)
        val spaced = rows.map { it.copy(recordIds = it.recordIds.replace(",", ", ")) }
        assertEquals(0, SleepPressure.measure(base, night, spaced).changed)
    }

    @Test
    fun `сны реки не читаются ушедшими`() {
        val (night, rows) = nightOver(base)
        val withRiver = rows + Dream(NIGHT, "1,2,3", DreamRiver.KIND)
        assertEquals(0, SleepPressure.measure(base, night, withRiver).changed)
    }

    @Test
    fun `вспомненный сон — приток, и это давление`() {
        val (night, rows) = nightOver(base)
        val recalled = rows.mapIndexed { i, d -> if (i == 0) d.copy(lastRecalledAt = 5L) else d }
        val r = SleepPressure.measure(base, night, recalled)
        assertEquals(1, r.tributaries)
        assertEquals(1, r.changed)
        assertTrue(SleepPressure.line(r), SleepPressure.line(r).contains("притоков реки 1"))
    }

    private companion object {
        const val NIGHT = 1_000L
    }
}
