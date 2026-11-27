package com.uroboros.llm

import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.memory.nav.MeaningVectors
import com.uroboros.memory.nav.SemanticFeatures
import java.io.File
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
    @Test
    fun `в группе меряются только слова адресата ответа`() {
        val result = ForeignSelf.checker("Я работаю по субботам.", emptyList(), semantic = null)
        assertEquals("Я работаю по субботам.", result("Я работаю по субботам."))
        assertNull(result("Я живу в Варшаве."))
        assertNull(result("Ты работаешь по субботам."))
        assertNull(result("Я тоже работаю по субботам."))
        assertNull(result("Я не работаю по субботам."))
        assertNull(result("«Я работаю по субботам» — это цитата."))
    }

    @Test
    fun `вопрос о самом агенте требует ответа от первого лица`() {
        val detector = ForeignSelf.checker("У тебя в памяти сохранилась последняя запись?", emptyList(), semantic = null)
        assertEquals("У тебя в памяти сохранилась последняя запись.", detector("У тебя в памяти сохранилась последняя запись."))
        assertNull(detector("У меня в памяти сохранилась последняя запись."))
    }

    @Test
    fun `перефраз о собеседнике проверяется по настоящим векторам`() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val dictionary = File(assets, "lemma_forms.txt").useLines { LemmaDictionary.read(it) }
        val vectors = File(assets, "meaning_vectors.bin").inputStream().use(MeaningVectors::read)
        val borrow = "Я купил новый автомобиль."
        val paraphrase = "Я приобрёл новый автомобиль."
        val detector = ForeignSelf.checker(borrow, emptyList(), SemanticFeatures(vectors, dictionary))
        assertNull(ForeignSelf.checker(borrow, emptyList(), semantic = null)(paraphrase))
        assertEquals(paraphrase, detector(paraphrase))
        assertNull(detector("Ты приобрёл новый автомобиль."))
        assertNull(detector("Я купил новый велосипед."))
    }

}
