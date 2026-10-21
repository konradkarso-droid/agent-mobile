package com.uroboros.gate

import com.uroboros.gate.NameCases.Case
import com.uroboros.memory.nav.TestVerbs
import org.junit.Assert.assertEquals
import org.junit.Test

class NameCasesTest {

    private fun all(name: String) = Case.values().map { NameCases.of(name).of(it) }

    @Test
    fun `мужское на согласную`() {
        assertEquals(listOf("Борис", "Бориса", "Борису", "Бориса", "Борисом", "Борисе"), all("Борис"))
    }

    @Test
    fun `на -й, -ий, -ь`() {
        assertEquals(listOf("Андрей", "Андрея", "Андрею", "Андрея", "Андреем", "Андрее"), all("Андрей"))
        assertEquals("Дмитрии", NameCases.of("Дмитрий").of(Case.PREP))
        assertEquals("Игорем", NameCases.of("Игорь").of(Case.INS))
    }

    @Test
    fun `на -а, -я, -ия`() {
        assertEquals(listOf("Маша", "Маши", "Маше", "Машу", "Машей", "Маше"), all("Маша"))
        assertEquals("Никиты", NameCases.of("Никита").of(Case.GEN))
        assertEquals("Ильи", NameCases.of("Илья").of(Case.GEN))
        assertEquals(listOf("Мария", "Марии", "Марии", "Марию", "Марией", "Марии"), all("Мария"))
    }

    @Test
    fun `несклоняемое остаётся как есть`() {
        assertEquals(List(6) { "Тони" }, all("Тони"))
        assertEquals(List(6) { "Cap" }, all("Cap"))
    }

    @Test
    fun `склоняется только первое слово`() {
        assertEquals("агенту Бориса", NameCases.phrase("агент", "Бориса").of(Case.DAT))
    }

    @Test
    fun `род — только где уверен`() {
        assertEquals(NameCases.Gender.MASC, NameCases.genderOf("Антон"))
        assertEquals(NameCases.Gender.MASC, NameCases.genderOf("Никита"))
        assertEquals(NameCases.Gender.FEMN, NameCases.genderOf("Маша"))
        assertEquals(NameCases.Gender.UNKNOWN, NameCases.genderOf("Саша"))
        assertEquals(NameCases.Gender.UNKNOWN, NameCases.genderOf("Тони"))
    }

    @Test
    fun `правило по букве беглую гласную не знает — поэтому словарь первым`() {
        // Без словаря граница прежняя: «Павела». Со словарём — «Павла» (тесты ниже).
        assertEquals("Павела", NameCases.of("Павел").of(Case.GEN))
        assertEquals(NameCases.Source.LETTER_UNKNOWN, NameCases.of("Павел").source)
    }

    // --- Со словарём приложения (TestVerbs.lemmas) ---

    private val dict = TestVerbs.lemmas
    private fun dAll(name: String) = Case.values().map { NameCases.of(name, dict).of(it) }

    @Test
    fun `словарь — беглая гласная и женские на мягкий знак`() {
        assertEquals(listOf("Павел", "Павла", "Павлу", "Павла", "Павлом", "Павле"), dAll("Павел"))
        assertEquals("Льва", NameCases.of("Лев", dict).of(Case.GEN))
        // Имя, а не нарицательное: «Любови», не «любви».
        assertEquals(listOf("Любовь", "Любови", "Любови", "Любовь", "Любовью", "Любови"), dAll("Любовь"))
        assertEquals(NameCases.Source.DICTIONARY, NameCases.of("Павел", dict).source)
    }

    @Test
    fun `словарь — как сказано пишется общее начало`() {
        assertEquals("Артёма", NameCases.of("Артём", dict).of(Case.GEN))
        assertEquals("Борису", NameCases.of("Борис", dict).of(Case.DAT))
        // Творительный — короткая форма.
        assertEquals("Машей", NameCases.of("Маша", dict).of(Case.INS))
    }

    @Test
    fun `словарь — несклоняемое имя не склоняется`() {
        assertEquals(List(6) { "Алсу" }, dAll("Алсу"))
        assertEquals(List(6) { "Тони" }, dAll("Тони"))
    }

    @Test
    fun `двоякое и незнакомое имя — по букве, и это видно`() {
        // «Мишель»: мужское склоняется, женское нет — словарь не решает.
        val m = NameCases.of("Мишель", dict)
        assertEquals(NameCases.Source.LETTER_AMBIGUOUS, m.source)
        assertEquals("Мишеля", m.of(Case.GEN))
        val cap = NameCases.of("Кэп", dict)
        assertEquals(NameCases.Source.LETTER_UNKNOWN, cap.source)
        assertEquals("Кэпа", cap.of(Case.GEN))
        assertEquals(NameCases.Source.LETTER_UNKNOWN, NameCases.of("Hope", dict).source)
    }

    @Test
    fun `род по словарю — только где словарь уверен`() {
        assertEquals(NameCases.Gender.MASC, NameCases.genderOf("Павел", dict))
        assertEquals(NameCases.Gender.FEMN, NameCases.genderOf("Любовь", dict))
        assertEquals(NameCases.Gender.MASC, NameCases.genderOf("Тони", dict))
        // Общий род и двоякое — не знаю: местоимения не трогаются.
        assertEquals(NameCases.Gender.UNKNOWN, NameCases.genderOf("Саша", dict))
        assertEquals(NameCases.Gender.UNKNOWN, NameCases.genderOf("Паша", dict))
        assertEquals(NameCases.Gender.UNKNOWN, NameCases.genderOf("Мишель", dict))
        // Нет в словаре — правило по букве, как без словаря.
        assertEquals(NameCases.Gender.MASC, NameCases.genderOf("Кэп", dict))
    }
}
