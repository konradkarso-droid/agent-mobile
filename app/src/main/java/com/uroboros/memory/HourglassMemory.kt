package com.uroboros.memory

import android.util.Log

class HourglassMemory(private val dao: StickerDao) {

    private val HOT_LAYERS = listOf(Layer.RED.name, Layer.ORANGE.name, Layer.YELLOW.name, Layer.GREEN.name)

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

    private fun rankOrder(): Comparator<Sticker> =
        compareByDescending<Sticker> { Importance.valueOf(it.importance).ordinal }
            .thenByDescending { it.createdAt }

    suspend fun getContext(query: String?, limit: Int): List<Sticker> {
        migrateExpired()

        if (query.isNullOrBlank()) {
            val all = dao.getAll()
            return all.sortedWith(rankOrder()).take(limit)
        }

        val all = dao.getAll()
        val spectrum = Prism.split(all)
        val layerPicks = Prism.filter(spectrum, query)
        val textMatches = dao.search(query, limit)
        val combined = (layerPicks + textMatches).distinctBy { it.id }

        val result = combined
            .sortedWith(rankOrder())
            .take(limit)

        val now = System.currentTimeMillis()
        result.forEach {
            val timeSinceLastAccess = now - it.lastAccessedAt
            val isFirstAccess = it.accessCount == 0
            it.accessCount += 1

            val currentLayer = Layer.valueOf(it.layer)
            val debounce = Prism.warmDebounce(currentLayer)

            if (isFirstAccess || timeSinceLastAccess >= debounce) {
                val warmer = Prism.warmerLayer(currentLayer)
                if (warmer != currentLayer) {
                    it.layer = warmer.name
                    it.expiryTime = Prism.newInterval(warmer)?.let { interval -> now + interval }
                }
            }

            it.lastAccessedAt = now
            dao.update(it)
        }
        return result
    }

    suspend fun saveEvent(sticker: Sticker): Long {
        val (layer, interval) = Prism.classify(sticker)
        sticker.layer = layer.name
        sticker.expiryTime = interval?.let { System.currentTimeMillis() + it }
        val newId = dao.insert(sticker)

        // --- RiskTrigger: log-only stage, does not block or alter saving ---
        val hotPool = dao.getByTagInLayers(sticker.tag, HOT_LAYERS).filter { it.id != newId }
        val saved = sticker.copy(id = newId)
        val decision = RiskTrigger.evaluate(saved, hotPool)
        Log.d(
            "RiskTrigger",
            "sticker id=$newId tag=${sticker.tag} shouldReview=${decision.shouldReview} reasons=${decision.reasons}"
        )

        return newId
    }
}
