package com.uroboros.memory.dream

import com.uroboros.llm.ConversationJournal
import com.uroboros.memory.RiskTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Зеркало ([Mirror]): сборка запроса из ленты, разбор ответа модели в
 * варианты, сверка «сбылось» и окно сверки. Проверки на молчание стоят рядом
 * с проверками срабатывания: лишнее «сбылось» заставило бы принять шум за
 * догадку, а в этом и весь смысл мерки.
 *
 * Чего эти тесты НЕ проверяют: что модель на деле даст три варианта, что они
 * дойдут до базы и что сверка стоит в ходе в нужном месте. Это проводка
 * (MirrorStep, MirrorChecker, MainActivity), её проверяет владелец на телефоне.
 */
class MirrorTest {

    private fun turn(question: String, agent: String, user: String = "ЗАПИСЬ-ПАМЯТИ $question") =
        ConversationJournal.Turn(userContent = user, agentContent = agent, question = question)

    private fun ready(history: List<ConversationJournal.Turn>) =
        Mirror.request(history) as Mirror.Request.Ready

    // ---- Сборка запроса ----

    @Test
    fun `подставленные записи памяти в запрос не попадают`() {
        val history = listOf(
            turn("Как дела?", "Хорошо.", user = "Скрытая запись про колодки\nКак дела?"),
        )
        val request = ready(history)
        assertFalse(request.text.contains("Скрытая"))
        assertFalse(request.text.contains("колодки"))
        assertTrue(request.text.contains("Как дела?"))
        assertTrue(request.text.contains("Хорошо."))
        assertFalse("колодк" in request.excludedStems.joinToString())
    }

    @Test
    fun `берутся последние три хода`() {
        val history = (1..5).map { turn("вопрос$it", "ответ$it") }
        val request = ready(history)
        assertFalse(request.text.contains("вопрос2"))
        assertFalse(request.text.contains("ответ2"))
        assertTrue(request.text.contains("вопрос3"))
        assertTrue(request.text.contains("ответ5"))
        assertEquals(3, request.fromTurn)
        assertEquals(5, request.toTurn)
    }

    @Test
    fun `короткая лента — все ходы с первого`() {
        val request = ready(listOf(turn("один", "два")))
        assertEquals(1, request.fromTurn)
        assertEquals(1, request.toTurn)
    }

    @Test
    fun `ход без реплики владельца — только ответ агента`() {
        val request = ready(listOf(turn("", "Я написал первым.")))
        assertEquals("Агент: Я написал первым.", request.text)
    }

    @Test
    fun `длинный разговор обрезается с конца`() {
        val long = "а".repeat(Mirror.MAX_CHARS * 2)
        val request = ready(listOf(turn("начало разговора", long + " КОНЕЦ")))
        assertEquals(Mirror.MAX_CHARS, request.text.length)
        assertTrue(request.text.endsWith("КОНЕЦ"))
        assertFalse(request.text.contains("начало"))
    }

    @Test
    fun `пустая лента — молчание с причиной`() {
        val request = Mirror.request(emptyList())
        assertEquals(Mirror.Request.Silent("лента пуста или не поднята"), request)
    }

    // ---- Разбор ответа ----

    @Test
    fun `нумерация, маркеры и кавычки снимаются`() {
        val answer = "1. «Расскажи про рубанки»\n2) А что с погодой?\n• \"Когда поедем на дачу?\""
        assertEquals(
            listOf("Расскажи про рубанки", "А что с погодой?", "Когда поедем на дачу?"),
            Mirror.parse(answer),
        )
        assertEquals(
            listOf("Расскажи про рубанки", "А что с погодой?", "Когда поедем на дачу?"),
            Mirror.parse("- Расскажи про рубанки\n* А что с погодой?\n— Когда поедем на дачу?"),
        )
    }

    @Test
    fun `пустые строки пропускаются`() {
        assertEquals(listOf("Первый вариант", "Второй вариант"), Mirror.parse("\n\nПервый вариант\n   \nВторой вариант\n"))
    }

    @Test
    fun `дубли по основам убираются`() {
        val answer = "Расскажи про рубанки\nРасскажи про рубанки!\nЧто с погодой?"
        assertEquals(listOf("Расскажи про рубанки", "Что с погодой?"), Mirror.parse(answer))
    }

    @Test
    fun `больше трёх — остаются первые три`() {
        val answer = "Про рубанки\nПро погоду\nПро дачу\nПро машину"
        assertEquals(listOf("Про рубанки", "Про погоду", "Про дачу"), Mirror.parse(answer))
    }

    @Test
    fun `пустой ответ — ноль вариантов`() {
        assertTrue(Mirror.parse("").isEmpty())
        assertTrue(Mirror.parse("  \n 1. \n - ").isEmpty())
    }

    // ---- Сверка ----

    @Test
    fun `две общие основы — сбылось`() {
        val checked = Mirror.check("Расскажи про рубанки и стамески", emptySet(), 0, "Хочу про рубанки и стамески")
        assertNotNull(checked)
        assertEquals(1, checked!!.repliesSeen)
        assertEquals(RiskTrigger.significantStems("рубанки стамески"), checked.fulfilledWords)
    }

    @Test
    fun `одна общая основа — не сбылось`() {
        val checked = Mirror.check("Расскажи про рубанки и стамески", emptySet(), 0, "Хочу про рубанки")
        assertEquals(Mirror.Checked(1, null), checked)
    }

    @Test
    fun `основа из разговора, на который смотрело зеркало, не засчитывается`() {
        val excluded = RiskTrigger.significantStems("Мы говорили про рубанки")
        val checked = Mirror.check("Расскажи про рубанки и стамески", excluded, 0, "Хочу про рубанки и стамески")
        assertEquals(Mirror.Checked(1, null), checked)
    }

    @Test
    fun `окно — четвёртая реплика не сверяется`() {
        val variant = "Расскажи про рубанки и стамески"
        var seen = 0
        repeat(Mirror.CHECK_WINDOW) {
            val checked = Mirror.check(variant, emptySet(), seen, "Сегодня была хорошая погода")!!
            seen = checked.repliesSeen
        }
        assertEquals(Mirror.CHECK_WINDOW, seen)
        assertNull(Mirror.check(variant, emptySet(), seen, "Хочу про рубанки и стамески"))
    }

    @Test
    fun `основы переживают строку базы`() {
        val stems = setOf("рубан", "стамеск")
        assertEquals(stems, Mirror.splitStems(Mirror.joinStems(stems)))
        assertTrue(Mirror.splitStems(null).isEmpty())
        assertTrue(Mirror.splitStems("").isEmpty())
    }
}
