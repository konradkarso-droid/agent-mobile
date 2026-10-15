package com.uroboros.memory.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Словарь начальных форм на настоящем файле приложения
 * (`app/src/main/assets/lemma_forms.txt`) и на маленьких файлах того же формата.
 * Полная сверка со словарём-источником (все формы) — на стенде, см.
 * `lemma_forms_SOURCE.md`; здесь — что разбор находится, когда должен, и
 * молчит, когда не должен.
 */
class LemmaDictionaryTest {

    private val dictionary: LemmaDictionary by lazy {
        val file = listOf("src/main/assets/lemma_forms.txt", "app/src/main/assets/lemma_forms.txt")
            .map { File(it) }.first { it.exists() }
        file.bufferedReader(Charsets.UTF_8).useLines { LemmaDictionary.read(it) }
    }

    private fun small(vararg lines: String) = LemmaDictionary.read(lines.asSequence())

    @Test
    fun `начальная форма у неправильных форм`() {
        assertEquals(setOf("человек"), dictionary.lemmas("людьми"))
        assertEquals(setOf("идти"), dictionary.lemmas("шёл"))
        assertEquals(setOf("я"), dictionary.lemmas("меня"))
        assertEquals(setOf("хороший"), dictionary.lemmas("наилучший"))
    }

    @Test
    fun `регистр и ё не важны`() {
        assertEquals(dictionary.analyze("шел"), dictionary.analyze("Шёл"))
        assertEquals(setOf("рубанок"), dictionary.lemmas("РУБАНОК"))
    }

    @Test
    fun `часть речи и лицо`() {
        val a = dictionary.analyze("болтаем")
        assertTrue(a.any { it.lemma == "болтать" && it.partOfSpeech == "VERB" && "1per" in it.tags && "plur" in it.tags })
        assertEquals(setOf("ADVB"), dictionary.analyze("совсем").map { it.partOfSpeech }.toSet())
        assertTrue(dictionary.analyze("Иван").any { it.partOfSpeech == "NOUN" && "Name" in it.tags })
    }

    @Test
    fun `наклонение, время и пометки местоимений`() {
        fun tags(w: String) = dictionary.analyze(w).map { it.tags }
        // Повелительное на «-ь» и прошедшее на «-ли» различаются пометкой, а не окончанием.
        assertTrue(tags("проверь").any { "VERB" in it && "impr" in it })
        assertTrue(tags("перевели").all { "past" in it })
        assertFalse(tags("перевели").any { "impr" in it })
        // «пойдём» — и будущее 1-го лица, и «давай вместе».
        assertTrue(tags("пойдём").any { "futr" in it && "1per" in it })
        assertTrue(tags("пойдём").any { "impr" in it && "incl" in it })
        assertTrue(tags("твою").all { "Apro" in it })
        assertTrue(tags("зачем").any { "Ques" in it })
        assertTrue(tags("кстати").any { "Prnt" in it })
        // Существительное и наречие повелительными не бывают.
        assertFalse(tags("люди").any { "impr" in it })
        assertFalse(tags("теперь").any { "impr" in it })
    }

    @Test
    fun `омоним отдаёт все разборы`() {
        assertEquals(setOf("вина", "вино"), dictionary.lemmas("вина"))
        assertEquals(setOf("NOUN", "VERB"), dictionary.analyze("стекло").map { it.partOfSpeech }.toSet())
    }

    @Test
    fun `дефис внутри слова`() {
        assertEquals(setOf("по-русски"), dictionary.lemmas("по-русски"))
    }

    @Test
    fun `незнакомое слово молчит, без догадки`() {
        for (w in listOf("сплетенить", "людьмами", "кэп", "дебажить", "hello", "", "12", "рубанок!", "  ")) {
            assertTrue("«$w» не должно разбираться", dictionary.analyze(w).isEmpty())
            assertFalse(dictionary.knows(w))
        }
    }

    @Test
    fun `кусок слова — не слово`() {
        assertFalse(dictionary.knows("рубанк"))
        assertFalse(dictionary.knows("людьм"))
    }

    @Test
    fun `маленький файл - основа, приставка и пустая основа`() {
        val d = small(
            "LEM1",
            "T 2", "NOUN,sing", "COMP",
            "E 4", "|ок|0", "|ка|0", "по|ольше|1", "|ольшой|0",
            "P 2", "ок|0 1", "ольшой|2 3",
            "S 2", "0б 1", "0рыв 0",
        )
        assertEquals(setOf("рывок"), d.lemmas("рывка"))
        assertEquals(setOf("большой"), d.lemmas("побольше"))
        assertTrue(d.analyze("рыв").isEmpty())
        assertTrue(d.analyze("поб").isEmpty())
        assertEquals(2, d.lexemeCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `не тот формат - ошибка, а не пустой словарь`() {
        small("UVS1", "T 0", "E 0", "P 0", "S 0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `основа без образца - ошибка`() {
        small("LEM1", "T 1", "NOUN", "E 1", "|а|0", "P 1", "|0", "S 1", "0мам 5")
    }
}
