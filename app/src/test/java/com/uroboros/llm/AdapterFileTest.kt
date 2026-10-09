package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Опознание файла накладки и строка о ней.
 *
 * ГЛАВНОЕ ЗДЕСЬ — молчание: файл, заголовок которого не прочитался, накладкой
 * не считается; две накладки — ни одна не берётся; отказ движка не попадает в
 * отпечаток загрузки.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что движок подключает накладку и что ответы с ней
 * другие. Это видно только на устройстве — строкой «Накладка:» и ответами.
 */
class AdapterFileTest {

    // ---- Сборка заголовка GGUF для проверок ----

    private class Gguf {
        private val out = ByteArrayOutputStream()
        private var kv = 0
        private val body = ByteArrayOutputStream()

        private fun le(stream: ByteArrayOutputStream, v: Long, n: Int) {
            for (i in 0 until n) stream.write(((v shr (8 * i)) and 0xFF).toInt())
        }

        private fun str(stream: ByteArrayOutputStream, s: String) {
            val b = s.toByteArray()
            le(stream, b.size.toLong(), 8)
            stream.write(b)
        }

        fun string(key: String, value: String) = apply {
            str(body, key); le(body, 8, 4); str(body, value); kv++
        }

        fun u32(key: String, value: Long) = apply {
            str(body, key); le(body, 4, 4); le(body, value, 4); kv++
        }

        fun strings(key: String, values: List<String>) = apply {
            str(body, key); le(body, 9, 4); le(body, 8, 4); le(body, values.size.toLong(), 8)
            values.forEach { str(body, it) }
            kv++
        }

        fun floats(key: String, count: Int) = apply {
            str(body, key); le(body, 9, 4); le(body, 6, 4); le(body, count.toLong(), 8)
            repeat(count) { le(body, 0, 4) }
            kv++
        }

        fun bytes(): ByteArray {
            out.write("GGUF".toByteArray())
            le(out, 3, 4)       // версия
            le(out, 504, 8)     // тензоров
            le(out, kv.toLong(), 8)
            out.write(body.toByteArray())
            return out.toByteArray()
        }
    }

    // ---- Опознание ----

    @Test
    fun `накладка — по general_type adapter`() {
        val h = Gguf().string("general.architecture", "qwen2").string("general.type", "adapter")
            .string("adapter.type", "lora").bytes()
        assertEquals(AdapterFile.Kind.ADAPTER, AdapterFile.kindOf(h))
    }

    @Test
    fun `модель — по general_type model`() {
        val h = Gguf().string("general.architecture", "qwen2").string("general.type", "model")
            .u32("qwen2.block_count", 36).bytes()
        assertEquals(AdapterFile.Kind.MODEL, AdapterFile.kindOf(h))
    }

    @Test
    fun `накладка без general_type — по adapter_type`() {
        val h = Gguf().string("general.architecture", "qwen2").string("adapter.type", "lora").bytes()
        assertEquals(AdapterFile.Kind.ADAPTER, AdapterFile.kindOf(h))
    }

    @Test
    fun `ключ после массивов находится`() {
        val h = Gguf().strings("general.tags", listOf("a", "bb")).floats("x.scales", 3)
            .u32("x.count", 1).string("general.type", "adapter").bytes()
        assertEquals(AdapterFile.Kind.ADAPTER, AdapterFile.kindOf(h))
    }

    @Test
    fun `имя файла не участвует — модель с именем накладки остаётся моделью`() {
        val h = Gguf().string("general.name", "nakladka_lora_adapter").string("general.type", "model").bytes()
        assertEquals(AdapterFile.Kind.MODEL, AdapterFile.kindOf(h))
    }

    @Test
    fun `не GGUF — не накладка`() {
        assertEquals(AdapterFile.Kind.UNKNOWN, AdapterFile.kindOf("PK\u0003\u0004 zip".toByteArray()))
        assertEquals(AdapterFile.Kind.UNKNOWN, AdapterFile.kindOf(ByteArray(0)))
    }

    @Test
    fun `обрыв до ключа — не накладка`() {
        val full = Gguf().string("general.architecture", "qwen2").string("general.type", "adapter").bytes()
        // Обрезано внутри значения general.type.
        val cut = full.copyOf(full.size - 3)
        assertEquals(AdapterFile.Kind.UNKNOWN, AdapterFile.kindOf(cut))
    }

    @Test
    fun `ключа нет вовсе — не накладка`() {
        val h = Gguf().string("general.architecture", "qwen2").u32("qwen2.block_count", 36).bytes()
        assertEquals(AdapterFile.Kind.UNKNOWN, AdapterFile.kindOf(h))
    }

    @Test
    fun `безумная длина строки не роняет разбор`() {
        val h = Gguf().string("general.architecture", "qwen2").bytes()
        // Подменить длину ключа первой пары на огромную.
        for (i in 24 until 32) h[i] = 0x7F
        assertEquals(AdapterFile.Kind.UNKNOWN, AdapterFile.kindOf(h))
    }

    // ---- Выбор ----

    @Test
    fun `накладок нет — выбора нет`() {
        val k = listOf(AdapterFile.Kind.MODEL, AdapterFile.Kind.UNKNOWN)
        assertEquals(AdapterFile.Pick.None, AdapterFile.pick(k))
    }

    @Test
    fun `одна накладка — она`() {
        val k = listOf(AdapterFile.Kind.MODEL, AdapterFile.Kind.ADAPTER, AdapterFile.Kind.UNKNOWN)
        assertEquals(AdapterFile.Pick.One(1), AdapterFile.pick(k))
    }

    @Test
    fun `две накладки — никакая`() {
        val k = listOf(AdapterFile.Kind.ADAPTER, AdapterFile.Kind.MODEL, AdapterFile.Kind.ADAPTER)
        assertEquals(AdapterFile.Pick.Several(2), AdapterFile.pick(k))
    }

    // ---- Итог, строка, отпечаток ----

    @Test
    fun `подключена — только когда движок сказал да`() {
        val one = AdapterFile.Pick.One(0)
        assertEquals(AdapterFile.Outcome.On("n.gguf", 10), AdapterFile.outcome(one, "n.gguf", 10, true, 1))
        assertEquals(AdapterFile.Outcome.Rejected("n.gguf"), AdapterFile.outcome(one, "n.gguf", 10, true, 2))
        // Заказ был, а движок молчит о накладке — тоже не подключена.
        assertEquals(AdapterFile.Outcome.Rejected("n.gguf"), AdapterFile.outcome(one, "n.gguf", 10, true, 0))
        assertEquals(AdapterFile.Outcome.NotOpened("n.gguf"), AdapterFile.outcome(one, "n.gguf", 10, false, 0))
    }

    @Test
    fun `без выбора ответ движка не читается`() {
        assertEquals(AdapterFile.Outcome.NotFound, AdapterFile.outcome(AdapterFile.Pick.None, null, 0, false, 1))
        assertEquals(AdapterFile.Outcome.Several(3), AdapterFile.outcome(AdapterFile.Pick.Several(3), null, 0, false, 1))
    }

    @Test
    fun `в отпечаток попадает только подключённая`() {
        assertEquals("|lora=n.gguf:10", AdapterFile.printPart(AdapterFile.Outcome.On("n.gguf", 10)))
        val silent = listOf(
            AdapterFile.Outcome.NoFolder,
            AdapterFile.Outcome.NotFound,
            AdapterFile.Outcome.Several(2),
            AdapterFile.Outcome.NotOpened("n.gguf"),
            AdapterFile.Outcome.Rejected("n.gguf"),
        )
        for (o in silent) assertEquals("", AdapterFile.printPart(o))
    }

    @Test
    fun `каждое нет называет причину, да называет файл`() {
        assertEquals("Накладка: n.gguf", AdapterFile.line(AdapterFile.Outcome.On("n.gguf", 10)))
        val no = listOf(
            AdapterFile.Outcome.NoFolder,
            AdapterFile.Outcome.NotFound,
            AdapterFile.Outcome.Several(2),
            AdapterFile.Outcome.NotOpened("n.gguf"),
            AdapterFile.Outcome.Rejected("n.gguf"),
        )
        val lines = no.map { AdapterFile.line(it) }
        for (l in lines) assertTrue(l, l.startsWith("Накладка: нет — "))
        assertEquals(lines.size, lines.toSet().size)
    }

    // ---- Ночная накладка: подпапка «ночь» ----

    private val A = AdapterFile.Kind.ADAPTER
    private val M = AdapterFile.Kind.MODEL

    @Test
    fun `подпапки нет — ночь на дневной`() {
        val plan = AdapterFile.nightPlan(null)
        assertTrue(plan is AdapterFile.NightPlan.Day)
        assertTrue((plan as AdapterFile.NightPlan.Day).why.contains("подпапки"))
    }

    @Test
    fun `в подпапке пусто или одни модели — ночь на дневной`() {
        assertTrue(AdapterFile.nightPlan(emptyList()) is AdapterFile.NightPlan.Day)
        assertTrue(AdapterFile.nightPlan(listOf(M, AdapterFile.Kind.UNKNOWN)) is AdapterFile.NightPlan.Day)
    }

    @Test
    fun `две ночных — никакая, ночь на дневной`() {
        val plan = AdapterFile.nightPlan(listOf(A, M, A))
        assertTrue(plan is AdapterFile.NightPlan.Day)
        assertTrue((plan as AdapterFile.NightPlan.Day).why.contains("2"))
    }

    @Test
    fun `одна ночная — она, по месту среди файлов подпапки`() {
        assertEquals(AdapterFile.NightPlan.Own(1), AdapterFile.nightPlan(listOf(M, A)))
    }

    @Test
    fun `строка ночи называет ночную накладку`() {
        assertEquals(
            "Ночь: накладка n5.gguf (ночная)",
            AdapterFile.nightLine(AdapterFile.NightPlan.Own(0), AdapterFile.Outcome.On("n5.gguf", 1)),
        )
    }

    @Test
    fun `ночная не подключилась — сказано прямо, а не выдано за ночную`() {
        val l = AdapterFile.nightLine(AdapterFile.NightPlan.Own(0), AdapterFile.Outcome.Rejected("n5.gguf"))
        assertTrue(l, l.startsWith("Ночь: ночная не подключилась — "))
        assertTrue(l, !l.contains("(ночная)"))
    }

    @Test
    fun `ночь на дневной — названы и причина, и дневная`() {
        val l = AdapterFile.nightLine(AdapterFile.NightPlan.Day("подпапки «ночь» нет"), AdapterFile.Outcome.On("n7.gguf", 1))
        assertTrue(l, l.startsWith("Ночь: ночной накладки нет — подпапки «ночь» нет"))
        assertTrue(l, l.contains("Накладка: n7.gguf"))
        assertTrue(l, !l.contains("(ночная)"))
    }
}
