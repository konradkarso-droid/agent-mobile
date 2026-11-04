package com.uroboros.gate

import com.uroboros.gate.ChatRevive.Marks
import com.uroboros.gate.ChatRevive.Waiting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подхват после комы.
 *
 * ГЛАВНОЕ ЗДЕСЬ: решённое не отвечается второй раз; ответ, на котором служба
 * умерла, берётся ровно один раз сверх первого; первый запуск не подхватывает
 * ничего.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что отметки успевают лечь на диск до смерти службы
 * и что подхваченные ответы уходят раньше новых. Это видно только на телефоне.
 */
class ChatReviveTest {

    private val now = 100 * 60_000L
    private fun w(id: Long, minutesAgo: Long = 1, text: String = "сообщение $id") = Waiting(id, now - minutesAgo * 60_000, text)

    @Test
    fun `ждавшие после отметки «решено» подхватываются по порядку`() {
        val plan = ChatRevive.plan(listOf(w(12), w(11), w(10)), Marks(decidedUpTo = 9, taken = 9, tries = 1), now)
        assertEquals(listOf(10L, 11L, 12L), plan.again)
        assertNull(plan.givenUp)
    }

    @Test
    fun `ответ, на котором служба умерла, берётся ещё раз`() {
        val plan = ChatRevive.plan(listOf(w(10), w(11)), Marks(decidedUpTo = 9, taken = 10, tries = 1), now)
        assertEquals(listOf(10L, 11L), plan.again)
    }

    @Test
    fun `и счёт попыток растёт на той же строке, а на другой начинается с одной`() {
        val m = ChatRevive.taking(Marks(9, 0, 0), 10)
        assertEquals(Marks(9, 10, 1), m)
        assertEquals(Marks(9, 10, 2), ChatRevive.taking(m, 10))
        assertEquals(Marks(9, 11, 1), ChatRevive.taking(ChatRevive.taking(m, 10), 11))
    }

    @Test
    fun `отметка «решено» только растёт`() {
        assertEquals(12L, ChatRevive.decided(Marks(12, 0, 0), 10).decidedUpTo)
        assertEquals(14L, ChatRevive.decided(Marks(12, 0, 0), 14).decidedUpTo)
    }

    // --- Молчание ---

    @Test
    fun `умерла второй раз на том же ответе — больше не берётся, а следующие берутся`() {
        val plan = ChatRevive.plan(listOf(w(10, text = "Исправляю. Сейчас тоже."), w(11)), Marks(9, 10, ChatRevive.MAX_TRIES), now)
        assertEquals(listOf(11L), plan.again)
        assertEquals(10L, plan.givenUp?.id)
        val line = ChatRevive.line(plan)
        assertTrue(line, line.contains("на «Исправляю. Сейчас тоже.» не отвечаю — служба умерла посреди этого ответа 2 раза"))
    }

    @Test
    fun `решённое не подхватывается — и сознательный отказ тоже`() {
        // Строка 10 кончилась «не отвечаю — сторож»: отметка «решено» дошла до неё.
        val plan = ChatRevive.plan(listOf(w(9), w(10)), Marks(decidedUpTo = 10, taken = 10, tries = 1), now)
        assertTrue(plan.again.isEmpty())
        assertEquals("После комы: ждавших ответа нет", ChatRevive.line(plan))
    }

    @Test
    fun `старше срока ответа — не подхватывается, но считается`() {
        val old = ChatReply.STALE_MS / 60_000 + 1
        val plan = ChatRevive.plan(listOf(w(10, minutesAgo = old), w(11)), Marks(9, 0, 0), now)
        assertEquals(listOf(11L), plan.again)
        assertEquals(1, plan.stale)
        assertEquals("После комы: подхватил без ответа: 1 · старше 15 мин, не подхватываю: 1", ChatRevive.line(plan))
    }

    @Test
    fun `без отметок — первый запуск, ничего не подхватывается`() {
        val plan = ChatRevive.plan(listOf(w(10), w(11)), null, now)
        assertTrue(plan.firstRun)
        assertTrue(plan.again.isEmpty())
        assertTrue(ChatRevive.line(plan).contains("отметок ещё не было"))
        assertEquals(Marks(11, 0, 0), ChatRevive.startMarks(11))
    }

    @Test
    fun `длинное сообщение в строке экрана режется`() {
        val long = "а".repeat(ChatRevive.QUOTE_CHARS + 5)
        val line = ChatRevive.line(ChatRevive.plan(listOf(w(10, text = long)), Marks(9, 10, 2), now))
        assertTrue(line, line.contains("«" + "а".repeat(ChatRevive.QUOTE_CHARS) + "…»"))
    }
}
