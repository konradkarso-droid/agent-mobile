package com.uroboros.memory.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class DirectJudgeTest {

    private fun file(
        width: Int = 2,
        mu: String = "0 0",
        sd: String = "1 1",
        spor: String = "1 0",
        soglasie: String = "0 1",
        nesvyazany: String = "0 0",
        b: String = "0 0 0",
    ) = """
        # проба
        версия 3
        слой 21
        ширина $width
        классы спор согласие несвязаны
        mu $mu
        sd $sd
        w_spor $spor
        w_soglasie $soglasie
        w_nesvyazany $nesvyazany
        b $b
    """.trimIndent()

    private fun ok(text: String) = (DirectJudgeDirections.parse(text) as DirectJudgeDirections.Parsed.Ok).directions

    @Test
    fun `файл разбирается и доля считается как нормированная экспонента`() {
        val d = ok(file())
        assertEquals(3, d.version)
        assertEquals(21, d.layer)
        // логиты: спор 2, согласие 0, несвязаны 0
        val p = d.pDispute(floatArrayOf(2f, 0f))!!
        assertEquals(exp(2.0) / (exp(2.0) + 2), p, 1e-9)
    }

    @Test
    fun `масштаб mu и sd применяется до весов`() {
        val d = ok(file(mu = "1 0", sd = "2 1"))
        // (3 − 1) / 2 = 1 → логит спора 1
        assertEquals(exp(1.0) / (exp(1.0) + 2), d.pDispute(floatArrayOf(3f, 0f))!!, 1e-9)
    }

    @Test
    fun `состояние не той ширины — не число, а отказ`() {
        assertNull(ok(file()).pDispute(floatArrayOf(1f, 2f, 3f)))
    }

    @Test
    fun `испорченный файл не даёт направления с нулями`() {
        assertTrue(DirectJudgeDirections.parse(file(mu = "0")) is DirectJudgeDirections.Parsed.Bad)
        assertTrue(DirectJudgeDirections.parse(file(sd = "1 0")) is DirectJudgeDirections.Parsed.Bad)
        assertTrue(DirectJudgeDirections.parse(file(spor = "1 x")) is DirectJudgeDirections.Parsed.Bad)
        assertTrue(DirectJudgeDirections.parse(file(b = "0 0")) is DirectJudgeDirections.Parsed.Bad)
        assertTrue(DirectJudgeDirections.parse(file().replace("слой 21\n", "")) is DirectJudgeDirections.Parsed.Bad)
        assertTrue(DirectJudgeDirections.parse("") is DirectJudgeDirections.Parsed.Bad)
    }

    @Test
    fun `сито отбрасывает только уверенное «не спорят» в обоих порядках`() {
        val t = DirectJudge.SIEVE_THRESHOLD
        assertFalse(DirectJudge.keep(t - 0.01, t - 0.01))
        assertTrue(DirectJudge.keep(t, 0.0))
        assertTrue(DirectJudge.keep(0.0, 0.9))
    }

    @Test
    fun `подача ровно та, на которой учили`() {
        // Форма — часть механизма (см. шапку DirectJudge): тест держит её от
        // случайной «починки», которая молча сделала бы направление чужим.
        assertEquals("<|im_start|>system\n<|im_end|>\n", DirectJudge.WALL)
        assertEquals("<|im_start|>user\nЗапись А: раз\nЗапись Б:", DirectJudge.head("раз"))
        assertEquals(" два", DirectJudge.tail("два"))
    }

    @Test
    fun `эталон узнан, когда спор высок и несвязанные низки в обоих порядках`() {
        assertNull(DirectJudge.etalonRefusal(0.97, 0.95, 0.01, 0.02))
    }

    @Test
    fun `эталон не узнан — сито молчит, причина словами`() {
        assertNotNull(DirectJudge.etalonRefusal(0.97, 0.3, 0.01, 0.02))
        assertNotNull(DirectJudge.etalonRefusal(0.97, 0.95, 0.01, DirectJudge.SIEVE_THRESHOLD))
        // ровные одинаковые числа — то, что даёт чужая модель
        assertNotNull(DirectJudge.etalonRefusal(0.33, 0.33, 0.33, 0.33))
    }

    @Test
    fun `направление из файла приложения разбирается`() {
        val text = javaClass.classLoader!!.getResource("judge/pryamoy_sud.txt")?.readText()
            ?: java.io.File("src/main/assets/judge/pryamoy_sud.txt").readText()
        val d = ok(text)
        assertEquals(3072, d.width)
        assertEquals(21, d.layer)
    }
}
