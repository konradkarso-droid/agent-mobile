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
        promises: Promises.Found = Promises.Found.Ok(emptyList(), 0, 0),
    ) = Desk.Inputs(
        night, conclusions, question, ageForModel = { "сегодня днём" }, moment = { "27.09 10:53" },
        promises = promises, dayForModel = { "30 октября" },
    )

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
    fun `прибор печатается и на пустой доске — с причинами у каждого вида`() {
        val line = Desk.meter(Desk.project(inputs(night = null, conclusions = null)))
        assertEquals(
            "Доска: пунктов 0 (не так 0 — ночей не было · связи снов 0 — ночей не было · " +
                "вопрос 0 — любопытство — лидера нет · обещания 0 — за 14 дн. обещаний нет) · " +
                "модели — только обещания при взгляде на приборы",
            line,
        )
    }

    @Test
    fun `раздел показывает окно и пустые виды`() {
        val text = Desk.section(Desk.project(inputs(night = Desk.Night(1L, ""))))
        assertTrue(text.startsWith("ДОСКА\n"))
        assertTrue(text.contains("Когда я спал сегодня днём, я не нашёл ни одной темы для своих снов."))
        assertTrue(text.contains("связи снов: выводов в последнюю ночь не было"))
        assertTrue(text.contains("Агент видит при взгляде на приборы:\nОбещаний за 14 дн. не нашлось."))
    }

    // --- Обещания ---

    private val day = 24L * 60 * 60 * 1000

    private fun promise(sentence: String, asked: String? = null, at: Long = 1_000L, together: Boolean = false) =
        Promises.Promise(sentence, asked, at, together)

    @Test
    fun `обещание — строка модели с днём и словами собеседника, строка владельца со сроком`() {
        val p = Desk.project(
            inputs(
                promises = Promises.Found.Ok(
                    listOf(promise("Я попробую выбрать имя сам.", "Постарайся определиться с выбором имени.")),
                    0, 0,
                ),
            ),
        )
        assertEquals(listOf(Desk.Kind.PROMISE), kinds(p))
        assertEquals(
            "Обещал 30 октября: «Я попробую выбрать имя сам.» — в ответ на «Постарайся определиться с выбором имени.»",
            p.items[0].forModel,
        )
        assertEquals(
            "27.09 10:53 · на «Постарайся определиться с выбором имени.» → «Я попробую выбрать имя сам.» · до 27.09 10:53",
            p.items[0].forOwner,
        )
        assertNull(p.emptyReasons[Desk.Kind.PROMISE])
    }

    @Test
    fun `обещание вместе и без слов собеседника`() {
        val p = Desk.project(inputs(promises = Promises.Found.Ok(listOf(promise("Исправим завтра.", together = true)), 0, 0)))
        assertEquals("Обещали вместе 30 октября: «Исправим завтра.»", p.items[0].forModel)
        assertTrue(p.items[0].forOwner.contains("вместе · «Исправим завтра.»"))
    }

    @Test
    fun `агенту — не больше окна и сколько осталось`() {
        val all = (1..5).map { promise("Обещание $it.", at = it * day) }
        val lines = Desk.forAgent(Desk.project(inputs(promises = Promises.Found.Ok(all, 0, 0))))
        assertEquals(Desk.WINDOW + 1, lines.size)
        assertEquals("Обещал 30 октября: «Обещание 1.»", lines[0])
        assertEquals("Ещё обещаний за 14 дн.: 2.", lines.last())
    }

    @Test
    fun `агенту — пустота словами, «нет» и «не видны» разные`() {
        assertEquals(listOf("Обещаний за 14 дн. не нашлось."), Desk.forAgent(Desk.project(inputs())))
        assertEquals(
            listOf("Обещания мне сейчас не видны: ${Promises.NO_DICTIONARY}."),
            Desk.forAgent(Desk.project(inputs(promises = Promises.Found.Silent(Promises.NO_DICTIONARY)))),
        )
    }

    @Test
    fun `пустые обещания — причина со счётом без времени и под нажимом`() {
        val p = Desk.project(inputs(promises = Promises.Found.Ok(emptyList(), 2, 1)))
        assertEquals("за 14 дн. обещаний нет, без времени хода 2, под нажимом не взято 1", p.emptyReasons[Desk.Kind.PROMISE])
        assertEquals(listOf("Обещаний за 14 дн. не нашлось."), Desk.forAgent(p))
    }
}
