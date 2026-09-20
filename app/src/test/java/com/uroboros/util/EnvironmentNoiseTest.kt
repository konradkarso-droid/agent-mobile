package com.uroboros.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор шума окружения в выводе компилятора.
 *
 * ГЛАВНАЯ ПРОВЕРКА ЗДЕСЬ — ПЕРВАЯ. Блок jansi несёт в хвосте `UnsatisfiedLinkError`
 * и `libc.so.6` — те самые приметы, по которым узнаётся поломка окружения.
 * Пока хвост оставался в тексте, настоящая ошибка компиляции рядом с ним
 * читалась как «сбой инструмента», и модель не получала ошибки вовсе. Это не
 * косметика вывода, а потеря сигнала.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что в живом Termux блок выглядит именно так.
 * Образец ниже записан с устройства 23.08 по памяти о виде вывода; если
 * `kotlinc` или jansi поменяют формулировку, тест останется зелёным, а шум
 * поедет в модель. Приметы закрытым списком — это цена, названная в
 * [EnvironmentNoise].
 */
class EnvironmentNoiseTest {

    private val jansiBlock = """
        Failed to load native library: jansi-2.4.0-a5e1b7-libjansi.so. osinfo: Linux/aarch64
        java.lang.UnsatisfiedLinkError: dlopen failed: library "libc.so.6" not found
        	at java.lang.ClassLoader${'$'}NativeLibrary.load0(Native Method)
        	at java.lang.System.load(System.java:1857)
    """.trimIndent()

    private val realError =
        "error: unresolved reference: sumPositiv\nerror: expecting '}'"

    @Test
    fun `настоящая ошибка рядом с блоком jansi доходит до модели`() {
        val cleaned = EnvironmentNoise.strip("$jansiBlock\n$realError")
        assertEquals(realError, cleaned)
        assertFalse(
            "иначе ошибка кода будет объявлена сбоем окружения и модель её не увидит",
            EnvironmentNoise.isInfrastructureFailure(cleaned),
        )
    }

    @Test
    fun `от блока не остаётся ни строчки`() {
        val cleaned = EnvironmentNoise.strip("$jansiBlock\n$realError")
        assertFalse(cleaned, cleaned.contains("jansi"))
        assertFalse(cleaned, cleaned.contains("libc.so.6"))
        assertFalse("стек — часть блока", cleaned.contains("ClassLoader"))
    }

    @Test
    fun `один шум и ничего больше — пусто`() {
        assertEquals("", EnvironmentNoise.strip(jansiBlock))
    }

    @Test
    fun `вывод без шума не трогается`() {
        assertEquals(realError, EnvironmentNoise.strip(realError))
    }

    @Test
    fun `настоящая поломка окружения сама по себе остаётся и опознаётся`() {
        // Не хвост блока, а самостоятельное сообщение: строка без отступа и без
        // примет jansi. Такое снимать нельзя — это и есть сбой инструмента.
        val standalone = "java.lang.OutOfMemoryError: Java heap space"
        val cleaned = EnvironmentNoise.strip(standalone)
        assertEquals(standalone, cleaned)
        assertTrue(EnvironmentNoise.isInfrastructureFailure(cleaned))
    }

    @Test
    fun `после блока ошибка компилятора начинает новую строку и уцелевает`() {
        // Строка без отступа, не несущая примет блока, заканчивает его — даже
        // если стоит сразу за стеком.
        val cleaned = EnvironmentNoise.strip(
            "Failed to load native library: jansi-…-libjansi.so\n" +
                "\tat java.lang.System.load(System.java:1857)\n" +
                "error: unresolved reference: total"
        )
        assertEquals("error: unresolved reference: total", cleaned)
    }
}
