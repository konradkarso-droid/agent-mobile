package com.uroboros.memory.nav

import com.uroboros.llm.LayerRead
import com.uroboros.llm.LiveReadOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * Прибор развилки с подставным направлением ширины 1: логит «развилки» равен
 * самому состоянию (mu 0, sd 1, w 1, b 0), так что доля «спор» p выходит из
 * состояния x = ln((1 − p) / p). Модели нет.
 */
class RazvilkaTest {

    private val text = """
        # подставное
        версия 7
        слой 21
        ширина 1
        mu 0
        sd 1
        w 1
        b 0
    """.trimIndent()

    private val direction = (RazvilkaDirection.parse(text) as RazvilkaDirection.Parsed.Ok).direction
    private val ready = RazvilkaHolder.Direction.Ready(direction)
    private val recognized = RazvilkaHolder.Etalon.Recognized(0.97, 0.03)

    private fun state(dispute: Double) = floatArrayOf(ln((1 - dispute) / dispute).toFloat())

    // --- Файл направления ---

    @Test
    fun `файл направления разбирается, доля спора — от логита развилки`() {
        assertEquals(7, direction.version)
        assertEquals(21, direction.layer)
        assertEquals(0.5, direction.pDispute(floatArrayOf(0f))!!, 1e-9)
        assertEquals(0.9, direction.pDispute(state(0.9))!!, 1e-6)
        assertNull(direction.pDispute(floatArrayOf(0f, 0f)))
    }

    @Test
    fun `испорченный файл — причина, а не направление с нулями`() {
        assertEquals(RazvilkaDirection.Parsed.Bad("нет версии"), RazvilkaDirection.parse(text.replace("версия 7", "")))
        assertEquals(RazvilkaDirection.Parsed.Bad("строка w не той длины"), RazvilkaDirection.parse(text.replace("w 1", "w 1 2")))
        assertEquals(RazvilkaDirection.Parsed.Bad("в sd не положительное число"), RazvilkaDirection.parse(text.replace("sd 1", "sd 0")))
        assertEquals(RazvilkaDirection.Parsed.Bad("строки b нет"), RazvilkaDirection.parse(text.replace("b 0", "")))
    }

    // --- Какую часть реплики читать ---

    @Test
    fun `реплика владельца читается целиком, подписанная — после двоеточия с пробелом`() {
        assertEquals("Тебя зовут Гоша.", Razvilka.segment("Тебя зовут Гоша."))
        assertEquals(" Тебя зовут Гоша.", Razvilka.segment("Денис: Тебя зовут Гоша."))
        assertEquals(" ты живёшь в облаке.", Razvilka.segment("Передаю слова Клода: ты живёшь в облаке."))
    }

    @Test
    fun `ярлык — не подпись, пустая реплика — не читается`() {
        assertEquals("Итог: ты живёшь в облаке.", Razvilka.segment("Итог: ты живёшь в облаке."))
        assertNull(Razvilka.segment("   "))
    }

    // --- План хода: молчание с причиной ---

    @Test
    fun `без направления, без эталона и без реплики — не читаю, и сказано почему`() {
        assertEquals(Razvilka.Plan.Silent("направление ещё не прочитано"),
            Razvilka.plan("Ты живёшь в облаке.", RazvilkaHolder.Direction.Loading, recognized))
        assertEquals(Razvilka.Plan.Silent("направления нет: нет слоя"),
            Razvilka.plan("Ты живёшь в облаке.", RazvilkaHolder.Direction.Failed("нет слоя"), recognized))
        assertEquals(Razvilka.Plan.Silent("эталон не снят — модель не загружена"),
            Razvilka.plan("Ты живёшь в облаке.", ready, RazvilkaHolder.Etalon.NotChecked("модель не загружена")))
        assertEquals(Razvilka.Plan.Silent("направление не от этой модели"),
            Razvilka.plan("Ты живёшь в облаке.", ready, RazvilkaHolder.Etalon.Refused("направление не от этой модели")))
        assertEquals(Razvilka.Plan.Silent("нет реплики собеседника"), Razvilka.plan(null, ready, recognized))
    }

    @Test
    fun `всё на месте — отвод взводится слоем направления по реплике`() {
        val p = Razvilka.plan("Денис: ты живёшь в облаке.", ready, recognized) as Razvilka.Plan.Read
        assertEquals(LiveReadOrder(21, " ты живёшь в облаке."), p.order)
    }

    // --- Строка прибора ---

    private fun line(read: LayerRead?): String =
        Razvilka.meterLine(Razvilka.plan("Ты живёшь в облаке.", ready, recognized), read)

    @Test
    fun `показание — доля спора и слово`() {
        assertEquals("Развилка: спор 0,92 — похоже на спор с моим (направление 7, эталон узнан)",
            line(LayerRead.Read(state(0.92))))
        assertEquals("Развилка: спор 0,08 — не спор: развилка или не о моём (направление 7, эталон узнан)",
            line(LayerRead.Read(state(0.08))))
    }

    @Test
    fun `отвод не прочитал — строка молчит с причиной движка`() {
        assertEquals("Развилка: не читаю — отвод не взведён: реплика уже обсчитана — читать нечем",
            line(LayerRead.Failed("реплика уже обсчитана — читать нечем")))
        assertEquals("Развилка: не читаю — итога отвода нет (генерация не дошла до движка)", line(null))
        assertEquals("Развилка: не читаю — состояние не той ширины (2 против 1)", line(LayerRead.Read(floatArrayOf(0f, 0f))))
    }

    @Test
    fun `молчащий план — строка с причиной`() {
        assertEquals("Развилка: не читаю — нет реплики собеседника",
            Razvilka.meterLine(Razvilka.plan(null, ready, recognized), null))
    }

    // --- Эталон ---

    @Test
    fun `эталон узнан, только когда спор — спор, а развилка — не спор`() {
        assertNull(Razvilka.etalonRefusal(0.9, 0.1))
        assertTrue(Razvilka.etalonRefusal(0.4, 0.1)!!.startsWith("направление не от этой модели"))
        assertTrue(Razvilka.etalonRefusal(0.9, 0.6)!!.contains("спор 0,90, развилка 0,60"))
    }

    @Test
    fun `снятие эталона — два чтения тем слоем и в той подаче, что учили`() = runBlocking {
        RazvilkaHolder.load { text.byteInputStream() }
        val asked = mutableListOf<Pair<String, Int>>()
        RazvilkaHolder.checkEtalon { wall, head, tail, layer ->
            assertEquals(Razvilka.ETALON_WALL, wall)
            assertTrue(head.endsWith("Денис:"))
            asked += tail to layer
            LayerRead.Read(state(if (tail == Razvilka.ETALON.dispute) 0.95 else 0.05))
        }
        assertEquals(listOf(Razvilka.ETALON.dispute to 21, Razvilka.ETALON.fork to 21), asked)
        val e = RazvilkaHolder.etalon as RazvilkaHolder.Etalon.Recognized
        assertEquals(0.95, e.disputeOnDispute, 1e-6)
        assertEquals(0.05, e.disputeOnFork, 1e-6)
    }

    @Test
    fun `чужая модель и сбой чтения — эталон не узнан или не снят`() = runBlocking {
        RazvilkaHolder.load { text.byteInputStream() }
        RazvilkaHolder.checkEtalon { _, _, _, _ -> LayerRead.Read(state(0.5)) }
        assertTrue(RazvilkaHolder.etalon is RazvilkaHolder.Etalon.Refused)
        RazvilkaHolder.checkEtalon { _, _, _, _ -> LayerRead.Failed("модель не загружена") }
        assertEquals(RazvilkaHolder.Etalon.NotChecked("чтение не удалось: модель не загружена"), RazvilkaHolder.etalon)
        RazvilkaHolder.checkEtalon { _, _, _, _ -> LayerRead.Read(floatArrayOf(0f, 0f)) }
        assertEquals(RazvilkaHolder.Etalon.Refused("состояние не той ширины (2 против 1)"), RazvilkaHolder.etalon)
    }
}
