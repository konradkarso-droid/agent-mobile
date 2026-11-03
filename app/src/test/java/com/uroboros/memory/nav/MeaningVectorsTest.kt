package com.uroboros.memory.nav

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

/**
 * Чтение файла векторов ([MeaningVectors]): формат на маленьком файле,
 * собранном здесь же, и настоящий файл приложения.
 */
class MeaningVectorsTest {

    /** Файл в формате сборщика (docs/slovar/meaning_build.py): 2 куска по 2 числа, 2 образца на кусок. */
    private fun tiny(words: List<String>, codes: List<IntArray>, restart: Int = 2, extra: ByteArray = ByteArray(0)): ByteArray {
        val bytes = ByteArrayOutputStream()
        val d = DataOutputStream(bytes)
        d.write("UMV1".toByteArray())
        listOf(words.size, 4, 2, 2, 2, restart).forEach { d.writeInt(it) }
        // кусок 0: образец 0 = (1, 0), образец 1 = (0, 1); кусок 1: образец 0 = (2, 2), образец 1 = (-1, 0)
        floatArrayOf(1f, 0f, 0f, 1f, 2f, 2f, -1f, 0f).forEach { d.writeFloat(it) }
        var prev = ByteArray(0)
        words.forEachIndexed { i, w ->
            val b = w.toByteArray(Charsets.UTF_8)
            var shared = 0
            if (i % restart != 0) while (shared < minOf(b.size, prev.size) && b[shared] == prev[shared]) shared++
            d.writeByte(shared); d.writeByte(b.size - shared); d.write(b, shared, b.size - shared)
            prev = b
        }
        codes.forEach { c -> c.forEach { d.writeByte(it) } }
        d.write(extra)
        return bytes.toByteArray()
    }

    private val words = listOf("кот", "кошка", "кошкин", "меч", "мечта")
    private val codes = listOf(intArrayOf(0, 0), intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(0, 0))
    private val small = MeaningVectors.read(ByteArrayInputStream(tiny(words, codes)))

    @Test
    fun `слово находится и внутри блока, и на полной записи`() {
        words.forEachIndexed { i, w -> assertEquals(w, i, small.indexOf(w)) }
    }

    @Test
    fun `вектор собирается из образцов кусков`() {
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 2f), small.vector("кошка"), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, -1f, 0f), small.vector("меч"), 0f)
    }

    @Test
    fun `слова нет — номера и вектора нет, соседи не подставляются`() {
        for (w in listOf("ко", "котик", "кошки", "аа", "яя", "мечтатель", "")) {
            assertEquals(w, -1, small.indexOf(w))
            assertNull(w, small.vector(w))
        }
    }

    @Test
    fun `чужой файл и лишние байты — отказ, а не тихое чтение`() {
        val bad = tiny(words, codes).also { it[0] = 'X'.code.toByte() }
        assertThrows(IllegalStateException::class.java) { MeaningVectors.read(ByteArrayInputStream(bad)) }
        val longer = tiny(words, codes, extra = byteArrayOf(1))
        assertThrows(IllegalStateException::class.java) { MeaningVectors.read(ByteArrayInputStream(longer)) }
    }

    // --- Настоящий файл приложения ---

    private fun cos(a: String, b: String): Double {
        val x = TestVerbs.vectors.vector(a)!!; val y = TestVerbs.vectors.vector(b)!!
        var xy = 0.0; var xx = 0.0; var yy = 0.0
        for (i in x.indices) { xy += x[i] * y[i]; xx += x[i] * x[i]; yy += y[i] * y[i] }
        return xy / sqrt(xx * yy)
    }

    @Test
    fun `в файле начальные формы, а не формы слова`() {
        val v = TestVerbs.vectors
        assertEquals(300, v.dim)
        assertTrue(v.size > 50_000)
        for (w in listOf("клинок", "меч", "фехтование", "стоматолог", "рубанок")) assertNotNull(w, v.vector(w))
        for (w in listOf("клинки", "мечом", "praxis")) assertNull(w, v.vector(w))
    }

    @Test
    fun `близкое по смыслу ближе далёкого`() {
        assertTrue(cos("клинок", "меч") > Meaning.NOUN_CLOSE)
        assertTrue(cos("клинок", "выходной") < Meaning.NOUN_CLOSE)
        assertTrue(cos("клинок", "меч") > cos("клинок", "выходной"))
    }
}
