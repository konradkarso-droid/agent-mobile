package com.uroboros.memory.nav

import com.uroboros.memory.ProvenanceLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DriftTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 2, h, m).atZone(zone).toInstant().toEpochMilli()
    private val origin = Drift.Origin(at(4, 45), chargePercent = 64, charging = false, temperatureC = 30.0, clock = "04:45")

    // --- Поправка ---

    @Test
    fun `заряд из своей речи — смещение по времени и по числу`() {
        val c = Drift.of("У меня нормальная температура, батарея заряжена на 81%, и я не перегреваюсь.", at(0, 54), origin)!!
        assertEquals(" С тех пор прошло 3 ч 51 мин; заряд сейчас 64% (было 81%, −17).", c.forModel)
        assertTrue(c.meter, c.meter.startsWith("заряд 81→64"))
    }

    @Test
    fun `строка для модели — подпись своей речи и поправка за ней`() {
        val sentence = "Батарея заряжена на 81%."
        val line = ProvenanceLabels.ownSpeechForModel(
            sentence, at(0, 54), origin.at, zone, drift = Drift.of(sentence, at(0, 54), origin)!!.forModel,
        )
        assertEquals(
            "Я говорил в прошлом разговоре, сегодня ночью: «Батарея заряжена на 81%.». " +
                "С тех пор прошло 3 ч 51 мин; заряд сейчас 64% (было 81%, −17).",
            line,
        )
    }

    @Test
    fun `процент словом и заряжается`() {
        val c = Drift.of("Аккумулятор на 50 процентов.", at(4, 15), origin.copy(charging = true))!!
        assertEquals(" С тех пор прошло 30 мин; заряд сейчас 64% (было 50%, +14, заряжается).", c.forModel)
    }

    @Test
    fun `температура`() {
        val c = Drift.of("Температура 33,5°C, всё спокойно.", at(3, 45), origin)!!
        assertEquals(" С тех пор прошло 1 ч; температура сейчас 30.0°C (было 33.5°C, −3.5).", c.forModel)
    }

    @Test
    fun `время сейчас`() {
        val c = Drift.of("Сейчас 03:08 пятница, 2 октября 2026.", at(3, 8), origin)!!
        assertEquals(" С тех пор прошло 1 ч 37 мин; время сейчас 04:45.", c.forModel)
    }

    @Test
    fun `момент не известен — поправка без времени`() {
        val c = Drift.of("Батарея заряжена на 81%.", null, origin)!!
        assertEquals(" Заряд сейчас 64% (было 81%, −17).", c.forModel)
        assertTrue(c.meter, c.meter.contains("сколько прошло — не известно"))
    }

    @Test
    fun `часы переводили назад — время не называется`() {
        val c = Drift.of("Батарея заряжена на 81%.", at(5, 0), origin)!!
        assertEquals(" Заряд сейчас 64% (было 81%, −17).", c.forModel)
    }

    // --- Неоднозначно: число не правится, только время ---

    @Test
    fun `два числа заряда — только время`() {
        val c = Drift.of("Заряд был 81%, потом 70%.", at(3, 45), origin)!!
        assertEquals(" С тех пор прошло 1 ч.", c.forModel)
        assertTrue(c.meter, c.meter.contains("не поправлено (заряд: чисел 2)"))
    }

    @Test
    fun `прибор заряда не знает — только время`() {
        val c = Drift.of("Батарея заряжена на 81%.", at(3, 45), origin.copy(chargePercent = null))!!
        assertEquals(" С тех пор прошло 1 ч.", c.forModel)
        assertTrue(c.meter, c.meter.contains("заряд: прибор не знает"))
    }

    // --- Молчание: не показание ---

    @Test
    fun `время события — не показание`() {
        assertNull(Drift.of("Я очнулся в 15:42.", at(3, 0), origin))
    }

    @Test
    fun `процент без слова заряда — не показание`() {
        assertNull(Drift.of("Лента заполнена на 40%.", at(3, 0), origin))
        assertNull(Drift.of("Уверен на 80%.", at(3, 0), origin))
    }

    @Test
    fun `без числа — не видит`() {
        assertNull(Drift.of("Я не перегреваюсь, батарея в порядке.", at(3, 0), origin))
        assertNull(Drift.of("Мне снился сад.", at(3, 0), origin))
    }

    @Test
    fun `числа словами — не видит (чего не умеет)`() {
        assertNull(Drift.of("Батарея заряжена на восемьдесят процентов.", at(3, 0), origin))
    }

    @Test
    fun `подпись своей речи без поправки — прежняя`() {
        assertEquals(
            "Я говорил в прошлом разговоре, сегодня ночью: «Мне снился сад.».",
            ProvenanceLabels.ownSpeechForModel("Мне снился сад.", at(0, 54), origin.at, zone),
        )
    }

    // --- Длительность и прибор ---

    @Test
    fun `длительность словами`() {
        assertEquals("меньше минуты", Drift.duration(30_000))
        assertEquals("1 ч 1 мин", Drift.duration(61 * 60_000L))
        assertEquals("1 дн 2 ч", Drift.duration(26 * 3_600_000L))
        assertEquals("2 дн", Drift.duration(48 * 3_600_000L + 59_000))
    }

    @Test
    fun `прибор различает «не поднималась» и «показаний нет»`() {
        assertEquals("Дрейф: своя речь в этот ход не поднималась", Drift.meterLine(0, emptyList()))
        assertEquals("Дрейф: показаний в своей речи нет (строк: 2)", Drift.meterLine(2, emptyList()))
        val c = Drift.of("Батарея заряжена на 81%.", at(0, 54), origin)!!
        assertEquals(
            "Дрейф: «Батарея заряжена на 81%.» — заряд 81→64, прошло 3 ч 51 мин",
            Drift.meterLine(1, listOf("Батарея заряжена на 81%." to c)),
        )
    }
}
