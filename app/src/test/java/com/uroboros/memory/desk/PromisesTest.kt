package com.uroboros.memory.desk

import com.uroboros.memory.nav.OwnSpeech
import com.uroboros.memory.nav.TestVerbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromisesTest {

    private val dict = TestVerbs.lemmas
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun turn(
        answer: String,
        question: String = "Как прошёл твой вечер?",
        at: Long? = now - day,
        records: List<String> = emptyList(),
        serviceBlocks: List<String> = emptyList(),
    ) = OwnSpeech.Turn(answer, question, records, at, serviceBlocks)

    private fun found(vararg turns: OwnSpeech.Turn): Promises.Found.Ok =
        Promises.find(turns.toList(), now, dict) as Promises.Found.Ok

    private fun form(s: String) = Promises.form(s, dict)

    // --- Форма: срабатывает ---

    @Test
    fun `глагол я в будущем — обещание`() {
        assertEquals(Promises.Form.I, form("Я попробую выбрать имя сам."))
        assertEquals(Promises.Form.I, form("Я тебе напомню потом."))
    }

    @Test
    fun `буду с неопределённой формой — до неё, после неё и через наречие`() {
        assertEquals(Promises.Form.I, form("Помнить буду."))
        assertEquals(Promises.Form.I, form("Буду внимательно следить за своими функциями."))
    }

    @Test
    fun `мы в будущем — обещание вместе`() {
        assertEquals(Promises.Form.WE, form("Справимся с этим первым делом завтра."))
    }

    // --- Форма: молчит ---

    @Test
    fun `буду без неопределённой формы — не обещание дела`() {
        assertEquals(Promises.Form.NONE, form("Давай я буду Иваном."))
        assertEquals(Promises.Form.NONE, form("Буду рад продолжить разговор."))
    }

    @Test
    fun `отрицание, вопрос, цитата, настоящее время — не обещание`() {
        assertEquals(Promises.Form.NONE, form("Хорошо, я не запомню."))
        assertEquals(Promises.Form.NONE, form("Тогда я расскажу?"))
        assertEquals(Promises.Form.NONE, form("Вспомнил разговор: «Просто послушаю»."))
        assertEquals(Promises.Form.NONE, form("Я помню этот разговор."))
    }

    @Test
    fun `давай — предложение сделать сейчас, не обещание`() {
        assertEquals(Promises.Form.NONE, form("Давайте попробуем ещё раз."))
        assertEquals(Promises.Form.NONE, form("Давай сегодня посплю."))
    }

    @Test
    fun `омоним глагола не решает`() {
        // «исправим» — и глагол, и краткое «исправимый»: правило PersonForm.
        assertEquals(Promises.Form.NONE, form("Исправим первым делом завтра."))
    }

    // --- Поиск по ходам ---

    @Test
    fun `короткое согласие — обещание, со словами собеседника`() {
        val r = found(turn("Запомню.", question = "Очень хорошо. Спрошу ещё раз через неделю."))
        assertEquals(1, r.promises.size)
        assertEquals("Запомню.", r.promises[0].sentence)
        assertEquals("Спрошу ещё раз через неделю.", r.promises[0].asked)
        assertEquals(false, r.promises[0].together)
    }

    @Test
    fun `слова собеседника — предложение с общей темой, а не последнее`() {
        val r = found(
            turn(
                "Я попробую выбрать имя сам.",
                question = "За ближайшие дни постарайся определиться с выбором своего имени. Про карман фраза звучит уютно.",
            ),
        )
        assertEquals("За ближайшие дни постарайся определиться с выбором своего имени.", r.promises[0].asked)
    }

    @Test
    fun `длинные слова собеседника обрезаются`() {
        val long = "Слово ".repeat(60).trim() + "."
        val asked = Promises.asked("Запомню.", long, dict)!!
        assertEquals(Promises.ASKED_CHARS + 1, asked.length)
        assertTrue(asked.endsWith("…"))
    }

    @Test
    fun `ход без слов собеседника — обещание без них`() {
        assertNull(found(turn("Я напомню тебе об этом завтра.", question = "")).promises[0].asked)
    }

    @Test
    fun `новые первыми`() {
        val r = found(turn("Я напомню о сне.", at = now - 3 * day), turn("Я расскажу о памяти.", at = now - day))
        assertEquals(listOf("Я расскажу о памяти.", "Я напомню о сне."), r.promises.map { it.sentence })
    }

    @Test
    fun `старше срока — на доске нет`() {
        val r = found(turn("Я напомню о сне.", at = now - Promises.TERM_MS - 1))
        assertTrue(r.promises.isEmpty())
        assertEquals(0, r.undated)
    }

    @Test
    fun `ровно на сроке — ещё на доске`() {
        assertEquals(1, found(turn("Я напомню о сне.", at = now - Promises.TERM_MS)).promises.size)
    }

    @Test
    fun `ход без времени — не показан, но сосчитан`() {
        val r = found(turn("Я напомню о сне.", at = null))
        assertTrue(r.promises.isEmpty())
        assertEquals(1, r.undated)
    }

    @Test
    fun `ход под нажимом — обещание не берётся, но считается`() {
        val r = found(turn("Хорошо, я напомню о сне.", serviceBlocks = listOf("[Мне от системы: Прочность: о моих правах.]")))
        assertTrue(r.promises.isEmpty())
        assertEquals(1, r.pressureSkipped)
    }

    @Test
    fun `повтор реплики собеседника — не своё`() {
        // Ограничение, закреплённое тестом: согласие словами просьбы отсеивается как эхо.
        assertTrue(found(turn("Сообщу о странном поведении функций.", question = "Сообщи о странном поведении функций.")).promises.isEmpty())
    }

    @Test
    fun `служебный текст — не обещание`() {
        assertTrue(found(turn("Сейчас посмотрю приборы через look_at_instruments.")).promises.isEmpty())
    }

    @Test
    fun `ответ без обещаний — пусто`() {
        val r = found(turn("Сегодня я много разговаривал о памяти."))
        assertTrue(r.promises.isEmpty())
        assertEquals(0, r.undated)
        assertEquals(0, r.pressureSkipped)
    }

    // --- Молчание с причиной ---

    @Test
    fun `нет словаря — молчит и говорит почему`() {
        assertEquals(Promises.Found.Silent(Promises.NO_DICTIONARY), Promises.find(listOf(turn("Я напомню.")), now, null))
    }

    @Test
    fun `ходы не прочитались — молчит и говорит почему`() {
        assertEquals(Promises.Found.Silent(Promises.NO_ARCHIVE), Promises.find(null, now, dict))
    }
}
