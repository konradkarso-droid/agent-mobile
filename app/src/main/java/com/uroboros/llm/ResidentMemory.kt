package com.uroboros.llm

import java.io.File

/**
 * Из чего состоит память процесса прямо сейчас — по `/proc/self/status`.
 *
 * Зачем. Цифра «Память: пик» (VmHWM) одна на всё и не говорит, чья это
 * память. Её делят на две части, и они ведут себя по-разному:
 *  - **рабочая** (RssAnon) — то, что процесс выделил сам: перепакованные
 *    веса, KV-кэш, буферы. Её системе некуда деть, кроме как убить процесс;
 *  - **файлы** (RssFile) — страницы отображённых файлов: модели, если она
 *    отображена, и библиотек. Их система может выбросить и перечитать.
 *
 * Строка показывает, что загрузка без отображения файла (см.
 * [LlmEngine.USE_MMAP]) действительно применилась: при ней «файлы» малы,
 * порядка сотни мегабайт на библиотеки, а не гигабайты модели.
 *
 * Чего не умеет: это снимок на момент вызова, а не пик — пик каждой части
 * ядро не хранит. Разделяемая память (RssShmem) не показывается: у агента
 * её нет в заметных количествах.
 */
object ResidentMemory {

    data class Split(val anonMb: Long, val fileMb: Long)

    /** Разбор текста `/proc/self/status`; null — если нужных строк нет. */
    fun parse(status: String): Split? {
        val anon = fieldKb(status, "RssAnon") ?: return null
        val file = fieldKb(status, "RssFile") ?: return null
        return Split(anon / 1024, file / 1024)
    }

    fun line(split: Split?): String =
        if (split == null) "Память сейчас: не прочитано"
        else "Память сейчас: рабочая ${split.anonMb} МБ, файлы ${split.fileMb} МБ"

    fun read(): Split? = runCatching { parse(File("/proc/self/status").readText()) }.getOrNull()

    private fun fieldKb(status: String, name: String): Long? =
        Regex("""^$name:\s+(\d+)\s+kB""", RegexOption.MULTILINE)
            .find(status)?.groupValues?.get(1)?.toLongOrNull()
}
