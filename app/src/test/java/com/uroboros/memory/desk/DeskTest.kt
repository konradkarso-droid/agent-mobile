package com.uroboros.memory.desk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskTest {

    private val silentQuestion = Desk.Question.Refuse("лидера нет")

    private fun inputs(
        night: Desk.Night? = Desk.Night(1_000L, null),
        conclusions: Desk.NightConclusions? = Desk.NightConclusions(0, emptyList(), 0),
        question: Desk.Question = silentQuestion,
    ) = Desk.Inputs(night, conclusions, question, ageForModel = { "сегодня днём" }, moment = { "27.09 10:53" })

    private fun kinds(p: Desk.Projection) = p.items.map { it.kind }

    // --- Молчание ---

    @Test
    fun `ночей не было — доска пуста и называет причину у каждого вида`() {
        val p = Desk.project(inputs(night = null, conclusions = null))
        assertTrue(p.items.isEmpty())
        assertEquals(Desk.NO_NIGHTS, p.emptyReasons[Desk.Kind.WRONG])
        assertEquals(Desk.NO_NIGHTS, p.emptyReasons[Desk.Kind.LINK])
        assertEquals("любопытство — лидера нет", p.emptyReasons[Desk.Kind.QUESTION])
    }

    @Test
    fun `шага тем не было — это не «не так»`() {
        val p = Desk.project(inputs(night = Desk.Night(1L, null)))
        assertTrue(p.items.isEmpty())
        assertEquals("в последнюю ночь шага тем не было, выводов не было", p.emptyReasons[Desk.Kind.WRONG])
    }

    @Test
    fun `темы приняты — молчит`() {
        val p = Desk.project(inputs(night = Desk.Night(1L, "память\nправило")))
        assertTrue(p.items.none { it.kind == Desk.Kind.WRONG })
    }

    @Test
    fun `выводы приняты — «не так» по выводам молчит, связь есть`() {
        val p = Desk.project(inputs(conclusions = Desk.NightConclusions(3, listOf("обе записи о правиле"), 0)))
        assertEquals(listOf(Desk.Kind.LINK), kinds(p))
        assertEquals("Когда я спал сегодня днём, я подумал, что обе записи о правиле.", p.items[0].forModel)
    }

    @Test
    fun `принятые молчат — не «не так» и не связь, причина названа`() {
        val p = Desk.project(inputs(conclusions = Desk.NightConclusions(2, emptyList(), 1)))
        assertTrue(p.items.isEmpty())
        assertEquals("принятые молчат — звено на проверке или удалено", p.emptyReasons[Desk.Kind.LINK])
    }

    // --- Срабатывание ---

    @Test
    fun `шаг тем ничего не принял — пункт «не так» от первого лица`() {
        val p = Desk.project(inputs(night = Desk.Night(1L, "")))
        assertEquals(listOf(Desk.Kind.WRONG), kinds(p))
        assertEquals("Когда я спал сегодня днём, я не нашёл ни одной темы для своих снов.", p.items[0].forModel)
        assertNull(p.emptyReasons[Desk.Kind.WRONG])
    }

    @Test
    fun `все выводы отброшены — пункт «не так»`() {
        val p = Desk.project(inputs(conclusions = Desk.NightConclusions(3, emptyList(), 0)))
        assertEquals(listOf(Desk.Kind.WRONG), kinds(p))
        assertEquals("ночь 27.09 10:53: ни один вывод не принят из 3", p.items[0].forOwner)
        assertEquals("в последнюю ночь ни один вывод не принят", p.emptyReasons[Desk.Kind.LINK])
    }

    @Test
    fun `вопрос о сне — пункт без своей строки модели`() {
        val p = Desk.project(inputs(question = Desk.Question.Ask("сон «а → б»")))
        assertEquals(listOf(Desk.Kind.QUESTION), kinds(p))
        assertNull(p.items[0].forModel)
    }

    @Test
    fun `окно — не больше трёх, по порядку видов`() {
        val p = Desk.project(
            inputs(
                night = Desk.Night(1L, ""),
                conclusions = Desk.NightConclusions(5, listOf("а", "б"), 0),
                question = Desk.Question.Ask("сон «в»"),
            ),
        )
        assertEquals(listOf(Desk.Kind.WRONG, Desk.Kind.LINK, Desk.Kind.LINK, Desk.Kind.QUESTION), kinds(p))
        assertEquals(listOf(Desk.Kind.WRONG, Desk.Kind.LINK, Desk.Kind.LINK), Desk.window(p).map { it.kind })
    }

    // --- Прибор ---

    @Test
    fun `прибор печатается и на пустой доске — с причинами, обещаниями и без подачи`() {
        val line = Desk.meter(Desk.project(inputs(night = null, conclusions = null)))
        assertEquals(
            "Доска: пунктов 0 (не так 0 — ночей не было · связи снов 0 — ночей не было · " +
                "вопрос 0 — любопытство — лидера нет · обещания — не собираются) · модели не подаётся",
            line,
        )
    }

    @Test
    fun `раздел показывает окно и пустые виды`() {
        val text = Desk.section(Desk.project(inputs(night = Desk.Night(1L, ""))))
        assertTrue(text.startsWith("ДОСКА\n"))
        assertTrue(text.contains("Когда я спал сегодня днём, я не нашёл ни одной темы для своих снов."))
        assertTrue(text.contains("связи снов: выводов в последнюю ночь не было"))
        assertTrue(text.endsWith(Desk.PROMISES))
    }
}
