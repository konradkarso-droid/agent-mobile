package com.uroboros.move

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Настройки в пакете: файл настроек → ключ → значение. */
typealias MovePrefsFiles = Map<String, Map<String, Any?>>

/**
 * Настройки приложения в пакете переезда: все файлы настроек, со всеми
 * значениями, кроме привязанных к телефону. Какие привязаны — решает
 * вызывающий (move.MoveAndroid); здесь только укладка и отсев.
 *
 * Почему все, а не список нужных: список кто-то должен не забыть пополнить,
 * а новая настройка, забытая в нём, молча осталась бы на старом телефоне.
 * Лишняя настройка на новом видна и правится; пропавшая — нет.
 *
 * Типы — те, что умеет SharedPreferences: строка, целое, длинное, дробное,
 * да/нет, набор строк.
 *
 * Чистый объект.
 */
object MovePrefs {

    /** Без файлов [skipFiles] и без ключей [deviceKeys] (файл → ключи). */
    fun portable(files: MovePrefsFiles, skipFiles: Set<String>, deviceKeys: Map<String, Set<String>>): MovePrefsFiles =
        files.filterKeys { it !in skipFiles }.mapValues { (file, values) ->
            val drop = deviceKeys[file].orEmpty()
            values.filterKeys { it !in drop }
        }

    fun encode(files: MovePrefsFiles): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).run {
            writeInt(files.size)
            for ((file, values) in files) {
                writeUTF(file)
                val kept = values.filterValues { it != null && typeOf(it) != 0 }
                writeInt(kept.size)
                for ((k, v) in kept) {
                    writeUTF(k)
                    val t = typeOf(v!!)
                    writeByte(t)
                    when (t) {
                        STRING -> writeLongUtf(v as String)
                        INT -> writeInt(v as Int)
                        LONG -> writeLong(v as Long)
                        FLOAT -> writeFloat(v as Float)
                        BOOLEAN -> writeBoolean(v as Boolean)
                        SET -> {
                            @Suppress("UNCHECKED_CAST")
                            val set = v as Set<String>
                            writeInt(set.size)
                            set.forEach { writeLongUtf(it) }
                        }
                    }
                }
            }
        }
        return bytes.toByteArray()
    }

    /** null — испорчено. */
    fun decode(b: ByteArray): MovePrefsFiles? = try {
        DataInputStream(ByteArrayInputStream(b)).run {
            val files = LinkedHashMap<String, Map<String, Any?>>()
            repeat(readInt()) {
                val file = readUTF()
                val values = LinkedHashMap<String, Any?>()
                repeat(readInt()) {
                    val k = readUTF()
                    values[k] = when (readByte().toInt()) {
                        STRING -> readLongUtf()
                        INT -> readInt()
                        LONG -> readLong()
                        FLOAT -> readFloat()
                        BOOLEAN -> readBoolean()
                        SET -> List(readInt()) { readLongUtf() }.toSet()
                        else -> return null
                    }
                }
                files[file] = values
            }
            if (available() != 0) return null
            files
        }
    } catch (e: java.io.IOException) {
        null
    }

    private const val STRING = 1
    private const val INT = 2
    private const val LONG = 3
    private const val FLOAT = 4
    private const val BOOLEAN = 5
    private const val SET = 6

    private fun typeOf(v: Any): Int = when (v) {
        is String -> STRING
        is Int -> INT
        is Long -> LONG
        is Float -> FLOAT
        is Boolean -> BOOLEAN
        is Set<*> -> if (v.all { it is String }) SET else 0
        else -> 0
    }

    /** Строки настроек бывают длиннее 64 КБ, которые держит writeUTF. */
    private fun DataOutputStream.writeLongUtf(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
    }

    private fun DataInputStream.readLongUtf(): String {
        val n = readInt()
        if (n < 0 || n > 64 * 1024 * 1024) throw java.io.IOException("строка не той длины")
        return String(ByteArray(n).also { readFully(it) }, Charsets.UTF_8)
    }
}
