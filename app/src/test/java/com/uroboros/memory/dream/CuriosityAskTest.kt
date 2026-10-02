package com.uroboros.memory.dream

import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выход «спросить» (CuriosityAsk).
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: нулевое давление, вклад ниже порога,
 * прошлый вопрос без ответа и уже спрошенный сон вопроса не дают. Выход
 * терминальный, и лишний вопрос ниже никто не отсеет.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что порог 6 верен (он объявлен) и что модель,
 * получив строку, спросит.
 */
class CuriosityAskTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private val live = (1L..9L).associateWith { Sticker(id = it, content = "Запись номер $it лежит в памяти") }

    private fun dream(vararg ids: Long, picked: Int = 0, recalled: Int = 0, askedAt: Long? = null, answered: Int = 0) =
        Dream(
            nightAt = now - day,
            recordIds = ids.joinToString(","),
            kind = DreamWeaver.Kind.TIME.name,
            pickedUpCount = picked,
            recalledCount = recalled,
            askedAt = askedAt,
            answeredCount = answered,
        )

    private fun pressure(vararg dreams: Dream) = CuriosityPressure.measure(dreams.toList(), { live[it] }, now)

    private fun decide(vararg dreams: Dream, awaiting: Boolean = false) =
        CuriosityAsk.decide(pressure(*dreams), awaiting)

    @Test
    fun `нулевое давление — отказ`() {
        assertEquals(CuriosityAsk.Decision.Refuse("лидера нет"), decide())
    }

    @Test
    fun `вклад 5 — отказ, 6 — спросить`() {
        // Подхват ×3 + вспоминание ×2 = 5.
        assertEquals(
            CuriosityAsk.Decision.Refuse("вклад лидера 5 из 6"),
            decide(dream(1, 2, picked = 1, recalled = 1)),
        )
        val ask = decide(dream(1, 2, picked = 2))
        assertTrue(ask is CuriosityAsk.Decision.Ask)
        assertEquals(6, (ask as CuriosityAsk.Decision.Ask).leader.contribution)
    }

    @Test
    fun `условие — вклад лидера, а не сумма`() {
        // Сумма 3 + 3 + 3 = 9 выше порога, но ни один сон сам не выделяется.
        val got = decide(dream(1, 2, picked = 1), dream(3, 4, picked = 1), dream(5, 6, picked = 1))
        assertEquals(CuriosityAsk.Decision.Refuse("вклад лидера 3 из 6"), got)
    }

    @Test
    fun `прошлый вопрос без ответа — отказ даже при большом вкладе`() {
        assertEquals(
            CuriosityAsk.Decision.Refuse("прошлый вопрос без ответа"),
            decide(dream(1, 2, picked = 9), awaiting = true),
        )
    }

    @Test
    fun `спрошенный сон не выбирается повторно`() {
        val got = decide(dream(1, 2, picked = 9, recalled = 9, askedAt = now - 10, answered = 9))
        assertEquals(CuriosityAsk.Decision.Refuse("лидера нет"), got)
    }

    @Test
    fun `лидер по построению не молчит и не спрошен`() {
        val hidden = live + (3L to Sticker(id = 3, content = "скрытая", reviewPending = true))
        val result = CuriosityPressure.measure(
            listOf(
                dream(1, 2, picked = 9, askedAt = now - 10),
                dream(3, 4, picked = 9),
                dream(5, 6, picked = 2),
            ),
            { hidden[it] },
            now,
        )
        val leader = result.leader!!
        assertEquals("5,6", leader.dream.recordIds)
        assertEquals(null, leader.dream.askedAt)
        assertFalse(leader.records.any { Dream.silences(it) })
        assertTrue(CuriosityAsk.decide(result, awaitingAnswer = false) is CuriosityAsk.Decision.Ask)
    }

    @Test
    fun `ждёт ответа, пока после вопроса не было реплики`() {
        assertFalse("ни разу не спрашивали", CuriosityAsk.awaiting(lastAskedAt = null, lastOwnerReplyAt = null))
        assertFalse(CuriosityAsk.awaiting(lastAskedAt = null, lastOwnerReplyAt = 5))
        assertTrue("реплики владельца на диске нет — ждёт", CuriosityAsk.awaiting(lastAskedAt = 10, lastOwnerReplyAt = null))
        assertTrue("реплика с самим предложением ответом не считается", CuriosityAsk.awaiting(10, 10))
        assertTrue(CuriosityAsk.awaiting(10, 9))
        assertFalse("реплика после вопроса снимает ожидание", CuriosityAsk.awaiting(10, 11))
    }

    @Test
    fun `строки прибора`() {
        assertEquals(
            "Спросить: не спрашиваю — вклад лидера 4 из 6",
            CuriosityAsk.meter(decide(dream(1, 2, recalled = 2))),
        )
        assertEquals(
            "Спросить: не спрашиваю — прошлый вопрос без ответа",
            CuriosityAsk.meter(decide(dream(1, 2, picked = 9), awaiting = true)),
        )
        assertEquals(
            "Спросить: в этой реплике предложено спросить о сне " +
                "«по времени: Запись номер 1 лежит в памяти → Запись номер 2 лежит в памяти»",
            CuriosityAsk.meter(decide(dream(1, 2, picked = 2))),
        )
    }

    @Test
    fun `строка для модели — записи лидера, без сна и без слова «интересно»`() {
        val leader = (decide(dream(1, 2, picked = 2)) as CuriosityAsk.Decision.Ask).leader
        val line = CuriosityAsk.line(leader)
        assertEquals(
            "Меня занимает, как связано: " +
                "«Запись номер 1 лежит в памяти», «Запись номер 2 лежит в памяти». " +
                "Если к месту — спроси пользователя об этом, одним вопросом.",
            line,
        )
        assertFalse(line.contains("интерес"))
        assertFalse(line, line.contains("снилось") || line.contains("сон"))
        assertFalse("без записей ответа слова «рядом» нет", line.contains("рядом"))
    }

    @Test
    fun `строка для пути «первым» — те же записи, просьба спросить прямо`() {
        val leader = (decide(dream(1, 2, picked = 2)) as CuriosityAsk.Decision.Ask).leader
        val line = CuriosityAsk.lineFirst(leader)
        assertEquals(
            "Меня занимает, как связано: " +
                "«Запись номер 1 лежит в памяти», «Запись номер 2 лежит в памяти». " +
                "Спроси пользователя об этом, одним вопросом.",
            line,
        )
        assertFalse("без «если к месту»", line.contains("Если к месту"))
        assertFalse(line.contains("интерес"))
        assertFalse(line, line.contains("снилось") || line.contains("сон"))
    }
}
