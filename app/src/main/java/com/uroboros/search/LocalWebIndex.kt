package com.uroboros.search

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.PriorityQueue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class IndexedPage(val url: String, val title: String, val text: String, val publishedAt: Long? = null)
internal data class IndexedMatch(
    val page: IndexedPage, val missingWords: List<String>, val semanticSpan: WebSpan? = null,
)
internal data class IndexStatus(val pages: Int, val checkedAt: Long?)

/**
 * Заменяемая база открытых страниц, отдельно от памяти и суда. FTS4 индексирует
 * внешнюю таблицу, поэтому исходный очищенный текст хранится в одном экземпляре.
 * Предел в байтах применяется к самой базе SQLite. Временные файлы SQLite
 * при записи могут занимать дополнительное место.
 */
internal class LocalWebIndex(context: Context) : SQLiteOpenHelper(context, "web_pages.db", null, 7) {
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) db.setMaximumSize(MAX_DB_BYTES)
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
        createSemanticTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(newVersion == 7 && oldVersion in 1..6) { "Unsupported web index upgrade: $oldVersion -> $newVersion" }
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE pages ADD COLUMN etag TEXT")
            db.execSQL("ALTER TABLE pages ADD COLUMN last_modified TEXT")
        }
        if (oldVersion < 4) db.execSQL("ALTER TABLE pages ADD COLUMN published_at INTEGER")
        if (oldVersion < 3) createFeedTables(db)
        else {
            if (oldVersion == 3) {
                db.execSQL("ALTER TABLE feed_articles ADD COLUMN published_at INTEGER")
                db.execSQL("UPDATE feeds SET due = 0, etag = NULL, last_modified = NULL")
            }
            if (oldVersion < 5) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN checked_at INTEGER")
                db.execSQL("ALTER TABLE feeds ADD COLUMN failed INTEGER NOT NULL DEFAULT 0")
            }
            if (oldVersion < 6) db.execSQL("ALTER TABLE feed_articles ADD COLUMN in_latest INTEGER NOT NULL DEFAULT 1")
        }
        createSemanticTable(db)
    }

    private fun createSemanticTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE page_vectors (page_id INTEGER PRIMARY KEY, spans BLOB NOT NULL)")
    }

    private fun createFeedTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE feeds (url TEXT PRIMARY KEY NOT NULL, due INTEGER NOT NULL, etag TEXT, " +
            "last_modified TEXT, checked_at INTEGER, failed INTEGER NOT NULL DEFAULT 0)")
        db.execSQL(
            "CREATE TABLE feed_articles (feed TEXT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, " +
                "position INTEGER NOT NULL, published_at INTEGER, in_latest INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (feed, url))",
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
        if (update.clearHistory) {
            db.delete("feed_articles", "feed = ?", arrayOf(url))
            return@transaction
        }
        update.articles?.let { storeFeedArticles(db, url, it) }
        pruneFeedHistory(db, url, at)
    }

    private fun storeFeedArticles(db: SQLiteDatabase, feed: String, articles: List<FeedArticle>) {
        db.execSQL("UPDATE feed_articles SET in_latest = 0 WHERE feed = ? AND in_latest = 1", arrayOf(feed))
        articles.distinctBy { it.url }.take(FEED_ARTICLE_LIMIT).forEachIndexed { position, article ->
            val values = ContentValues().apply {
                put("title", article.title)
                put("position", position)
                put("in_latest", 1)
                article.publishedAt?.let { put("published_at", it) }
            }
            if (db.update("feed_articles", values, "feed = ? AND url = ?", arrayOf(feed, article.url)) == 0) {
                values.put("feed", feed)
                values.put("url", article.url)
                db.insertOrThrow("feed_articles", null, values)
            }
            article.publishedAt?.let { published ->
                db.execSQL("UPDATE pages SET published_at = ? WHERE url = ?", arrayOf(published, article.url))
            }
        }
    }

    private fun pruneFeedHistory(db: SQLiteDatabase, feed: String, now: Long) {
        db.execSQL(
            "DELETE FROM feed_articles WHERE feed = ? AND in_latest = 0 " +
                "AND (published_at IS NULL OR published_at < ? OR published_at > ?)",
            arrayOf(feed, now - FEED_HISTORY_AGE_MS, now),
        )
        db.execSQL(
            "DELETE FROM feed_articles WHERE rowid IN (SELECT rowid FROM feed_articles " +
                "WHERE feed = ? AND in_latest = 0 ORDER BY published_at DESC, url LIMIT -1 OFFSET ?)",
            arrayOf(feed, FEED_HISTORY_LIMIT),
        )
    }

    fun feedCandidates(now: Long): List<FeedArticle> = readableDatabase.rawQuery(
        "SELECT a.url, a.title, a.published_at FROM feed_articles a LEFT JOIN frontier f ON a.url = f.url " +
            "LEFT JOIN pages p ON a.url = p.url WHERE a.in_latest = 1 AND p.id IS NULL AND (f.due IS NULL OR f.due <= ?) " +
            "ORDER BY a.position, a.feed, a.url",
        arrayOf(now.toString()),
    ).use { rows ->
        buildList { while (rows.moveToNext()) add(FeedArticle(rows.getString(0), rows.getString(1), rows.longOrNull(2))) }
    }

    fun headlines(sources: List<WebFeedSource>, window: PublicationWindow, now: Long): List<NewsHeadline> {
        if (sources.isEmpty()) return emptyList()
        val names = sources.associateBy { it.feed.toString() }
        val marks = names.keys.joinToString(", ") { "?" }
        return readableDatabase.rawQuery(
            "SELECT feed, url, title, published_at FROM feed_articles WHERE feed IN ($marks) AND title <> '' " +
                "AND published_at >= ? AND published_at < ? AND (in_latest = 1 OR published_at >= ?)",
            (names.keys.toList() + listOf(window.from.toString(), window.until.toString(),
                (now - FEED_HISTORY_AGE_MS).toString())).toTypedArray(),
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
                        "WHERE f.due = 0 AND p.id IS NULL " +
                        "AND f.url NOT IN (SELECT url FROM feed_articles WHERE in_latest = 1) " +
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

    /** За проход обрабатываются только несколько страниц, включая страницы из старой базы. */
    suspend fun fillSemantic(encoder: WebSemantics, allowed: () -> Boolean) {
        val db = writableDatabase
        val allocated = db.pageSize * db.rawQuery("PRAGMA page_count", null).use {
            check(it.moveToFirst())
            it.getLong(0)
        }
        if (allocated >= MAX_DB_BYTES - WEB_TEXT_RESERVE) return
        val filled = android.database.DatabaseUtils.queryNumEntries(db, "page_vectors")
        val limit = (MAX_VECTOR_PAGES - filled).coerceIn(0L, VECTOR_BATCH)
        if (limit == 0L) return
        val context = currentCoroutineContext()
        val pending = db.rawQuery(
            "SELECT url, title, body, published_at, id FROM pages p " +
                "WHERE NOT EXISTS (SELECT 1 FROM page_vectors v WHERE v.page_id = p.id) ORDER BY p.id DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { rows ->
            buildList {
                while (rows.moveToNext()) add(rows.getLong(4) to rows.page())
            }
        }
        for ((id, page) in pending) {
            context.ensureActive()
            if (!allowed()) break
            val spans = encoder.encode(page)
            context.ensureActive()
            if (!allowed()) break
            // Пустой BLOB отмечает страницу без пригодного спектра.
            db.execSQL(
                "INSERT OR IGNORE INTO page_vectors(page_id, spans) SELECT id, ? FROM pages " +
                    "WHERE id = ? AND url = ? AND title = ? AND body = ?",
                arrayOf(spans, id, page.url, page.title, page.text),
            )
        }
    }

    suspend fun findSemantic(
        encoder: WebSemantics, query: QuantizedWebVector, words: SearchWords, limit: Int,
        window: PublicationWindow?, source: WebFeedSource?, allowed: () -> Boolean,
    ): List<IndexedMatch> {
        data class Ranked(val id: Long, val match: WebSimilarity)
        val context = currentCoroutineContext()
        val db = readableDatabase
        val order = compareByDescending<Ranked> { it.match.score }.thenBy { it.id }
        val best = PriorityQueue(order.reversed())
        val sql = "SELECT v.page_id, v.spans FROM page_vectors v JOIN pages p ON p.id = v.page_id WHERE 1=1" +
            (if (window == null) "" else " AND p.published_at >= ? AND p.published_at < ?") + sourceClause(source)
        val args = buildList {
            if (window != null) {
                add(window.from.toString())
                add(window.until.toString())
            }
            addAll(sourceArguments(source))
        }
        db.rawQuery(sql, args.toTypedArray()).use { rows ->
            while (rows.moveToNext()) {
                context.ensureActive()
                if (!allowed()) return emptyList()
                val match = encoder.best(rows.getBlob(1), query) ?: continue
                best.add(Ranked(rows.getLong(0), match))
                if (best.size > limit) best.remove()
            }
        }
        return best.sortedWith(order).mapNotNull { (id, match) ->
            db.rawQuery("SELECT url, title, body, published_at FROM pages WHERE id = ?", arrayOf(id.toString())).use { row ->
                if (!row.moveToFirst()) return@use null
                val page = row.page()
                if (match.span.text(page.text) == null) return@use null
                IndexedMatch(page, words.missingGroups(page.title + "\n" + page.text), match.span)
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
        db.execSQL("DELETE FROM page_vectors WHERE page_id = ?", arrayOf(id))
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
        const val MAX_DB_BYTES = 100_000_000L
        const val WEB_TEXT_RESERVE = 12_000_000L
        const val MAX_VECTOR_PAGES = 5_000L
        const val VECTOR_BATCH = 16L
        const val FRONTIER_LIMIT = 12_000L
        // Объявленные пределы истории каждой ленты. Издания отдают только видимую часть выпусков.
        const val FEED_HISTORY_AGE_MS = 14 * 24 * 60 * 60_000L
        const val FEED_HISTORY_LIMIT = 1_024
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
