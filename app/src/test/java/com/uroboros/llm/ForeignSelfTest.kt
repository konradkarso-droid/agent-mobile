package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForeignSelfTest {

    private val recall = listOf(
        "Твои слова вчера вечером: «Я состою в клубе исторического фехтования. Это было давно».",
        "Твои слова на днях: «Praxis: Я люблю ездить на поездах.».",
        "Я говорил на днях: «Я помню твои записи».",
        "В этом чате, сегодня: «Я тут новенький в группе».",
        "Твои слова сегодня утром: «Я тут.».",
        "С твоих слов на днях: ты работаешь по субботам.",
    ).joinToString("\n")

    @Test
    fun `фразы собеседника — из «Твои слова», с «я», подпись снята, короткие и чужие строки — нет`() {
        assertEquals(
            listOf("Я состою в клубе исторического фехтования.", "Я люблю ездить на поездах."),
            ForeignSelf.theirWords(recall),
        )
    }

    @Test
    fun `нет записей — нет фраз`() {
        assertEquals(emptyList<String>(), ForeignSelf.theirWords(null))
    }

    private val theirs = listOf("Я состою в клубе исторического фехтования.")

    @Test
    fun `дословная копия ловится, и в списке, и без точки, и с другим регистром`() {
        assertEquals("Я состою в клубе исторического фехтования.", ForeignSelf.find("Пользователь спросил о фехтовании:\n\n- Я состою в клубе исторического фехтования.", theirs))
        assertEquals("я состою в клубе исторического фехтования, и это здорово!", ForeignSelf.find("я состою в клубе исторического фехтования, и это здорово!", theirs))
    }

    @Test
    fun `молчит на пересказе, цитате и своих словах`() {
        assertNull(ForeignSelf.find("Ты состоишь в клубе исторического фехтования.", theirs))
        assertNull(ForeignSelf.find("Ты говорил: «Я состою в клубе исторического фехтования».", theirs))
        assertNull(ForeignSelf.find("Я тоже люблю историю.", theirs))
        assertNull(ForeignSelf.find("Я состою в клубе исторического фехтования.", emptyList()))
    }
}
