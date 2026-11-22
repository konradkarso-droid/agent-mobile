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
    "во", "на", "по", "из", "об", "от", "за", "про", "такое",
    "the", "and", "how", "is", "to", "what", "when", "where", "which", "does", "with",
)

/**
 * Заменяемая база открытых страниц, отдельно от памяти и суда. FTS4 индексирует
 * внешнюю таблицу, поэтому исходный очищенный текст хранится в одном экземпляре.
 * Предел в байтах применяется к самой базе SQLite. Временные файлы SQLite
 * при записи могут занимать дополнительное место.
 */
internal class LocalWebIndex(context: Context) : SQLiteOpenHelper(context, "web_pages.db", null, 3) {
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) db.setMaximumSize(100_000_000L)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE pages (id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL, " +
                "body TEXT NOT NULL, etag TEXT, last_modified TEXT)",
        )
        db.execSQL("CREATE VIRTUAL TABLE pages_fts USING fts4(title, body, content='pages', tokenize=unicode61)")
        db.execSQL("CREATE TABLE frontier (url TEXT PRIMARY KEY NOT NULL, due INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX frontier_due ON frontier(due)")
        db.execSQL("CREATE TABLE refresh (checked_at INTEGER NOT NULL)")
        createFeedTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(newVersion == 3 && oldVersion in 1..2) { "Unsupported web index upgrade: $oldVersion -> $newVersion" }
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE pages ADD COLUMN etag TEXT")
            db.execSQL("ALTER TABLE pages ADD COLUMN last_modified TEXT")
        }
        createFeedTables(db)
    }

    private fun createFeedTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE feeds (url TEXT PRIMARY KEY NOT NULL, due INTEGER NOT NULL, etag TEXT, last_modified TEXT)")
        db.execSQL(
            "CREATE TABLE feed_articles (feed TEXT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, " +
                "position INTEGER NOT NULL, PRIMARY KEY (feed, url))",
        )
    }

    fun status(): IndexStatus = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM pages), (SELECT MAX(checked_at) FROM refresh)", null,
    ).use { row ->
        check(row.moveToFirst())
        IndexStatus(row.getInt(0), if (row.isNull(1)) null else row.getLong(1))
    }

    fun nextDue(before: Long, limit: Int, indexed: Boolean): List<String> = readableDatabase.rawQuery(
        "SELECT f.url FROM frontier f LEFT JOIN pages p ON p.url = f.url " +
            "WHERE f.due <= ? AND (p.id IS NOT NULL) = CAST(? AS INTEGER) ORDER BY f.due, f.rowid LIMIT ?",
        arrayOf(before.toString(), if (indexed) "1" else "0", limit.toString()),
    ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }

    fun dueTargets(urls: List<String>, now: Long): List<String> {
        if (urls.isEmpty()) return emptyList()
        val placeholders = urls.joinToString(", ") { "?" }
        val eligible = readableDatabase.rawQuery(
            "SELECT url FROM frontier WHERE due <= ? AND url IN ($placeholders)",
            (listOf(now.toString()) + urls).toTypedArray(),
        ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
        return urls.filter { it in eligible }
    }

    fun seed(urls: List<String>, retryUnindexed: Boolean = false) = transaction { db ->
        addUrls(db, urls)
        if (retryUnindexed) {
            for (url in urls) {
                db.execSQL("UPDATE frontier SET due = -1 WHERE url = ? AND url NOT IN (SELECT url FROM pages)", arrayOf(url))
            }
        }
    }

    fun feedState(url: String): FeedState = readableDatabase.rawQuery(
        "SELECT due, etag, last_modified FROM feeds WHERE url = ?", arrayOf(url),
    ).use { row ->
        if (row.moveToFirst()) FeedState(row.getLong(0), PageValidators.from(row.getString(1), row.getString(2)))
        else FeedState()
    }

    fun recordFeed(url: String, update: FeedUpdate, due: Long) = transaction { db ->
        val stored = db.insertWithOnConflict("feeds", null, update.validators.values().apply {
            put("url", url)
            put("due", due)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        check(stored != -1L)
        val articles = update.articles ?: return@transaction
        db.delete("feed_articles", "feed = ?", arrayOf(url))
        articles.distinctBy { it.url }.take(FEED_ARTICLE_LIMIT).forEachIndexed { position, article ->
            db.insertOrThrow("feed_articles", null, ContentValues().apply {
                put("feed", url)
                put("url", article.url)
                put("title", article.title)
                put("position", position)
            })
        }
    }

    fun feedCandidates(now: Long): List<FeedArticle> = readableDatabase.rawQuery(
        "SELECT a.url, a.title FROM feed_articles a LEFT JOIN frontier f ON a.url = f.url " +
            "LEFT JOIN pages p ON a.url = p.url WHERE p.id IS NULL AND (f.due IS NULL OR f.due <= ?) " +
            "ORDER BY a.position, a.feed, a.url",
        arrayOf(now.toString()),
    ).use { rows ->
        buildList { while (rows.moveToNext()) add(FeedArticle(rows.getString(0), rows.getString(1))) }
    }

    fun queueFeedTargets(urls: List<String>, now: Long): List<String> {
        if (urls.isEmpty()) return emptyList()
        transaction { db ->
            val targets = urls.distinct()
            val marks = targets.joinToString(", ") { "?" }
            val present = db.rawQuery(
                "SELECT COUNT(*) FROM frontier WHERE url IN ($marks)", targets.toTypedArray(),
            ).use { rows -> check(rows.moveToFirst()); rows.getInt(0) }
            val needed = android.database.DatabaseUtils.queryNumEntries(db, "frontier") +
                targets.size - present - FRONTIER_LIMIT
            if (needed > 0) {
                // Освобождаем места только из очереди ещё не посещённых ссылок.
                val seeds = CrawlSources.seeds
                val keep = seeds.joinToString(", ") { "?" }
                db.execSQL(
                    "DELETE FROM frontier WHERE url IN (" +
                        "SELECT f.url FROM frontier f LEFT JOIN pages p ON f.url = p.url " +
                        "WHERE f.due = 0 AND p.id IS NULL AND f.url NOT IN (SELECT url FROM feed_articles) " +
                        "AND f.url NOT IN ($keep) ORDER BY f.rowid DESC LIMIT ?)",
                    (seeds + needed.toString()).toTypedArray(),
                )
            }
            addUrls(db, targets)
        }
        return dueTargets(urls.distinct(), now)
    }

    fun validators(url: String): PageValidators? = readableDatabase.rawQuery(
        "SELECT etag, last_modified FROM pages WHERE url = ?", arrayOf(url),
    ).use { row -> if (row.moveToFirst()) PageValidators.from(row.getString(0), row.getString(1)) else null }

    fun record(address: String, outcome: CrawlOutcome, due: Long, at: Long) = transaction { db ->
        val checked = when (outcome) {
            is CrawlOutcome.Loaded -> {
                val page = outcome.content.page
                val oldId = removePage(db, outcome.url)
                if (outcome.url != address) removePage(db, address)
                if (page != null) {
                    val id = db.insertOrThrow("pages", null, outcome.validators.values().apply {
                        if (oldId != null) put("id", oldId)
                        put("url", page.url)
                        put("title", page.title)
                        put("body", page.text)
                    })
                    db.execSQL("INSERT INTO pages_fts(docid, title, body) VALUES (?, ?, ?)", arrayOf(id, page.title, page.text))
                }
                addUrls(db, outcome.content.links)
                page != null
            }
            is CrawlOutcome.Unchanged -> {
                check(db.update("pages", outcome.validators.values(), "url = ?", arrayOf(outcome.url)) == 1)
                if (outcome.url != address) removePage(db, address)
                true
            }
            is CrawlOutcome.Removed -> {
                removePage(db, outcome.url)
                if (outcome.url != address) removePage(db, address)
                false
            }
        }
        if (address != outcome.url) {
            // После переадресации будущая проверка идёт к канонической странице.
            db.delete("frontier", "url = ?", arrayOf(address))
            addUrls(db, listOf(outcome.url))
        }
        db.execSQL("UPDATE frontier SET due = ? WHERE url = ?", arrayOf(due, outcome.url))
        if (checked) {
            db.execSQL("DELETE FROM refresh")
            db.execSQL("INSERT INTO refresh VALUES (?)", arrayOf(at))
        }
    }

    fun defer(address: String, due: Long) {
        writableDatabase.execSQL("UPDATE frontier SET due = ? WHERE url = ?", arrayOf(due, address))
    }

    private fun PageValidators.values() = ContentValues().apply {
        put("etag", etag)
        put("last_modified", modified)
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
        var remaining = FRONTIER_LIMIT - android.database.DatabaseUtils.queryNumEntries(db, "frontier")
        for (url in urls.distinct()) {
            if (remaining <= 0) break
            val id = db.insertWithOnConflict("frontier", null, ContentValues().apply {
                put("url", url)
                put("due", 0L)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (id != -1L) remaining--
        }
    }

    private companion object {
        const val FRONTIER_LIMIT = 12_000L
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
