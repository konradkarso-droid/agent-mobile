package com.uroboros.memory

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Миграция на новое устройство (2026-08-21): экспорт всей Room-базы памяти
 * (`uroboros_memory.db`, содержит Sticker/ActionEvidence/LastStableSnapshot) одним
 * файлом в Download — дальше пользователь сам переносит файл на новое устройство
 * любым удобным способом (в т.ч. вручную загружает в GitHub через веб-редактор,
 * как и остальной код — вариант A, без прямой интеграции приложения с GitHub API).
 *
 * WAL-чекпоинт перед копированием ОБЯЗАТЕЛЕН: Room по умолчанию в режиме WAL,
 * часть свежих записей физически лежит в соседнем `-wal` файле, а не в самом `.db` —
 * без checkpoint копия основного файла может не содержать последние изменения.
 *
 * MediaStore (не прямой File-доступ) — обязателен на minSdk 29+ (scoped storage);
 * для файла, который приложение создаёт само в Downloads, отдельное разрешение
 * READ/WRITE_EXTERNAL_STORAGE не требуется.
 */
object DatabaseExporter {

    private const val DB_FILE_NAME = "uroboros_memory.db"

    /** Возвращает имя созданного файла при успехе, null при ошибке. */
    suspend fun exportToDownloads(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val db = MemoryDatabase.getInstance(context)
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }

            val dbFile = context.getDatabasePath(DB_FILE_NAME)
            if (!dbFile.exists()) return@withContext null

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val exportName = "uroboros_memory_backup_$timestamp.db"

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, exportName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null

            resolver.openOutputStream(itemUri)?.use { out ->
                dbFile.inputStream().use { input -> input.copyTo(out) }
            } ?: return@withContext null

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)

            exportName
        } catch (e: Exception) {
            null
        }
    }
}
