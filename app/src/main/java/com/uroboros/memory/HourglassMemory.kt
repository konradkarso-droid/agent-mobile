package com.uroboros.memory

class HourglassMemory(private val dao: StickerDao) {

    suspend fun getContext(query: String?, limit: Int): List<Sticker> {
        val all = dao.getAll()
        val spectrum = Prism.split(all)
        val layerPicks = Prism.filter(spectrum, query.orEmpty())

        if (query.isNullOrBlank()) {
            // Пустой запрос — просто "посмотреть, что в памяти",
            // не считается обращением к конкретным стикерам
            return layerPicks
                .sortedByDescending { it.createdAt }
                .take(limit)
        }

        // Целенаправленный поиск — реальный сигнал интереса,
        // вот тут обращение действительно засчитывается
        val textMatches = dao.search(query, limit)
        val combined = (layerPicks + textMatches).distinctBy { it.id }

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
