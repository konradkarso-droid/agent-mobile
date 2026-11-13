package com.uroboros.move

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Опись пакета: что старый телефон собирается передать. Новый по ней решает,
 * принимать ли ([MoveCheck]), и по ней же сверяет принятое — каждый файл
 * описи пришёл целиком, с тем же размером и суммой.
 *
 * [session] — номер переезда. Старый, замерший насовсем, повторяет его при
 * повторной передаче: так новый узнаёт пакет, который у него уже лежит
 * ([committed], см. [Move], «обрыв между 3 и 4»).
 *
 * [counts] — строки каждой таблицы каждой базы в снимке («файл/таблица» →
 * число). Новый пересчитывает их после замены баз: строка «сошлось» на экране
 * значит, что базы дошли до строки, а не только до байта.
 *
 * Чистый объект.
 */
data class MoveManifest(
    val session: String,
    val committed: Boolean,
    val dbs: List<Db>,
    val counts: Map<String, Long>,
    val entries: List<Entry>,
    /** Имя файла модели, выбранной на старом последней; пусто — не выбиралась. */
    val lastModel: String,
) {
    /** База: имя файла и номер схемы в снимке. */
    data class Db(val fileName: String, val label: String, val version: Int)

    enum class Kind(val code: Int) { DB(1), WAL(2), PREFS(3), TOKEN(4), MODEL(5) }

    /** Один файл пакета. У модели [folder] — подпапка в папке моделей ("" — сама папка). */
    data class Entry(val kind: Kind, val folder: String, val name: String, val size: Long) {
        val key: String get() = "${kind.code}/$folder/$name"
    }

    val totalBytes: Long get() = entries.sumOf { it.size }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).run {
            writeUTF(session)
            writeBoolean(committed)
            writeUTF(lastModel)
            writeInt(dbs.size)
            dbs.forEach { writeUTF(it.fileName); writeUTF(it.label); writeInt(it.version) }
            writeInt(counts.size)
            counts.forEach { (k, v) -> writeUTF(k); writeLong(v) }
            writeInt(entries.size)
            entries.forEach { writeByte(it.kind.code); writeUTF(it.folder); writeUTF(it.name); writeLong(it.size) }
        }
        return bytes.toByteArray()
    }

    companion object {
        /** null — не опись: испорчена или другой версии. */
        fun decode(b: ByteArray): MoveManifest? = try {
            DataInputStream(ByteArrayInputStream(b)).run {
                val session = readUTF()
                val committed = readBoolean()
                val lastModel = readUTF()
                val dbs = List(count()) { Db(readUTF(), readUTF(), readInt()) }
                val counts = (0 until count()).associate { readUTF() to readLong() }
                val entries = List(count()) {
                    val code = readByte().toInt()
                    val kind = Kind.values().firstOrNull { it.code == code } ?: return null
                    Entry(kind, readUTF(), readUTF(), readLong()).takeIf { it.size >= 0 } ?: return null
                }
                if (available() != 0) return null
                MoveManifest(session, committed, dbs, counts, entries, lastModel)
            }
        } catch (e: java.io.IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        private fun DataInputStream.count(): Int = readInt().also { require(it in 0..100_000) }
    }
}
