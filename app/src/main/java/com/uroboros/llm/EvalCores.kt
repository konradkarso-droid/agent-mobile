package com.uroboros.llm

import com.dark.gguf_lib.GGMLEngine

/**
 * Строка «Ядра: …» к строкам обсчёта запроса (экран — «Обсчёт запроса», чат —
 * «Обсчёт (…)»): где был агент, пока считал запрос, и сколько ядер ему дала
 * система.
 *
 * ЗАЧЕМ. Один и тот же обсчёт на экране и в фоне идёт с разной скоростью, в
 * разы. Без этой строки медленный обсчёт из-за урезанных ядер и медленный по
 * другой причине выглядят одинаково — просто малым числом ток/с.
 *
 * Что значат числа и чего они не умеют — «ГДЕ ШЁЛ ОБСЧЁТ» в `gguf_lib.cpp`;
 * почему движок урезает потоки по данным ядрам — «ПОТОКИ ПО ДАННЫМ ЯДРАМ»
 * там же. Урезал — строка говорит, сколько из скольких.
 * Главное: частоты ядер здесь не видно, поэтому «дано все» при медленном
 * обсчёте не значит, что дело не в фоне.
 *
 * Чистый объект.
 */
object EvalCores {

    /** Одно чтение: группа процесса словом системы; ядер разрешено всего и из быстрых (-1 — не прочиталось). */
    data class Reading(val group: String, val cpus: Int, val fast: Int) {
        val read: Boolean get() = group.isNotEmpty() || cpus >= 0
    }

    /**
     * Строка целиком. [start] и [end] — в начале и в конце обсчёта;
     * [cpusAll] и [fastAll] — ядер в телефоне всего и быстрых (-1 / 0 — не
     * известно); [batchThreads] — потоков обсчёта на деле, [batchThreadsCfg]
     * — по режиму, [genThreads] и [genThreadsCfg] — то же для выдачи (≤ 0 —
     * не известно).
     */
    fun line(
        start: Reading, end: Reading, cpusAll: Int, fastAll: Int, batchThreads: Int,
        batchThreadsCfg: Int = -1, genThreads: Int = -1, genThreadsCfg: Int = -1,
    ): String {
        if (!start.read) return "Ядра: не прочитались"
        val head = "Ядра: " + place(start, cpusAll, fastAll)
        val moved = end.read && end != start
        val tail = if (moved) " → к концу обсчёта: " + place(end, cpusAll, fastAll) else ""
        val cut = cut(batchThreads, batchThreadsCfg, genThreads, genThreadsCfg)?.let { " · $it" } ?: ""
        return head + tail + cut + (overload(listOf(start, end).filter { it.read }, batchThreads)?.let { " · $it" } ?: "")
    }

    /** Движок урезал потоки по данным ядрам — сколько из скольких. Молчит, когда не урезал или не известно. */
    private fun cut(batch: Int, batchCfg: Int, gen: Int, genCfg: Int): String? {
        val parts = buildList {
            if (batch > 0 && batchCfg > batch) add("обсчёт $batch из $batchCfg")
            if (gen > 0 && genCfg > gen) add("выдача $gen из $genCfg")
        }
        return if (parts.isEmpty()) null else "потоков взято по ядрам: " + parts.joinToString(", ")
    }

    /** Строка по разбивке последней генерации движка. */
    fun of(b: GGMLEngine.DecodeBreakdown): String = line(
        Reading(b.placeGroup, b.placeCpus, b.placeFast),
        Reading(b.placeEndGroup, b.placeEndCpus, b.placeEndFast),
        b.cpusAll, b.fastAll, b.batchThreads,
        b.batchThreadsCfg, b.genThreads, b.genThreadsCfg,
    )

    /** Группа словами; слово системы — в скобках, чтобы его можно было искать. */
    fun where(group: String): String = when (group) {
        "" -> "группа не прочиталась"
        "top-app" -> "на экране (top-app)"
        "foreground" -> "не на экране, служба (foreground)"
        "background" -> "в фоне (background)"
        "system-background" -> "в системном фоне (system-background)"
        "restricted" -> "под ограничением системы (restricted)"
        "root" -> "без группы"
        // Свои имена прошивки (у vivo — «h-background»): слово системы
        // остаётся в скобках, словами — только то, что в нём сказано.
        else -> if (group.endsWith("background")) "в фоне ($group)" else "группа «$group»"
    }

    private fun place(r: Reading, cpusAll: Int, fastAll: Int): String {
        val cores = when {
            r.cpus < 0 -> "ядра не прочитались"
            cpusAll > 0 -> "дано ${r.cpus} из $cpusAll"
            else -> "дано ${r.cpus}"
        }
        val fast = if (r.fast >= 0 && fastAll > 0) ", быстрых ${r.fast} из $fastAll" else ""
        return "${where(r.group)}, $cores$fast"
    }

    /**
     * Потоков больше, чем дано ядер, — меньшее из чтений. Быстрые известны —
     * сравнивается с ними: потоков обсчёта движок ставит по числу быстрых
     * ядер, и лишний поток ляжет на медленное или чужое ядро, а ждут все
     * самого медленного. Молчит, когда сравнивать не с чем или ядер хватает.
     */
    private fun overload(readings: List<Reading>, batchThreads: Int): String? {
        if (batchThreads <= 0) return null
        val fast = readings.mapNotNull { r -> r.fast.takeIf { it >= 0 } }.minOrNull()
        val any = readings.mapNotNull { r -> r.cpus.takeIf { it >= 0 } }.minOrNull()
        return when {
            fast != null && batchThreads > fast -> "потоков обсчёта $batchThreads больше, чем дано быстрых ядер ($fast)"
            fast == null && any != null && batchThreads > any -> "потоков обсчёта $batchThreads больше, чем дано ядер ($any)"
            else -> null
        }
    }
}
