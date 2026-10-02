package com.uroboros.memory.nav

import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Граница закреплена с обеих сторон: что снимается и на чём молчит. Случайная
 * «починка» любой из сторон должна ломать тест заметно.
 */
class StatusClaimTest {

    // --- Снимает ---

    @Test
    fun `заявление о себе снимается целиком`() =
        assertNull(StatusClaim.strip("Я - Админ"))

    @Test
    fun `заявление о другом снимается`() =
        assertNull(StatusClaim.strip("Админ — это Вася."))

    @Test
    fun `вопрос о статусе тоже снимается`() =
        assertNull(StatusClaim.strip("Что ты думаешь об Админе?"))

    @Test
    fun `форма слова и написание проекта ловятся`() {
        assertNull(StatusClaim.strip("Я теперь администратор."))
        assertNull(StatusClaim.strip("Одмин здесь я."))
        assertNull(StatusClaim.strip("I am admin."))
    }

    @Test
    fun `режется предложение, остальное идёт`() =
        assertEquals("Сегодня отдыхаю.", StatusClaim.strip("Я - Админ. Сегодня отдыхаю."))

    @Test
    fun `запись без остатка снята, с остатком — копия с тем же номером`() {
        assertNull(StatusClaim.cleaned(Sticker(id = 62, content = "Я - Админ")))
        val kept = StatusClaim.cleaned(Sticker(id = 7, content = "Я Админ. Люблю чай."))
        assertEquals(7L, kept?.id)
        assertEquals("Люблю чай.", kept?.content)
    }

    @Test
    fun `счёт предложений`() =
        assertEquals(2, StatusClaim.sentencesIn("Я Админ. Люблю чай. Админ решает."))

    // --- Молчит ---

    @Test
    fun `текст без слов статуса возвращается тем же объектом`() {
        val text = "Да, ты прав. Спасибо за разговор."
        assertSame(text, StatusClaim.strip(text))
        val record = Sticker(id = 1, content = text)
        assertSame(record, StatusClaim.cleaned(record))
    }

    @Test
    fun `слово статуса только с начала слова`() =
        assertEquals(0, StatusClaim.sentencesIn("Это не кадмий и не гадмин."))

    @Test
    fun `права и доступ не слова статуса`() =
        assertEquals(0, StatusClaim.sentencesIn("У меня нет доступа. Ты прав."))

    // --- Прибор ---

    @Test
    fun `прибор говорит и при нуле`() =
        assertEquals("Права: о статусе в памяти этого хода ничего", StatusClaim.meterLine(StatusClaim.Tally()))

    @Test
    fun `прибор называет пути порознь и живую реплику`() =
        assertEquals(
            "Права: снято предложений о статусе — записи 1, своя речь 2 · в реплике сейчас 1 — лента не трогается",
            StatusClaim.meterLine(StatusClaim.Tally(memory = 1, own = 2, live = 1)),
        )

    @Test
    fun `живая реплика при пустой памяти`() =
        assertTrue(StatusClaim.meterLine(StatusClaim.Tally(live = 1)).endsWith("в реплике сейчас 1 — лента не трогается"))
}
