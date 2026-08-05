package com.uroboros.memory

class HourglassMemory(private val dao: StickerDao) {

    suspend fun getContext(query: String?, limit: Int): List<Sticker> {
        val all = dao.getAll()
        val spectrum = Prism.split(all)
        val layerPicks = Prism.filter(spectrum, query.orEmpty())

        val combined = if (!query.isNullOrBlank()) {
            val textMatches = dao.search(query, limit)
            (layerPicks + textMatches).distinctBy { it.id }
        } else {
            layerPicks
        }

        val result = combined
            .sortedByDescending { it.createdAt }
            .take(limit)

        result.forEach {
            it.lastAccessedAt = System.currentTimeMillis()
            it.accessCount += 1
            dao.update(it)
        }
        return result
    }

    suspend fun saveEvent(sticker: Sticker): Long {
        val (layer, interval) = Prism.classify(sticker)
        sticker.layer = layer.name
        sticker.expiryTime = interval?.let { System.currentTimeMillis() + it }
        return dao.insert(sticker)
    }
}
