package com.uroboros.llm

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.uroboros.ModelPrefs

/**
 * Папка моделей: что в ней лежит — модели или накладки.
 *
 * Одно место на два входа: экран строит из неё список моделей на выбор,
 * [LlmEngine] ищет в ней накладку при загрузке. Разойдись они, файл мог бы
 * оказаться сразу и в списке моделей, и подключённой накладкой. Как файл
 * опознаётся — у [AdapterFile].
 *
 * Папку выбирает человек на экране; она запоминается здесь же, в настройках
 * [ModelPrefs.NAME], чтобы её видело и тело агента, загружающее модель само.
 */
object ModelFolder {

    const val KEY_MODEL_FOLDER_URI = "model_folder_uri"

    /** Выбранная папка моделей; null — не выбиралась. */
    fun folderUri(context: Context): Uri? =
        context.getSharedPreferences(ModelPrefs.NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_FOLDER_URI, null)?.let(Uri::parse)

    data class Entry(val file: DocumentFile, val kind: AdapterFile.Kind)

    /**
     * Все `.gguf` папки с опознанием. Бросает, если папка недоступна, — как и
     * прежний перебор на экране: «папки нет» и «папка пуста» различаются.
     */
    fun scan(context: Context, folderUri: Uri): List<Entry> {
        val tree = DocumentFile.fromTreeUri(context, folderUri)
            ?: throw IllegalStateException("папка недоступна")
        return entriesOf(context, tree)
    }

    /**
     * Файлы подпапки ночной накладки ([AdapterFile.NIGHT_FOLDER]); null —
     * подпапки нет. В список моделей подпапка не попадает: [scan] берёт
     * только файлы самой папки. Бросает, если папка моделей недоступна.
     */
    fun scanNight(context: Context, folderUri: Uri): List<Entry>? {
        val tree = DocumentFile.fromTreeUri(context, folderUri)
            ?: throw IllegalStateException("папка недоступна")
        val night = tree.listFiles().firstOrNull {
            it.isDirectory && it.name?.trim()?.equals(AdapterFile.NIGHT_FOLDER, ignoreCase = true) == true
        } ?: return null
        return entriesOf(context, night)
    }

    private fun entriesOf(context: Context, dir: DocumentFile): List<Entry> =
        dir.listFiles()
            .filter { it.isFile && it.name?.endsWith(".gguf", ignoreCase = true) == true }
            .map { Entry(it, kindOf(context, it.uri)) }

    /** Модели на выбор: всё, что не опознано накладкой (см. [AdapterFile]). */
    fun models(entries: List<Entry>): List<DocumentFile> =
        entries.filter { it.kind != AdapterFile.Kind.ADAPTER }.map { it.file }

    /** Файл не прочитался — [AdapterFile.Kind.UNKNOWN]: остаётся в моделях. */
    private fun kindOf(context: Context, uri: Uri): AdapterFile.Kind = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(AdapterFile.HEADER_BYTES)
            var got = 0
            while (got < buf.size) {
                val n = input.read(buf, got, buf.size - got)
                if (n < 0) break
                got += n
            }
            AdapterFile.kindOf(buf.copyOf(got))
        } ?: AdapterFile.Kind.UNKNOWN
    } catch (e: Exception) {
        AdapterFile.Kind.UNKNOWN
    }
}
