package com.uroboros.gate

import com.uroboros.gate.NameCases.Case
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
    fun `беглую гласную не знает — граница записана`() {
        // «Павел» → правильно «Павла»; правило по букве даёт «Павела». Тест держит
        // границу на виду: починка (словарь с падежами) обязана его поменять.
        assertEquals("Павела", NameCases.of("Павел").of(Case.GEN))
    }
}
