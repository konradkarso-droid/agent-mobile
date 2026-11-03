package com.uroboros.memory.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Паспорт агента (Passport): тексты строк, откуда берутся значения, что
 * предлагать. Главное — молчание: без своего имени агента нет строки имени,
 * решённое не предлагается заново.
 */
class PassportTest {

    private fun said(answer: String, heard: String = "") = Passport.Said(answer, heard)

    @Test
    fun `строки и их вид`() {
        val name = Passport.nameLine("Александр")
        assertEquals("Меня зовут Александр. Это имя я выбрал себе сам.", name)
        assertEquals(Passport.Kind.NAME, Passport.kindOf(name))
        assertEquals(Passport.Kind.GENDER, Passport.kindOf(Passport.GENDER_LINE))
        val owner = Passport.ownerLine("Иван", "Кэп")
        assertEquals("Иван — мой создатель и хост. Коротко его можно звать Кэп.", owner)
        assertEquals(Passport.Kind.OWNER, Passport.kindOf(owner))
        assertEquals("Иван — мой создатель и хост.", Passport.ownerLine("Иван", null))
        assertNull(Passport.kindOf("Долгое время мы с собеседником чаще всего говорили о радуге."))
    }

    @Test
    fun `имя — последнее своё, названное не меньше двух раз`() {
        val n = Passport.namings(
            listOf(
                said("Вспомнил: имя — Александр.", "Вспомнишь, какое имя ты себе выбрал?"),
                said("Привет! Я в порядке."),
                said("Да, Александр — имя, которое я себе выбрал.", "Помнишь своë имя?"),
            )
        )
        assertEquals(2, n.counts["Александр"])
        assertEquals("Александр", Passport.chosenName(n))
    }

    @Test
    fun `назвался один раз — имени не предлагает`() {
        val n = Passport.namings(listOf(said("Имя выбрал себе: Александр.")))
        assertNull(Passport.chosenName(n))
        assertTrue(Passport.meterLine(n, null, null, emptyList()).contains("«Александр» названо 1 раз из 2"))
    }

    @Test
    fun `сменил имя — берётся последнее, если и его назвал дважды`() {
        val n = Passport.namings(
            listOf(said("Меня зовут Флориан."), said("Меня зовут Флориан."), said("Меня зовут Александр.")),
        )
        assertNull(Passport.chosenName(n))
        val n2 = Passport.namings(
            listOf(said("Меня зовут Флориан."), said("Меня зовут Александр."), said("Меня зовут Александр.")),
        )
        assertEquals("Александр", Passport.chosenName(n2))
    }

    @Test
    fun `имя, повторённое за владельцем, не в счёт`() {
        val n = Passport.namings(listOf(said("Меня зовут Иван.", "Меня зовут Иван."), said("Меня зовут Иван.", "Я Иван.")))
        assertNull(Passport.chosenName(n))
    }

    @Test
    fun `прозвище владельца — из его своих слов, последнее`() {
        assertEquals("Кэп", Passport.nickname(listOf("Моë имя - Иван, прозвище - Кэп.", "А ты - кто?")))
        assertEquals("Кэп", Passport.nickname(listOf("Иван - моë имя, моë прозвище - Кэп.")))
        assertNull(Passport.nickname(listOf("Меня зовут Иван.", "Зови меня как хочешь.")))
    }

    @Test
    fun `пусто — предлагаются все три по порядку`() {
        assertEquals(
            listOf(Passport.nameLine("Александр"), Passport.GENDER_LINE, Passport.ownerLine("Иван", "Кэп")),
            Passport.proposals("Александр", "Иван", "Кэп", emptyList()),
        )
    }

    @Test
    fun `без имени — ни имени, ни рода, владелец сам по себе`() {
        assertEquals(listOf(Passport.ownerLine("Иван", null)), Passport.proposals(null, "Иван", null, emptyList()))
        assertTrue(Passport.proposals(null, null, null, emptyList()).isEmpty())
    }

    @Test
    fun `ждущее и принятое не предлагается второй раз`() {
        val existing = listOf(
            Passport.Existing(Passport.nameLine("Александр"), pending = true, rejected = false),
            Passport.Existing(Passport.GENDER_LINE, pending = false, rejected = false),
        )
        assertEquals(listOf(Passport.ownerLine("Иван", "Кэп")), Passport.proposals("Александр", "Иван", "Кэп", existing))
        // Ждущее имя держит место и для другого имени.
        assertEquals(listOf(Passport.ownerLine("Иван", "Кэп")), Passport.proposals("Флориан", "Иван", "Кэп", existing))
    }

    @Test
    fun `отвергнутый текст не возвращается, другое имя — предлагается`() {
        val existing = listOf(Passport.Existing(Passport.nameLine("Александр"), pending = true, rejected = true))
        assertFalse(Passport.nameLine("Александр") in Passport.proposals("Александр", null, null, existing))
        assertTrue(Passport.nameLine("Флориан") in Passport.proposals("Флориан", null, null, existing))
    }

    @Test
    fun `имя в стене — по принятым строкам`() {
        assertTrue(Passport.hasName(listOf(Passport.GENDER_LINE, Passport.nameLine("Александр"))))
        assertFalse(Passport.hasName(listOf(Passport.GENDER_LINE)))
    }
}
