package com.uroboros.memory

import android.content.Context

class TrustedMediator(context: Context) {

    private val dao = MemoryDatabase.getInstance(context).stickerDao()

    suspend fun saveEvent(content: String, tag: String = "general"): Long {
        val sticker = Sticker(content = content, tag = tag)
        return dao.insert(sticker)
    }

    suspend fun getContext(query: String? = null, limit: Int = 10): List<Sticker> {
        val results = if (query.isNullOrBlank()) {
            dao.getRecent(limit)
        } else {
            dao.search(query, limit)
        }

        results.forEach {
            it.lastAccessedAt = System.currentTimeMillis()
            it.accessCount += 1
            dao.update(it)
        }

        return results
    }

    suspend fun totalStickers(): Int = dao.count()
}
