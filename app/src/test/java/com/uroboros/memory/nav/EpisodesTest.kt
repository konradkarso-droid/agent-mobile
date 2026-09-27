package com.uroboros.memory.nav

import com.uroboros.memory.nav.Episodes.Point
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodesTest {

    private val min = 60_000L
    private val hour = Episodes.EPISODE_SILENCE_MS

    @Test
    fun `тишина дольше часа открывает новый эпизод`() {
        val eps = Episodes.number(listOf(Point(0), Point(2 * min), Point(2 * min + hour + 1)))
        assertEquals(listOf(0, 0, 1), eps)
    }

    @Test
    fun `пауза ровно в час эпизод не открывает`() {
        assertEquals(listOf(0, 0), Episodes.number(listOf(Point(0), Point(hour))))
    }

    @Test
    fun `закрытие ленты открывает новый эпизод без паузы`() {
        val eps = Episodes.number(listOf(Point(0), Point(min, closedBefore = true)))
        assertEquals(listOf(0, 1), eps)
    }

    @Test
    fun `отрицательная пауза эпизод не открывает и не прячет`() {
        // Часы перевели на два часа назад между вторым и третьим ходом.
        val eps = Episodes.number(listOf(Point(10 * hour), Point(10 * hour + min), Point(8 * hour), Point(8 * hour + min)))
        assertEquals(listOf(0, 0, 0, 0), eps)
        // А настоящая тишина после перевода часов — считается от переведённого хода.
        val later = Episodes.number(listOf(Point(10 * hour), Point(8 * hour), Point(8 * hour + hour + 1)))
        assertEquals(listOf(0, 0, 1), later)
    }

    @Test
    fun `ход без времени эпизод не открывает`() {
        assertEquals(listOf(0, 0, 0), Episodes.number(listOf(Point(0), Point(null), Point(min))))
    }

    @Test
    fun `часы эпизодов по времени — запись в разговоре и после тишины`() {
        val clock = Episodes.Clock(listOf(0L, 10 * min, 3 * hour, 3 * hour + min), emptyList())
        assertEquals(0, clock.episodeAt(5 * min))
        assertEquals(1, clock.episodeAt(3 * hour + min))
    }

    @Test
    fun `часы эпизодов — закрытие между моментами делит разговор`() {
        val clock = Episodes.Clock(listOf(0L, 10 * min), listOf(5 * min))
        assertEquals(0, clock.episodeAt(0L))
        assertEquals(1, clock.episodeAt(10 * min))
    }

    @Test
    fun `соседи — только в том же эпизоде`() {
        val eps = listOf(0, 0, 1, 1)
        assertEquals(Pair(0, null), Episodes.neighbours(eps, 1))
        assertEquals(Pair(null, 3), Episodes.neighbours(eps, 2))
    }
}
