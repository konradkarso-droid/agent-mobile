package com.uroboros.search

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.PriorityQueue

internal data class IndexedPage(val url: String, val title: String, val text: String, val publishedAt: Long? = null)
internal data class IndexedMatch(val page: IndexedPage, val missingWords: List<String>)
internal data class IndexStatus(val pages: Int, val checkedAt: Long?)

/**
 * Заменяемая база открытых страниц, отдельно от памяти и суда. FTS4 индексирует
 * внешнюю таблицу, поэтому исходный очищенный текст хранится в одном экземпляре.
 * Предел в байтах применяется к самой базе SQLite. Временные файлы SQLite
 * при записи могут занимать дополнительное место.
 */
internal class LocalWebIndex(context: Context) : SQLiteOpenHelper(context, "web_pages.db", null, 5) {
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) db.setMaximumSize(100_000_000L)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE pages (id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL, " +
                "body TEXT NOT NULL, etag TEXT, last_modified TEXT, published_at INTEGER)",
        )
        db.execSQL("CREATE VIRTUAL TABLE pages_fts USING fts4(title, body, content='pages', tokenize=unicode61)")
        db.execSQL("CREATE TABLE frontier (url TEXT PRIMARY KEY NOT NULL, due INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX frontier_due ON frontier(due)")
        db.execSQL("CREATE TABLE refresh (checked_at INTEGER NOT NULL)")
        createFeedTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(newVersion == 5 && oldVersion in 1..4) { "Unsupported web index upgrade: $oldVersion -> $newVersion" }
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE pages ADD COLUMN etag TEXT")
            db.execSQL("ALTER TABLE pages ADD COLUMN last_modified TEXT")
        }
        if (oldVersion < 4) db.execSQL("ALTER TABLE pages ADD COLUMN published_at INTEGER")
        if (oldVersion < 3) {
            createFeedTables(db)
            return
        }
        if (oldVersion == 3) {
            db.execSQL("ALTER TABLE feed_articles ADD COLUMN published_at INTEGER")
            db.execSQL("UPDATE feeds SET due = 0, etag = NULL, last_modified = NULL")
        }
        db.execSQL("ALTER TABLE feeds ADD COLUMN checked_at INTEGER")
        db.execSQL("ALTER TABLE feeds ADD COLUMN failed INTEGER NOT NULL DEFAULT 0")
    }

    private fun createFeedTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE feeds (url TEXT PRIMARY KEY NOT NULL, due INTEGER NOT NULL, etag TEXT, " +
            "last_modified TEXT, checked_at INTEGER, failed INTEGER NOT NULL DEFAULT 0)")
        db.execSQL(
            "CREATE TABLE feed_articles (feed TEXT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, " +
                "position INTEGER NOT NULL, published_at INTEGER, PRIMARY KEY (feed, url))",
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
        "SELECT due, etag, last_modified, checked_at, failed FROM feeds WHERE url = ?", arrayOf(url),
    ).use { row ->
        if (row.moveToFirst()) FeedState(row.getLong(0), PageValidators.from(row.getString(1), row.getString(2)),
            row.longOrNull(3), row.getInt(4) != 0)
        else FeedState()
    }

    fun recordFeed(url: String, update: FeedUpdate?, due: Long, at: Long) = transaction { db ->
        if (update == null) {
            val values = ContentValues().apply { put("due", due); put("failed", 1) }
            if (db.update("feeds", values, "url = ?", arrayOf(url)) == 0) {
                values.put("url", url)
                db.insertOrThrow("feeds", null, values)
            }
            return@transaction
        }
        val stored = db.insertWithOnConflict("feeds", null, update.validators.values().apply {
            put("url", url)
            put("due", due)
            put("checked_at", at)
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
                put("published_at", article.publishedAt)
            })
            article.publishedAt?.let { published ->
                db.execSQL("UPDATE pages SET published_at = ? WHERE url = ?", arrayOf(published, article.url))
            }
        }
    }

    fun feedCandidates(now: Long): List<FeedArticle> = readableDatabase.rawQuery(
        "SELECT a.url, a.title, a.published_at FROM feed_articles a LEFT JOIN frontier f ON a.url = f.url " +
            "LEFT JOIN pages p ON a.url = p.url WHERE p.id IS NULL AND (f.due IS NULL OR f.due <= ?) " +
            "ORDER BY a.position, a.feed, a.url",
        arrayOf(now.toString()),
    ).use { rows ->
        buildList { while (rows.moveToNext()) add(FeedArticle(rows.getString(0), rows.getString(1), rows.longOrNull(2))) }
    }

    fun headlines(sources: List<WebFeedSource>): List<NewsHeadline> {
        if (sources.isEmpty()) return emptyList()
        val names = sources.associateBy { it.feed.toString() }
        val marks = names.keys.joinToString(", ") { "?" }
        return readableDatabase.rawQuery(
            "SELECT feed, url, title, published_at FROM feed_articles WHERE feed IN ($marks)",
            names.keys.toTypedArray(),
        ).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val source = names.getValue(rows.getString(0))
                    add(NewsHeadline(source, FeedArticle(rows.getString(1), rows.getString(2), rows.longOrNull(3))))
                }
            }
        }
    }

    fun page(url: String): IndexedPage? = readableDatabase.rawQuery(
        "SELECT url, title, body, published_at FROM pages WHERE url = ?", arrayOf(url),
    ).use { if (it.moveToFirst()) it.page() else null }

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
                val published = publicationDate(db, outcome.url)
                val oldId = removePage(db, outcome.url)
                if (outcome.url != address) removePage(db, address)
                if (page != null) {
                    val id = db.insertOrThrow("pages", null, outcome.validators.values().apply {
                        if (oldId != null) put("id", oldId)
                        put("url", page.url)
                        put("title", page.title)
                        put("body", page.text)
                        put("published_at", published)
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

    fun find(
        query: SearchWords, limit: Int, window: PublicationWindow? = null, source: WebFeedSource? = null,
    ): List<IndexedMatch> {
        if (query.terms.isEmpty()) return if (window == null) emptyList() else recent(window, limit, source)
        val expression = query.terms.joinToString(" OR ") { term -> "\"$term\"" }
        data class RankedPage(val id: Long, val score: MatchScore, val published: Long)
        val bestFirst = compareBy<RankedPage> { it.score.missingWords.size }
            .thenByDescending { it.score.weight }.thenByDescending { it.published }.thenBy { it.id }
        val db = readableDatabase
        val score = "matchinfo(pages_fts, 'pcx')"
        val scoped = window != null || source != null
        val sql = buildString {
            append("SELECT pages_fts.docid, $score, ")
            append(if (window == null) "0" else "p.published_at")
            append(" FROM pages_fts")
            if (scoped) append(" JOIN pages p ON p.id = pages_fts.docid")
            append(" WHERE pages_fts MATCH ?")
            if (window != null) append(" AND p.published_at >= ? AND p.published_at < ?")
            append(sourceClause(source))
        }
        val args = buildList {
            add(expression)
            if (window != null) {
                add(window.from.toString())
                add(window.until.toString())
            }
            addAll(sourceArguments(source))
        }
        val best = db.rawQuery(sql, args.toTypedArray()).use { rows ->
            val ranked = PriorityQueue(bestFirst.reversed())
            while (rows.moveToNext()) {
                ranked.add(RankedPage(rows.getLong(0), query.scoreIndexMatch(rows.getBlob(1)), rows.getLong(2)))
                if (ranked.size > limit) ranked.remove()
            }
            ranked.sortedWith(bestFirst)
        }
        return best.mapNotNull { (id, score) ->
            db.rawQuery("SELECT url, title, body, published_at FROM pages WHERE id = ?", arrayOf(id.toString())).use { row ->
                if (!row.moveToFirst()) return@use null
                IndexedMatch(row.page(), score.missingWords)
            }
        }
    }

    private fun recent(window: PublicationWindow, limit: Int, source: WebFeedSource?): List<IndexedMatch> =
        readableDatabase.rawQuery(
        "SELECT url, title, body, published_at FROM pages p WHERE published_at >= ? AND published_at < ?" +
            sourceClause(source) + " " +
            "ORDER BY published_at DESC, id LIMIT ?",
        (listOf(window.from.toString(), window.until.toString()) + sourceArguments(source) + limit.toString()).toTypedArray(),
    ).use { rows -> buildList { while (rows.moveToNext()) add(IndexedMatch(rows.page(), emptyList())) } }

    private fun publicationDate(db: SQLiteDatabase, url: String): Long? = db.rawQuery(
        "SELECT published_at FROM pages WHERE url = ? AND published_at IS NOT NULL UNION ALL " +
            "SELECT published_at FROM feed_articles WHERE url = ? AND published_at IS NOT NULL LIMIT 1",
        arrayOf(url, url),
    ).use { row -> if (row.moveToFirst()) row.getLong(0) else null }

    private fun Cursor.longOrNull(column: Int) = if (isNull(column)) null else getLong(column)

    private fun Cursor.page() = IndexedPage(getString(0), getString(1), getString(2), longOrNull(3))

    private fun removePage(db: SQLiteDatabase, url: String): Long? {
        val id = db.rawQuery("SELECT id FROM pages WHERE url = ?", arrayOf(url)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: return null
        // FTS4 читает старый текст из pages при удалении, поэтому порядок важен.
        db.execSQL("DELETE FROM pages_fts WHERE docid = ?", arrayOf(id))
        db.execSQL("DELETE FROM pages WHERE id = ?", arrayOf(id))
        return id
    }

    private fun sourceClause(source: WebFeedSource?): String =
        source?.articleHosts?.joinToString(" OR ", " AND (", ")") { "p.url LIKE ?" }.orEmpty()

    private fun sourceArguments(source: WebFeedSource?): List<String> =
        source?.articleHosts?.map { "https://$it/%" }.orEmpty()

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
