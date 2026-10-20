package com.uroboros.llm

/**
 * Записи хода, разрезанные на строки для склейки готовых записей, и приборы
 * склейки в «Подробно»: «Записи:» (ход) и «Куски ночи:» (ночная сборка).
 *
 * Что такое склейка, почему кусок — целая строка с подписью и чего склейка
 * не умеет — у `record_splice.h` в нативной части. Здесь — разрез строки,
 * который уходит движку, и слова приборов.
 *
 * ЧЕГО НЕ УМЕЕТ: подпись находится по первому «: » в строке. Строка без
 * подписи, но с «: » внутри разрежется не там. Склейке это безвредно — в
 * кусок строка идёт целиком, — но прибор тогда назовёт подписью начало такой
 * строки.
 */
object RecordSplit {

    /** Строка вспоминания: подпись (с двоеточием) и содержание (с пробелом в начале). */
    data class Line(val sig: String, val content: String)

    /**
     * Строки вспоминания в том виде, в каком их режет движок. Строка без «: »
     * (шапка портрета «О собеседнике я знаю только это:») идёт целиком
     * содержанием с пустой подписью. Пустые строки (разделитель между
     * портретом и остальными записями) пропускаются: это перенос, а не запись.
     */
    fun lines(recall: String): List<Line> = recall.split('\n')
        .filter { it.isNotEmpty() }
        .map { line ->
            val at = line.indexOf(SIG_END)
            if (at < 0) Line("", line) else Line(line.substring(0, at + 1), line.substring(at + 1))
        }

    /** Как прошёл ход со склейкой (см. `TurnStats` в `record_splice.h`). */
    data class Turn(
        /** Строк, которые можно было взять готовыми в этом ходе. */
        val lines: Int = 0,
        /** Из них взято готовыми. */
        val ready: Int = 0,
        /** Токенов, обсчитанных заново (со швами и всем вне записей). */
        val freshTokens: Int = 0,
        /** Куска нет. */
        val missing: Int = 0,
        /** Кусок есть, но не годен: подпись, стена или загрузка другие. */
        val stale: Int = 0,
        /** Сбой подъёма. */
        val failed: Int = 0,
        /** Строку не отрезать ровно по токенам — готовой её не взять (почему — `chunk_text` в `record_splice.h`). */
        val unsplit: Int = 0,
    )

    /** Отчёт движка о записях хода (см. `plan_splice` в `gguf_lib.cpp`). */
    data class Report(
        val lines: Int = 0,
        val sigTokens: Int = 0,
        val contentTokens: Int = 0,
        /** Токенов строк целиком — то, что идёт в куски. */
        val lineTokens: Int = 0,
        /** Швы — начало строк, которое считается заново и при готовом куске. */
        val seamTokens: Int = 0,
        /** Склейка включена. */
        val on: Boolean = false,
        /** Включена, но в этом ходе не пошла — почему. */
        val off: String? = null,
        /** Ход шёл по кускам; null — не шёл. */
        val turn: Turn? = null,
        /** Почему записи не режутся вовсе; null — разрезаны. */
        val why: String? = null,
    )

    /** Строка прибора «Записи:». [report] null — в ходе записей не было. */
    fun meterLine(report: Report?): String {
        if (report == null) return "Записи: в этом ходе записей не было"
        report.why?.let { return "Записи: не режутся — $it" }
        val head = "Записи: ${report.lines} · подписи ${report.sigTokens} ток. · содержание ${report.contentTokens} ток."
        val turn = report.turn
        return when {
            !report.on -> "$head · склейка выключена — записи обсчитаны целиком; " +
                "при готовых кусках заново считалось бы ${report.seamTokens} ток. из ${report.lineTokens}"
            report.off != null -> "$head · склейка включена, но в этом ходе не пошла: ${report.off}"
            turn == null -> "$head · склейка включена, ход не дошёл до обсчёта"
            else -> "$head · готовых ${turn.ready} из ${turn.lines}" +
                misses(turn) + " · заново обсчитано ${turn.freshTokens} ток."
        }
    }

    private fun misses(turn: Turn): String {
        val parts = listOfNotNull(
            turn.missing.takeIf { it > 0 }?.let { "нет куска $it" },
            turn.stale.takeIf { it > 0 }?.let { "кусок не годен $it" },
            turn.failed.takeIf { it > 0 }?.let { "сбой $it" },
            turn.unsplit.takeIf { it > 0 }?.let { "не отрезать ровно $it" },
        )
        return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }

    /** Итог одной порции ночной сборки (см. `nativeBuildRecordChunks`). */
    data class Build(
        val built: Int = 0,
        val kept: Int = 0,
        val failed: Int = 0,
        val stopped: Boolean = false,
        val removed: Int = 0,
        /** Размер папки после подрезки; null — порция не последняя. */
        val totalBytes: Long? = null,
        /** Почему порция не шла вовсе. */
        val why: String? = null,
    )

    /** Сумма порций одной ночи. */
    fun sum(parts: List<Build>): Build = Build(
        built = parts.sumOf { it.built },
        kept = parts.sumOf { it.kept },
        failed = parts.sumOf { it.failed },
        stopped = parts.any { it.stopped },
        removed = parts.sumOf { it.removed },
        totalBytes = parts.lastOrNull { it.totalBytes != null }?.totalBytes,
        why = parts.firstNotNullOfOrNull { it.why },
    )

    /**
     * Строка «Куски ночи:». [asked] — сколько строк ночь собиралась собрать;
     * [refusal] — почему не собирала вовсе (выключено, сторож, нечего).
     */
    fun nightLine(asked: Int, build: Build?, refusal: String?): String {
        refusal?.let { return "Куски ночи: не собирала — $it" }
        if (build == null) return "Куски ночи: не собирала"
        build.why?.let { return "Куски ночи: не вышло — $it" }
        val disk = build.totalBytes?.let { " · на диске ${(it + MB / 2) / MB} МБ" }.orEmpty()
        val removed = if (build.removed > 0) " · вытеснено ${build.removed}" else ""
        val stopped = if (build.stopped) " · остановлено сторожем" else ""
        return "Куски ночи: строк $asked · обсчитано ${build.built} · уже были готовы ${build.kept}" +
            (if (build.failed > 0) " · сбоев ${build.failed}" else "") + disk + removed + stopped
    }

    private const val MB = 1024L * 1024L
    private const val SIG_END = ": "
}
