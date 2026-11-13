package com.uroboros.move

import java.io.File

/**
 * Применение принятого пакета на новом телефоне — шаг 4 у [Move]. Идёт при
 * запуске процесса, раньше, чем откроется любая база (UroborosApp), и только
 * если «готово» со старого пришло (отметка [READY] той же сессии, что и
 * [COMPLETE]).
 *
 * Раскладка папки приёма — здесь же, одна на приём и применение.
 *
 * Сбой применения — строка и пакет на месте: следующий запуск попробует снова.
 * Замена баз повторяема — каждый раз кладётся тот же снимок.
 *
 * ЧЕГО НЕ УМЕЕТ: не откатывает уже заменённые базы, если сорвался шаг после
 * них; строки сверки считает до того, как Room доведёт старую схему
 * миграциями, — миграция, меняющая число строк, сверку не испортит, она идёт
 * позже.
 *
 * Чистый объект: файлы через java.io, остальное — руками вызывающего ([Hands]).
 */
object MoveApply {

    const val DB_DIR = "db"
    const val PREFS = "prefs.bin"
    const val TOKEN = "token.bin"
    const val MANIFEST = "manifest.bin"
    const val LAST_MODEL_URI = "last_model_uri.txt"
    const val COMPLETE = "COMPLETE"
    const val READY = "READY"

    /** То, чего чистый код сам не умеет. */
    interface Hands {
        /** Где лежит база этого телефона с таким именем файла. */
        fun dbFile(fileName: String): File
        fun writePrefs(files: MovePrefsFiles, lastModelUri: String?)

        /** Положить закрытый этим телефоном токен на место. */
        fun installToken(sealed: File)

        /** Строки каждой таблицы базы: «файл/таблица» → число. */
        fun count(fileName: String): Map<String, Long>
    }

    sealed class Result {
        object None : Result()
        data class Applied(val line: String) : Result()
        data class Failed(val line: String) : Result()
    }

    /** Сессия, пакет которой лежит целиком; null — нет такого. */
    fun completeSession(staging: File): String? = File(staging, COMPLETE).takeIf { it.isFile }?.readText()?.trim()

    fun apply(staging: File, hands: Hands): Result {
        val ready = File(staging, READY).takeIf { it.isFile }?.readText()?.trim() ?: return Result.None
        return try {
            if (ready != completeSession(staging)) return Result.Failed("пакет не тот, что подтверждён («готово» от другой сессии)")
            val m = MoveManifest.decode(File(staging, MANIFEST).readBytes()) ?: return Result.Failed("опись пакета не читается")
            val names = m.dbs.map { it.fileName }
            for (name in names) place(File(File(staging, DB_DIR), name), hands.dbFile(name))
            val prefs = File(staging, PREFS).takeIf { it.isFile }?.let { MovePrefs.decode(it.readBytes()) ?: return Result.Failed("настройки пакета не читаются") }
            val lastModel = File(staging, LAST_MODEL_URI).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            if (prefs != null) hands.writePrefs(prefs, lastModel)
            File(staging, TOKEN).takeIf { it.isFile }?.let { hands.installToken(it) }
            val actual = names.flatMap { hands.count(it).entries }.associate { it.key to it.value }
            staging.deleteRecursively()
            Result.Applied(MoveCheck.countsLine(m.counts, actual))
        } catch (e: Exception) {
            Result.Failed("${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        }
    }

    /**
     * Положить снимок [staged] (и его `-wal`, если он есть) на место базы
     * [target]. Прежние `-wal`, `-shm`, `-journal` удаляются: от чужой базы они
     * испортили бы новую при открытии.
     */
    fun place(staged: File, target: File) {
        require(staged.isFile) { "в пакете нет базы ${staged.name}" }
        target.parentFile?.mkdirs()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(target.path + suffix).delete()
        copy(staged, target)
        val wal = File(staged.path + "-wal")
        if (wal.isFile) copy(wal, File(target.path + "-wal"))
    }

    private fun copy(from: File, to: File) {
        val tmp = File(to.path + ".move")
        from.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(to)) {
            tmp.delete()
            error("${to.name} не записался")
        }
    }
}
