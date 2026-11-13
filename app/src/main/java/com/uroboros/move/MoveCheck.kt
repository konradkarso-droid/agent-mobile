package com.uroboros.move

import java.util.Locale

/**
 * Решения нового телефона по описи ([MoveManifest]) и строки сверки после
 * замены баз. Почему отказ здесь терминален и сомнение не пропускается — у
 * [Move]: приём затирает.
 *
 * Чистый объект.
 */
object MoveCheck {

    /** Состояние нового телефона на момент описи. */
    data class Here(
        /** Номер схемы каждой базы в этой сборке: имя файла → номер. */
        val versions: Map<String, Int>,
        /** Записей памяти, ходов ленты и строк чатов здесь; больше нуля — принимать некуда. */
        val memoryRows: Long,
        val tokenHere: Boolean,
        val modelFolderChosen: Boolean,
        /** Свободно места; null — не узнать. */
        val freeBytes: Long?,
        /** Модели, уже лежащие в папке: ключ файла описи → размер. */
        val modelsHere: Map<String, Long>,
    )

    /** Запас места сверх пакета: базы при открытии растут, система тоже пишет. */
    const val SPARE_BYTES = 256L * 1024 * 1024

    /** Модели, которые не нужны: тот же ключ и тот же размер уже здесь. */
    fun skip(m: MoveManifest, here: Here): Set<String> =
        m.entries.filter { it.kind == MoveManifest.Kind.MODEL && here.modelsHere[it.key] == it.size }.map { it.key }.toSet()

    /** Почему не принимать; null — принимать. */
    fun refusal(m: MoveManifest, here: Here): String? {
        if (here.memoryRows > 0) return "здесь уже есть память (${here.memoryRows} строк) — принимать агента можно только в чистую установку"
        if (here.tokenHere) return "здесь уже введён токен бота — сотри его («Токен» → «Стереть»): токен приедет со старого"
        val newer = m.dbs.filter { db -> here.versions[db.fileName]?.let { db.version > it } ?: false }
        if (newer.isNotEmpty()) {
            return "сборка здесь старее: " + newer.joinToString(", ") { "${it.label} v${it.version} > v${here.versions[it.fileName]}" } +
                " — поставь здесь ту же сборку, что на старом"
        }
        val unknown = m.dbs.filter { it.fileName !in here.versions }
        if (unknown.isNotEmpty()) return "этой сборке не знакомы базы: " + unknown.joinToString(", ") { it.label }
        val skip = skip(m, here)
        val models = m.entries.filter { it.kind == MoveManifest.Kind.MODEL && it.key !in skip }
        if (models.isNotEmpty() && !here.modelFolderChosen) return "не выбрана папка моделей — выбери пустую папку на главном экране"
        val need = m.entries.filter { it.key !in skip }.sumOf { it.size } + SPARE_BYTES
        here.freeBytes?.let { free ->
            if (free < need) return "не хватит места: нужно ${gb(need)}, свободно ${gb(free)}"
        }
        return null
    }

    /**
     * Строка сверки после замены баз: все строки описи против пересчитанных
     * здесь. «Сошлось» — только если совпали все таблицы.
     */
    fun countsLine(expected: Map<String, Long>, actual: Map<String, Long>): String {
        val total = expected.values.sum()
        val diff = (expected.keys + actual.keys).sorted().mapNotNull { k ->
            val e = expected[k] ?: 0L
            val a = actual[k] ?: 0L
            if (e == a) null else "$k $e → $a"
        }
        return if (diff.isEmpty()) "строк в базах со старого ${num(total)}, здесь ${num(actual.values.sum())} — сошлось"
        else "строки не сошлись: " + diff.take(5).joinToString("; ") + (if (diff.size > 5) "; и ещё ${diff.size - 5}" else "")
    }

    fun gb(bytes: Long): String = String.format(Locale.ROOT, "%.1f ГБ", bytes / (1024.0 * 1024 * 1024))

    /** Мегабайты для небольших сумм, гигабайты — от гигабайта. */
    fun size(bytes: Long): String =
        if (bytes >= 1024L * 1024 * 1024) gb(bytes) else String.format(Locale.ROOT, "%.1f МБ", bytes / (1024.0 * 1024))

    private fun num(n: Long): String = String.format(Locale.ROOT, "%,d", n).replace(',', ' ')
}
