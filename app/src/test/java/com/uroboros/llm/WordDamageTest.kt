package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Трафарет здесь собран из короткого списка словоформ ([WordDamage.fromWords]),
 * а не из файла приложения: тест проверяет правила, а не словарь. Настоящий
 * трафарет проверяет себя сам при загрузке ([WordDamage.selfCheck]).
 *
 * Тесты на МОЛЧАНИЕ закрепляют границы из описания [WordDamage]: случайная
 * «починка» любой из них должна ломать тест заметно.
 */
class WordDamageTest {

    private val words = listOf(
        "люди", "людьми", "людям", "письмами", "память", "памяти", "воспоминания", "воспоминание",
        "особого", "раннего", "у", "меня", "нет", "для", "быстрого", "после", "фехтования", "регулирует",
        "процессы", "процессов", "агент", "по", "китайски", "будет", "как", "хорошо", "правило",
        "новых", "навыков", "обучение", "красный", "мнемоническое", "мне", "снилось", "управление",
        "то", "есть", "помнит", "чувствую",
    )
    private val d = WordDamage.fromWords(words)
    private val none = emptySet<String>()

    private fun damaged(text: String, request: Set<String> = none) = d.check(text, request)

    // --- Ловит ---

    @Test
    fun `несуществующая форма ловится`() =
        assertEquals(listOf("людьмами"), damaged("Мне снилось управление людьмами"))

    @Test
    fun `латиница вплотную к русской букве ловится`() =
        assertTrue(damaged("Особого раннего воспomинания у меня нет").any { "воспomинания" in it })

    @Test
    fun `слипшееся с латинским словом ловится`() =
        assertTrue(damaged("для быстрогоwipeания после фехтования").any { "wipe" in it })

    @Test
    fun `иероглиф вплотную к русской букве ловится`() =
        assertTrue(damaged("регулирует процессы新陈代谢а").any { "新陈代谢" in it })

    @Test
    fun `заглавная посреди русского слова ловится`() =
        assertTrue(damaged("обучение новых навЫков").any { "навЫков" in it })

    @Test
    fun `латинское слово посреди русской фразы ловится`() =
        assertTrue(damaged("Я чувствую myself").contains("myself"))

    // --- Сверка с запросом — по форме, не по основе ---

    @Test
    fun `слово из запроса в той же форме не проверяется`() =
        assertTrue(damaged("Уроборос помнит", WordDamage.requestWords("Ты — Уроборос.")).isEmpty())

    @Test
    fun `другая форма слова из запроса проверяется`() =
        assertEquals(listOf("уробороса"), damaged("у Уробороса", WordDamage.requestWords("Ты — Уроборос.")).filter { it != "у" })

    @Test
    fun `порча не прячется за настоящей формой из запроса`() =
        assertEquals(listOf("людьмами"), damaged("управление людьмами", WordDamage.requestWords("управление людьми")))

    // --- Молчание: границы ---

    @Test
    fun `настоящие слова молчат`() =
        assertTrue(damaged("Особого раннего воспоминания у меня нет.").isEmpty())

    @Test
    fun `сокращение заглавными не проверяется`() =
        assertTrue(damaged("Правило — «РОЯЗГСФ», то есть РОЯЗГСФ.").isEmpty())

    @Test
    fun `короткие слова трафаретом не проверяются`() =
        assertTrue(damaged("ыщъ ьйь").isEmpty())

    @Test
    fun `иероглифы и латиница в кавычках и скобках молчат`() =
        assertTrue(damaged("По-китайски будет как «生化性别» (shēnghuà xìngbié)").isEmpty())

    @Test
    fun `иероглиф после запятой молчит — граница, не находка`() =
        assertTrue(damaged("Хорошо,谢谢").isEmpty())

    @Test
    fun `латинское слово без кавычек — порча, даже если о нём просили`() =
        assertTrue(damaged("Красный — Red").contains("Red"))

    @Test
    fun `ошибка формы из настоящих слов молчит`() =
        assertTrue(damaged("управление людям").isEmpty())

    // --- Файл трафарета ---

    private fun file(codes: List<Long>, magic: String = "UST6", count: Int = codes.size): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(magic.toByteArray())
        out.write(byteArrayOf((count ushr 24).toByte(), (count ushr 16).toByte(), (count ushr 8).toByte(), count.toByte()))
        var prev = 0L
        for (c in codes) {
            var delta = c - prev
            prev = c
            while (true) {
                val b = (delta and 0x7f).toInt()
                delta = delta ushr 7
                if (delta != 0L) out.write(b or 0x80) else { out.write(b); break }
            }
        }
        return out.toByteArray()
    }

    /** Числа сочетаний слова «люди» — как их пишет сборщик файла. */
    private fun codesOf(word: String): List<Long> {
        fun code(c: Char): Long = when (c) { '^' -> 0L; '$' -> 1L; else -> (c - 'а' + 2).toLong() }
        val s = "^$word$"
        return (0..s.length - WordDamage.N).map { i -> (0 until WordDamage.N).fold(0L) { v, j -> (v shl 6) or code(s[i + j]) } }.sorted()
    }

    @Test
    fun `файл читается в тот же трафарет`() {
        val r = WordDamage.read(ByteArrayInputStream(file(codesOf("людьми"))))
        assertTrue(r.check("людьми", none).isEmpty())
        assertEquals(listOf("людьмами"), r.check("людьмами", none))
        assertTrue(r.selfCheck())
    }

    @Test(expected = IllegalStateException::class)
    fun `чужой файл отвергается`() {
        WordDamage.read(ByteArrayInputStream(file(codesOf("люди"), magic = "XXXX")))
    }

    @Test(expected = IllegalStateException::class)
    fun `обрезанный файл отвергается`() {
        WordDamage.read(ByteArrayInputStream(file(codesOf("люди"), count = 10)))
    }

    @Test
    fun `самопроверка проваливается на пустом по смыслу трафарете`() =
        assertFalse(WordDamage.fromWords(listOf("кот")).selfCheck())
}
