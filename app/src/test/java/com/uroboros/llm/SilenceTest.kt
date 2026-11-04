package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SilenceTest {

    @Test
    fun `строка молчания — причина из первой строки`() {
        assertEquals("грубит третий раз подряд", Silence.reasonOf("МОЛЧУ: грубит третий раз подряд."))
        assertEquals("пишет одно и то же", Silence.reasonOf("  молчу — пишет одно и то же\nа дальше не читается"))
        assertEquals("навязчив", Silence.reasonOf("«Молчу»: навязчив"))
    }

    @Test
    fun `без причины — так и сказано`() {
        assertEquals(Silence.NO_REASON, Silence.reasonOf("МОЛЧУ."))
        assertEquals(Silence.NO_REASON, Silence.reasonOf("Молчу"))
    }

    @Test
    fun `молчит — обычный ответ, «молчу» не первым словом, похожее слово`() {
        assertNull(Silence.reasonOf("Привет! Как дела?"))
        assertNull(Silence.reasonOf("Я молчу, потому что устал."))
        assertNull(Silence.reasonOf("Молчун ты, а не я."))
        assertNull(Silence.reasonOf("Не буду отвечать."))
        assertNull(Silence.reasonOf(""))
    }

    @Test
    fun `длинная причина режется`() {
        val r = Silence.reasonOf("МОЛЧУ: " + "а".repeat(500))!!
        assertEquals(Silence.REASON_CHARS + 1, r.length)
    }
}
