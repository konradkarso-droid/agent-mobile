package com.uroboros.move

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.uroboros.ModelPrefs
import com.uroboros.gate.store.GateTokenStore
import com.uroboros.llm.AdapterFile
import com.uroboros.llm.GenerationThreads
import com.uroboros.llm.ModelFolder
import com.uroboros.memory.DatabaseExporter
import com.uroboros.safety.MemoryWatch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Руки переезда на телефоне: снимок и отдача на старом ([source]), приём на
 * новом ([Sink]), применение при запуске ([applyOnStart]). Сам переезд — у
 * [Move].
 *
 * НАСТРОЙКИ, ПРИВЯЗАННЫЕ К ТЕЛЕФОНУ ([SKIP_FILES], [DEVICE_KEYS]) — одно место
 * на этот список. Пути к модели указывают в папку этого телефона; замеры
 * памяти — про его железо; отметка переезда — про этот экземпляр. Остальные
 * настройки едут все ([MovePrefs], почему все).
 *
 * ЧЕГО НЕ УМЕЕТ: место на новом считается по общей памяти телефона — папка
 * моделей на карте памяти не проверяется; модели ищутся только `.gguf` в
 * самой папке и в подпапке ночной накладки ([AdapterFile.NIGHT_FOLDER]).
 */
object MoveAndroid {

    val SKIP_FILES = setOf(MoveState.PREFS, MemoryWatch.PREFS, GenerationThreads.PREFS)
    val DEVICE_KEYS = mapOf(ModelPrefs.NAME to setOf(ModelFolder.KEY_MODEL_FOLDER_URI, ModelPrefs.KEY_LAST_MODEL_URI))

    private const val OUT_DIR = "move_out"
    private const val IN_DIR = "move_in"

    fun staging(c: Context) = File(c.filesDir, IN_DIR)

    // --- Настройки ---

    private fun prefsNames(c: Context): List<String> =
        File(c.applicationInfo.dataDir, "shared_prefs").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            .orEmpty()

    private fun readPrefs(c: Context): MovePrefsFiles =
        MovePrefs.portable(
            prefsNames(c).associateWith { c.getSharedPreferences(it, Context.MODE_PRIVATE).all },
            SKIP_FILES, DEVICE_KEYS,
        )

    // --- Модели ---

    private class Model(val entry: MoveManifest.Entry, val file: DocumentFile)

    /** Модели папки и её ночной подпапки; папки нет или недоступна — пусто. */
    private fun models(c: Context): List<Model> {
        val folder = ModelFolder.folderUri(c) ?: return emptyList()
        return runCatching {
            val top = ModelFolder.scan(c, folder).map { "" to it.file }
            val night = ModelFolder.scanNight(c, folder).orEmpty().map { AdapterFile.NIGHT_FOLDER to it.file }
            (top + night).mapNotNull { (sub, f) ->
                val name = f.name ?: return@mapNotNull null
                Model(MoveManifest.Entry(MoveManifest.Kind.MODEL, sub, name, f.length()), f)
            }
        }.getOrDefault(emptyList())
    }

    // --- Старый телефон ---

    /** Снимок для отдачи. [session] — прежняя, если агент уже переехал и отдаёт пакет снова. */
    fun source(c: Context, session: String?): MoveSource {
        val dir = File(c.cacheDir, OUT_DIR).apply { deleteRecursively(); mkdirs() }
        val snaps = DatabaseExporter.snapshot(c, dir)
        val inMemory = LinkedHashMap<MoveManifest.Entry, ByteArray>()
        val files = LinkedHashMap<MoveManifest.Entry, File>()
        for (s in snaps) for (f in s.files) {
            val kind = if (f.name.endsWith("-wal")) MoveManifest.Kind.WAL else MoveManifest.Kind.DB
            files[MoveManifest.Entry(kind, "", s.fileName, f.length())] = f
        }
        val prefs = MovePrefs.encode(readPrefs(c))
        inMemory[MoveManifest.Entry(MoveManifest.Kind.PREFS, "", "prefs", prefs.size.toLong())] = prefs
        val token = (if (session != null) GateTokenStore.readParked(c) else null) ?: GateTokenStore.read(c)
        token?.toByteArray(Charsets.UTF_8)?.let { inMemory[MoveManifest.Entry(MoveManifest.Kind.TOKEN, "", "token", it.size.toLong())] = it }
        val models = models(c)
        val lastModel = ModelPrefs.lastModelUri(c)?.let { runCatching { DocumentFile.fromSingleUri(c, Uri.parse(it))?.name }.getOrNull() }.orEmpty()
        val manifest = MoveManifest(
            session = session ?: UUID.randomUUID().toString(),
            committed = session != null,
            dbs = snaps.map { MoveManifest.Db(it.fileName, it.label, it.version) },
            counts = snaps.flatMap { it.counts.entries }.associate { it.key to it.value },
            entries = files.keys.toList() + inMemory.keys + models.map { it.entry },
            lastModel = lastModel,
        )
        val byModel = models.associate { it.entry to it.file }
        return object : MoveSource {
            override val manifest = manifest
            override fun open(entry: MoveManifest.Entry): InputStream =
                inMemory[entry]?.let { ByteArrayInputStream(it) }
                    ?: files[entry]?.inputStream()
                    ?: byModel[entry]?.let { c.contentResolver.openInputStream(it.uri) }
                    ?: error("«${entry.name}» не открылся")

            override fun commit(session: String) = MoveState.commit(c, session)
        }
    }

    /** Убрать снимок отданного. */
    fun dropSource(c: Context) {
        File(c.cacheDir, OUT_DIR).deleteRecursively()
    }

    // --- Новый телефон ---

    /** Строк в памяти, ленте и чатах здесь: больше нуля — принимать некуда. */
    fun memoryRows(c: Context): Long {
        val sources = DatabaseExporter.SOURCES.associateBy { it.fileName }
        fun count(file: String, table: String): Long = sources.getValue(file).open(c).openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getLong(0) }
        return count(DatabaseExporter.MEMORY_DB_FILE_NAME, "stickers") +
            count(com.uroboros.llm.JournalDatabase.FILE_NAME, "journal_turns") +
            count(com.uroboros.llm.JournalDatabase.FILE_NAME, "journal_archive_turns") +
            count(com.uroboros.llm.ChatDatabase.FILE_NAME, "chat_lines")
    }

    fun tokenHere(c: Context): Boolean = File(c.filesDir, GateTokenStore.FILE_NAME).exists()

    private fun folder(c: Context): DocumentFile? =
        ModelFolder.folderUri(c)?.let { runCatching { DocumentFile.fromTreeUri(c, it) }.getOrNull() }?.takeIf { it.canWrite() }

    fun here(c: Context): MoveCheck.Here {
        val modelsHere = models(c).associate { it.entry.key to it.entry.size }
        return MoveCheck.Here(
            versions = DatabaseExporter.versions(c),
            memoryRows = memoryRows(c),
            tokenHere = tokenHere(c),
            modelFolderChosen = folder(c) != null,
            freeBytes = runCatching { StatFs((c.getExternalFilesDir(null) ?: c.filesDir).path).availableBytes }.getOrNull(),
            modelsHere = modelsHere,
        )
    }

    class Sink(private val c: Context) : MoveSink {
        private val dir = staging(c)
        private val created = mutableListOf<DocumentFile>()
        private val open = mutableListOf<OutputStream>()
        private var token: ByteArrayOutputStream? = null
        private var lastModelUri: String? = null
        private var lastModel = ""

        override fun refusal(m: MoveManifest): String? = MoveCheck.refusal(m, here(c))
        override fun skip(m: MoveManifest): Set<String> = MoveCheck.skip(m, here(c))
        override fun hasComplete(session: String) = MoveApply.completeSession(dir) == session

        override fun begin(m: MoveManifest) {
            dir.deleteRecursively()
            File(dir, MoveApply.DB_DIR).mkdirs()
            lastModel = m.lastModel
            // Модель, что уже лежит здесь и не поедет, — тоже может быть последней выбранной.
            if (m.lastModel.isNotEmpty()) {
                lastModelUri = folder(c)?.findFile(m.lastModel)?.takeIf { it.isFile }?.uri?.toString()
            }
        }

        override fun create(entry: MoveManifest.Entry): OutputStream {
            val out: OutputStream = when (entry.kind) {
                MoveManifest.Kind.DB -> File(File(dir, MoveApply.DB_DIR), entry.name).outputStream()
                MoveManifest.Kind.WAL -> File(File(dir, MoveApply.DB_DIR), entry.name + "-wal").outputStream()
                MoveManifest.Kind.PREFS -> File(dir, MoveApply.PREFS).outputStream()
                MoveManifest.Kind.TOKEN -> ByteArrayOutputStream().also { token = it }
                MoveManifest.Kind.MODEL -> createModel(entry)
            }
            return object : FilterOutputStream(out) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            }.also { open += it }
        }

        private fun createModel(entry: MoveManifest.Entry): OutputStream {
            val root = folder(c) ?: error("папка моделей недоступна")
            val target = if (entry.folder.isEmpty()) root
            else root.findFile(entry.folder)?.takeIf { it.isDirectory } ?: root.createDirectory(entry.folder) ?: error("подпапка «${entry.folder}» не создалась")
            target.findFile(entry.name)?.delete()
            val doc = target.createFile("application/octet-stream", entry.name) ?: error("«${entry.name}» не создался")
            created += doc
            if (entry.folder.isEmpty() && entry.name == lastModel) lastModelUri = doc.uri.toString()
            return c.contentResolver.openOutputStream(doc.uri) ?: error("«${entry.name}» не открылся на запись")
        }

        override fun complete(m: MoveManifest) {
            token?.toByteArray()?.let { File(dir, MoveApply.TOKEN).writeBytes(GateTokenStore.seal(String(it, Charsets.UTF_8))) }
            File(dir, MoveApply.MANIFEST).writeBytes(m.encode())
            File(dir, MoveApply.LAST_MODEL_URI).writeText(lastModelUri.orEmpty())
            File(dir, MoveApply.COMPLETE).writeText(m.session)
        }

        override fun ready(session: String) {
            File(dir, MoveApply.READY).writeText(session)
        }

        override fun abort() {
            open.forEach { runCatching { it.close() } }
            created.forEach { runCatching { it.delete() } }
            dir.deleteRecursively()
        }
    }

    // --- Применение при запуске ---

    /** Применить принятый пакет, если «готово» пришло; итог — в строку «Комы». */
    fun applyOnStart(c: Context) {
        val result = MoveApply.apply(staging(c), Hands(c))
        when (result) {
            MoveApply.Result.None -> Unit
            is MoveApply.Result.Applied -> MoveState.noteArrived(c, result.line)
            is MoveApply.Result.Failed -> MoveState.noteArrived(c, "не применён: ${result.line}")
        }
    }

    private class Hands(private val c: Context) : MoveApply.Hands {
        override fun dbFile(fileName: String): File = c.getDatabasePath(fileName)

        override fun writePrefs(files: MovePrefsFiles, lastModelUri: String?) {
            for ((name, values) in files) {
                val prefs = c.getSharedPreferences(name, Context.MODE_PRIVATE)
                val keep = DEVICE_KEYS[name].orEmpty().mapNotNull { k -> prefs.all[k]?.let { k to it } }
                val edit = prefs.edit().clear()
                for ((k, v) in values + keep) put(edit, k, v)
                if (name == ModelPrefs.NAME && lastModelUri != null) edit.putString(ModelPrefs.KEY_LAST_MODEL_URI, lastModelUri)
                check(edit.commit()) { "настройки «$name» не записались" }
            }
        }

        private fun put(edit: android.content.SharedPreferences.Editor, k: String, v: Any?) {
            when (v) {
                is String -> edit.putString(k, v)
                is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v)
                is Float -> edit.putFloat(k, v)
                is Boolean -> edit.putBoolean(k, v)
                is Set<*> -> edit.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
        }

        override fun installToken(sealed: File) = GateTokenStore.install(c, sealed)

        override fun count(fileName: String): Map<String, Long> {
            val db = SQLiteDatabase.openDatabase(dbFile(fileName).path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                val tables = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                        "AND name NOT IN ('android_metadata', 'room_master_table')",
                    null,
                ).use { cur -> buildList { while (cur.moveToNext()) add(cur.getString(0)) } }
                return tables.associate { t ->
                    "$fileName/$t" to db.rawQuery("SELECT COUNT(*) FROM `$t`", null).use { cur -> cur.moveToFirst(); cur.getLong(0) }
                }
            } finally {
                db.close()
            }
        }
    }
}
