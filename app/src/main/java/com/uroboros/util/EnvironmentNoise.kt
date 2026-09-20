package com.uroboros.util

/**
 * Шум окружения в выводе компилятора: что выбросить и что считать поломкой
 * самого инструмента.
 *
 * ЗАЧЕМ. В Termux каждый вызов `kotlinc` печатает в stderr блок про jansi —
 * библиотека собрана под glibc, которой в Termux нет. Компиляции это не мешает,
 * но текст едет обратно в модель вместе с настоящей ошибкой, и один раз модель
 * уже вписала путь из этого блока в сгенерированный код как исходник.
 *
 * ПОЧЕМУ БЛОКОМ, А НЕ ПОСТРОЧНО. Блок выглядит так:
 *
 *     Failed to load native library: jansi-…-libjansi.so. osinfo: Linux/arm64
 *     java.lang.UnsatisfiedLinkError: dlopen failed: library "libc.so.6" not found
 *         at java.lang.ClassLoader$NativeLibrary.load(Native Method)
 *         at …
 *
 * Слово «jansi» стоит только в первой строке. Отбор по словам-приметам снимал
 * её одну, а продолжение — исключение и стек — оставалось. И это не просто
 * мусор в тексте: строки продолжения содержат `UnsatisfiedLinkError` и
 * `libc.so.6`, то есть ровно те приметы, по которым [isInfrastructureFailure]
 * узнаёт поломку окружения. Настоящая ошибка компиляции рядом с таким хвостом
 * читается как «сбой инструмента», и модель не видит ошибки вовсе. Поэтому
 * блок снимается целиком: от строки-приметы до первой строки, начинающей новое
 * сообщение.
 *
 * ЧТО СЧИТАЕТСЯ ПРОДОЛЖЕНИЕМ: строка с отступа (стек, `at …`) или строка,
 * несущая приметы того же блока. Первая строка без отступа и без примет
 * заканчивает блок — она уже чужая.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - список примет закрытый. Новый вид шума будет ехать в модель, пока его сюда
 *    не впишут; молча «угадывать шум» по форме строки этот разбор не пытается,
 *    иначе он съедал бы настоящие ошибки;
 *  - настоящая `UnsatisfiedLinkError`, пришедшая сама по себе (не хвостом
 *    блока), остаётся и по-прежнему считается поломкой окружения — так и надо;
 *  - разбор лексический: ни `kotlinc`, ни Termux он не спрашивает, а только
 *    читает текст.
 *
 * Отдельно от `ErrorSignature` намеренно: та сводит текст к сравнимому ключу и
 * теряет позиции, а этот текст идёт в модель и позиции в нём нужны.
 */
object EnvironmentNoise {

    /** Строки, с которых начинается известный блок шума. */
    private val BLOCK_STARTS = listOf(
        "failed to load native library",
        "jansi",
    )

    /** Приметы, по которым строка опознаётся как продолжение того же блока. */
    private val BLOCK_CONTINUATIONS = listOf(
        "jansi",
        "libjansi.so",
        "unsatisfiedlinkerror",
        "libc.so.6",
        "dlopen failed",
        "in namespace (default)",
    )

    /** Приметы поломки самого инструмента или окружения, а не кода в .kt-файле. */
    private val INFRASTRUCTURE_MARKERS = listOf(
        "UnsatisfiedLinkError",
        "libc.so.6",
        "java.lang.NoClassDefFoundError",
        "Could not find or load main class",
        "OutOfMemoryError",
    )

    /** Вывод без известных блоков шума. */
    fun strip(text: String): String {
        val kept = mutableListOf<String>()
        var insideBlock = false
        for (line in text.lineSequence()) {
            val low = line.lowercase()
            val starts = BLOCK_STARTS.any { low.contains(it) }
            if (starts) {
                insideBlock = true
                continue
            }
            if (insideBlock) {
                val indented = line.isBlank() || line.first().isWhitespace()
                val continues = indented || BLOCK_CONTINUATIONS.any { low.contains(it) }
                if (continues) continue
                insideBlock = false
            }
            kept += line
        }
        return kept.joinToString("\n").trim()
    }

    /**
     * Похоже ли на поломку инструмента или окружения.
     *
     * Спрашивать это нужно у ОЧИЩЕННОГО текста: в исходном хвост блока jansi
     * отвечает «да» на любой компиляции, и настоящая ошибка кода никогда не
     * дойдёт до модели.
     */
    fun isInfrastructureFailure(stderr: String): Boolean =
        INFRASTRUCTURE_MARKERS.any { stderr.contains(it, ignoreCase = true) }
}
