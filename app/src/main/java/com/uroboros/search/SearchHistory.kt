package com.uroboros.search

import org.json.JSONArray
import org.json.JSONObject

internal interface SearchHistory {
    fun read(): List<SearchEntry>
    fun write(entries: List<SearchEntry>): Boolean
}

/** Запись запроса и его выдача сохраняются вместе в прежней истории веб-поиска. */
internal class JsonSearchHistory(
    private val readJson: () -> String,
    private val writeJson: (String) -> Boolean,
) : SearchHistory {
    override fun read(): List<SearchEntry> {
        val rows = JSONArray(readJson())
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            SearchEntry(
                id = row.getString("id"),
                requestId = row.getString("request"),
                query = row.getString("query"),
                channel = SearchChannel.valueOf(row.getString("channel")),
                at = row.getLong("at"),
                status = SearchStatus.valueOf(row.getString("status")),
                count = row.getInt("count"),
                refresh = row.optBoolean("refresh"),
                result = row.optJSONObject("result")?.let(::readResult),
            )
        }
    }

    override fun write(entries: List<SearchEntry>): Boolean {
        val rows = JSONArray()
        for (entry in entries) {
            val row = JSONObject()
                .put("id", entry.id)
                .put("request", entry.requestId)
                .put("query", entry.query)
                .put("channel", entry.channel.name)
                .put("at", entry.at)
                .put("status", entry.status.name)
                .put("count", entry.count)
                .put("refresh", entry.refresh)
            entry.result?.let { row.put("result", writeResult(it)) }
            rows.put(row)
        }
        return writeJson(rows.toString())
    }

    private fun readResult(result: JSONObject): SearchResult? {
        val hits = result.optJSONArray("hits")
        val sources = (0 until minOf(hits?.length() ?: 0, SEARCH_LIMIT)).mapNotNull { index ->
            val hit = hits?.optJSONObject(index) ?: return@mapNotNull null
            val url = hit.opt("url") as? String ?: return@mapNotNull null
            val title = hit.opt("title") as? String ?: return@mapNotNull null
            val description = hit.opt("description") as? String ?: return@mapNotNull null
            SearchHit(title, url, description)
        }
        return SearchResult.from(sources, result.opt("detail") as? String, result.opt("answer") as? String)
    }

    private fun writeResult(result: SearchResult): JSONObject {
        val hits = JSONArray()
        for (hit in result.hits) {
            hits.put(JSONObject().put("title", hit.title).put("url", hit.url).put("description", hit.description))
        }
        return JSONObject().put("hits", hits).put("detail", result.detail).put("answer", result.answer)
    }
}
