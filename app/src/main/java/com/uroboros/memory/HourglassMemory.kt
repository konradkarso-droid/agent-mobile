package com.uroboros.memory

class HourglassMemory(private val dao: StickerDao) {

    suspend fun migrateExpired() {
        val now = System.currentTimeMillis()
        val all = dao.getAll()
        for (sticker in all) {
            val expiry = sticker.expiryTime ?: continue
            if (now >= expiry) {
                val newLayer = Prism.colderLayer(Layer.valueOf(sticker.layer))
                sticker.layer = newLayer.name
                sticker.expiryTime = Prism.newInterval(newLayer)?.let { now + it }
                dao.update(sticker)
            }
        }
    }

    suspend fun getContext(query: String?, limit: Int): List<Sticker> {
        migrateExpired()

        if (query.isNullOrBlank()) {
            val all = dao.getAll()
            return all.sortedByDescending { it.createdAt }.take(limit)
        }

        val all = dao.getAll()
        val spectrum = Prism.split(all)
        val layerPicks = Prism.filter(spectrum, query)
        val textMatches = dao.search(query, limit)
        val combined = (layerPicks + textMatches).distinctBy { it.id }

        val result = combined
            .sortedByDescending { it.createdAt }
            .take(limit)

        val now = System.currentTimeMillis()
        result.forEach {
            it.lastAccessedAt = now
            it.accessCount += 1

            val currentLayer = Layer.valueOf(it.layer)
            val warmer = Prism.warmerLayer(currentLayer)
            if (warmer != currentLayer) {
                it.layer = warmer.name
                it.expiryTime = Prism.newInterval(warmer)?.let { interval -> now + interval }
            }

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
