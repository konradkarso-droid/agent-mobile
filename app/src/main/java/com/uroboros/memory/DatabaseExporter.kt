package com.uroboros.memory

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.uroboros.llm.JournalDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Выгрузка баз приложения файлами в папку Download — дальше человек сам
 * переносит их на другое устройство или загружает в GitHub через
 * веб-редактор, как и остальной код (без прямой связи приложения с GitHub).
 *
 * БАЗ НЕСКОЛЬКО, И ВЫГРУЖАЮТСЯ ОНИ ВМЕСТЕ. Память и лента разговора лежат
 * в разных файлах (причина — в KDoc [JournalDatabase]). Экспорт устроен
 * списком [SOURCES], а не отдельным входом на каждую базу: при появлении
 * третьей правится одна строка, а вызывающий остаётся прежним. Отдельный
 * вход на каждую базу означал бы несколько мест, где надо помнить, что баз
 * стало больше.
 *
 * ОТЧЁТ НАЗЫВАЕТ КАЖДУЮ БАЗУ ОТДЕЛЬНО. Одна выгруженная база и все
 * выгруженные базы обязаны выглядеть на экране по-разному, иначе пропуск
 * неотличим от успеха. Поэтому [exportAllToDownloads] отдаёт список исходов
 * по одному на базу, а [describe] превращает его в текст, читаемый без
 * знания программирования.
 *
 * ОТКАЗ ОДНОЙ БАЗЫ ОСТАВЛЯЕТ ОСТАЛЬНЫЕ. База ленты открывается с настоящей
 * миграцией и без деструктивного отката: при испорченном файле или
 * несовпадении схемы обращение к ней бросает. Такой отказ гасится вокруг
 * одной базы и называется в отчёте, память при этом выгружается как обычно.
 *
 * WAL-ЧЕКПОИНТ ПЕРЕД КОПИРОВАНИЕМ ОБЯЗАТЕЛЕН: Room по умолчанию в режиме
 * WAL, часть свежих записей физически лежит в соседнем `-wal` файле, а не в
 * самом `.db`. Без чекпоинта копия основного файла может оказаться без
 * последних изменений.
 *
 * MediaStore (вместо прямого доступа к файлу) обязателен на minSdk 29+
 * (scoped storage); для файла, который приложение создаёт само в Downloads,
 * отдельное разрешение READ/WRITE_EXTERNAL_STORAGE не требуется.
 *
 * ЧЕГО ЭКСПОРТЁР НЕ УМЕЕТ:
 * - не отличает пустую базу от полной. Обращение к базе создаёт её файл,
 *   если файла ещё не было, и такая база выгрузится успешно, будучи пустой.
 *   Сколько в ней строк — вопрос к тому, кто читает выгрузку;
 * - не переносит `-wal` и `-shm`: чекпоинт сводит их содержимое в основной
 *   файл, и копируется только он;
 * - не проверяет, что скопированный файл открывается как база. Успех здесь
 *   означает «файл записан целиком», а не «база цела».
 */
object DatabaseExporter {

    /** Имя файла базы памяти (Sticker/ActionEvidence/LastStableSnapshot). */
    private const val MEMORY_DB_FILE_NAME = "uroboros_memory.db"

    /**
     * Одна выгружаемая база: как называется на диске, как называется для
     * человека и как до неё дотянуться, чтобы снять чекпоинт.
     *
     * Ярлык идёт на экран, поэтому это слово обиходного языка, а не имя
     * файла: человеку, читающему отчёт, имя файла ничего не говорит.
     */
    private class Source(
        val label: String,
        val fileName: String,
        val checkpoint: (Context) -> Unit
    )

    /**
     * Порядок здесь — порядок строк в отчёте. Память первой: она старше и
     * при чтении отчёта ищется первой.
     */
    private val SOURCES = listOf(
        Source(
            label = "память",
            fileName = MEMORY_DB_FILE_NAME,
            checkpoint = { context ->
                MemoryDatabase.getInstance(context)
                    .openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(FULL)")
                    .use { it.moveToFirst() }
            }
        ),
        Source(
            label = "лента разговора",
            fileName = JournalDatabase.FILE_NAME,
            checkpoint = { context ->
                JournalDatabase.getInstance(context)
                    .openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(FULL)")
                    .use { it.moveToFirst() }
            }
        )
    )

    /**
     * Исход по одной базе. Заполнено ровно одно из двух полей:
     * [exportedName] при успехе, [failure] при отказе. Разделение нужно,
     * чтобы отчёт мог сказать, ЧТО именно осталось невыгруженным, а не
     * только сколько файлов получилось.
     */
    data class Outcome(
        val label: String,
        val exportedName: String?,
        val failure: String?
    ) {
        val ok: Boolean get() = exportedName != null
    }

    /**
     * Выгружает все базы из [SOURCES]. Отказ одной базы оставляет остальные
     * выгруженными; список всегда той же длины, что [SOURCES], и содержит
     * строку на каждую базу — в том числе на отказавшую.
     */
    suspend fun exportAllToDownloads(context: Context): List<Outcome> =
        withContext(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            SOURCES.map { source -> exportOne(context, source, timestamp) }
        }

    /**
     * Старый вход на одну базу памяти. Возвращает имя созданного файла при
     * успехе, `null` при отказе.
     *
     * Оставлен работающим, чтобы вызывающие менялись отдельно от этого
     * файла. Ленту разговора он не выгружает — для полной выгрузки
     * [exportAllToDownloads].
     */
    suspend fun exportToDownloads(context: Context): String? =
        withContext(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val memory = SOURCES.first { it.fileName == MEMORY_DB_FILE_NAME }
            exportOne(context, memory, timestamp).exportedName
        }

    /**
     * Отчёт для экрана: строка на каждую базу с её ярлыком и исходом.
     *
     * Строки различают три случая — выгружено, файла ещё нет, отказ — чтобы
     * молчание одной базы было видно рядом с успехом другой.
     */
    fun describe(outcomes: List<Outcome>): String {
        val lines = outcomes.joinToString("\n") { outcome ->
            if (outcome.ok) {
                "${outcome.label}: ${outcome.exportedName}"
            } else {
                "${outcome.label}: ${outcome.failure}"
            }
        }
        val done = outcomes.count { it.ok }
        val head = "Выгружено баз: $done из ${outcomes.size}"
        val tail = if (done > 0) {
            "\n(файлы лежат в папке Download — оттуда переносите на другое " +
                "устройство или загружайте в GitHub вручную)"
        } else {
            ""
        }
        return "$head\n$lines$tail"
    }

    /**
     * Чекпоинт, проверка файла и копирование одной базы.
     *
     * Общее время метки одно на всю выгрузку: файлы одного прогона
     * сортируются рядом и читаются как один снимок, а не как два разных
     * дня, если прогон пришёлся на смену минуты.
     */
    private fun exportOne(context: Context, source: Source, timestamp: String): Outcome {
        return try {
            source.checkpoint(context)

            val dbFile: File = context.getDatabasePath(source.fileName)
            if (!dbFile.exists()) {
                return Outcome(source.label, null, "файла ещё нет")
            }

            val baseName = source.fileName.removeSuffix(".db")
            val exportName = "${baseName}_backup_$timestamp.db"

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, exportName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return Outcome(source.label, null, "система отказала в записи в Download")

            val written = resolver.openOutputStream(itemUri)?.use { out ->
                dbFile.inputStream().use { input -> input.copyTo(out) }
                true
            } ?: false

            if (!written) {
                return Outcome(source.label, null, "файл в Download не открылся на запись")
            }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)

            Outcome(source.label, exportName, null)
        } catch (e: Exception) {
            // Причина идёт на экран словами: «не удалось» без причины
            // неотличимо от «не пробовали».
            Outcome(source.label, null, "отказ: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
    }
}
