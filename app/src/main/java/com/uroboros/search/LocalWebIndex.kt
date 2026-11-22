package com.uroboros.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.uroboros.memory.nav.LemmaDictionary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.ln

internal data class IndexedPage(val url: String, val title: String, val text: String)
internal data class IndexStatus(val pages: Int, val checkedAt: Long?)

internal fun searchTerms(query: String, dictionary: LemmaDictionary? = null): List<String> =
    WORD.findAll(query.lowercase(Locale.ROOT)).map { it.value }
        .filter { it.length in 2..48 && it !in FILLER }.distinct().take(12).toList()
        .flatMap { word ->
            val normal = dictionary?.analyze(word)?.firstOrNull()?.lemma?.lowercase(Locale.ROOT)
            if (normal != null && normal.length >= 5 && WORD.matches(normal)) {
                listOf(word, normal, normal.dropLast(1) + "*")
            } else listOf(word)
        }.distinct()

private val WORD = Regex("[\\p{L}\\p{N}]+")
private val FILLER = setOf(
    "как", "где", "что", "это", "когда", "какой", "какие", "для", "или", "при",
    "во", "на", "по", "из", "об", "от", "за", "про", "the", "and", "how", "is", "to",
)

/**
 * Заменяемая база открытых страниц, отдельно от памяти и суда. FTS4 индексирует
 * внешнюю таблицу, поэтому исходный очищенный текст хранится в одном экземпляре.
 * Предел в байтах применяется к самой базе SQLite. Временные файлы SQLite
 * при записи могут занимать дополнительное место.
 */
internal class LocalWebIndex(context: Context) : SQLiteOpenHelper(context, "web_pages.db", null, 1) {
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) db.setMaximumSize(100_000_000L)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pages (id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE VIRTUAL TABLE pages_fts USING fts4(title, body, content='pages', tokenize=unicode61)")
        db.execSQL("CREATE TABLE frontier (url TEXT PRIMARY KEY NOT NULL, due INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX frontier_due ON frontier(due)")
        db.execSQL("CREATE TABLE refresh (checked_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Перестраиваемые данные. Версия 1 ещё не имеет миграций.
        error("Unsupported web index upgrade: $oldVersion -> $newVersion")
    }

    fun status(): IndexStatus = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM pages), (SELECT MAX(checked_at) FROM refresh)", null,
    ).use { row ->
        check(row.moveToFirst())
        IndexStatus(row.getInt(0), if (row.isNull(1)) null else row.getLong(1))
    }

    fun nextDue(now: Long, limit: Int): List<String> = readableDatabase.rawQuery(
        "SELECT url FROM frontier WHERE due <= ? ORDER BY due, rowid LIMIT ?",
        arrayOf(now.toString(), limit.toString()),
    ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }

    fun seed(urls: List<String>, force: Boolean) = transaction { db ->
        addUrls(db, urls)
        if (force) {
            for (url in urls) db.execSQL("UPDATE frontier SET due = -1 WHERE url = ?", arrayOf(url))
        }
    }

    fun record(address: String, page: IndexedPage?, links: List<String>, due: Long, deleted: Boolean = false) =
        transaction { db ->
            if (page != null) {
                val oldId = removePage(db, page.url)
                if (page.url != address) removePage(db, address)
                val id = db.insertOrThrow("pages", null, ContentValues().apply {
                    if (oldId != null) put("id", oldId)
                    put("url", page.url)
                    put("title", page.title)
                    put("body", page.text)
                })
                db.execSQL("INSERT INTO pages_fts(docid, title, body) VALUES (?, ?, ?)", arrayOf(id, page.title, page.text))
            } else if (deleted) {
                removePage(db, address)
            }
            db.execSQL("UPDATE frontier SET due = ? WHERE url = ?", arrayOf(due, address))
            addUrls(db, links)
        }

    fun checked(at: Long) = transaction { db ->
        db.execSQL("DELETE FROM refresh")
        db.execSQL("INSERT INTO refresh VALUES (?)", arrayOf(at))
    }

    fun find(terms: List<String>, limit: Int): List<IndexedPage> {
        if (terms.isEmpty()) return emptyList()
        val expression = terms.joinToString(" OR ") { term ->
            "\"$term\""
        }
        val db = readableDatabase
        val best = db.rawQuery(
            "SELECT docid, matchinfo(pages_fts, 'pcx') FROM pages_fts WHERE pages_fts MATCH ?",
            arrayOf(expression),
        ).use { rows ->
            val ranked = java.util.PriorityQueue(compareBy<Pair<Long, Double>> { it.second }.thenByDescending { it.first })
            while (rows.moveToNext()) {
                ranked.add(rows.getLong(0) to score(rows.getBlob(1)))
                if (ranked.size > limit) ranked.remove()
            }
            ranked.sortedWith(compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first })
        }
        return best.mapNotNull { (id, _) ->
            db.rawQuery("SELECT url, title, body FROM pages WHERE id = ?", arrayOf(id.toString())).use { row ->
                if (row.moveToFirst()) IndexedPage(row.getString(0), row.getString(1), row.getString(2)) else null
            }
        }
    }

    private fun score(blob: ByteArray): Double {
        val data = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder())
        val phrases = data.int
        val columns = data.int
        var score = 0.0
        repeat(phrases) {
            repeat(columns) { column ->
                val hits = data.int
                data.int // Всего совпадений с этой фразой.
                val documents = data.int
                if (hits > 0) score += (if (column == 0) 2 else 1) * (1 + ln(1.0 + hits)) / ln(2.0 + documents)
            }
        }
        return score
    }

    private fun removePage(db: SQLiteDatabase, url: String): Long? {
        val id = db.rawQuery("SELECT id FROM pages WHERE url = ?", arrayOf(url)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: return null
        // FTS4 читает старый текст из pages при удалении, поэтому порядок важен.
        db.execSQL("DELETE FROM pages_fts WHERE docid = ?", arrayOf(id))
        db.execSQL("DELETE FROM pages WHERE id = ?", arrayOf(id))
        return id
    }

    private fun addUrls(db: SQLiteDatabase, urls: List<String>) {
        var remaining = 12_000 - android.database.DatabaseUtils.queryNumEntries(db, "frontier")
        for (url in urls.distinct()) {
            if (remaining <= 0) break
            val id = db.insertWithOnConflict("frontier", null, ContentValues().apply {
                put("url", url)
                put("due", 0L)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (id != -1L) remaining--
        }
    }

    private fun transaction(write: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            write(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
