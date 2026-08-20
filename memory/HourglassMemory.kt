package com.uroboros.memory

class HourglassMemory(private val dao: StickerDao) {

    suspend fun getContext(query: String?, limit: Int): List<Sticker> {
        val all = dao.getAll()
        val spectrum = Prism.split(all)
        val layerPicks = Prism.filter(spectrum, query.orEmpty())

        // текстовый поиск дополняет отбор по слоям, а не заменяет его —
        // так старое поведение (LIKE-поиск) не теряется
        val textMatchIds: Set<Long> = if (!query.isNullOrBlank()) {
            dao.search(query, limit).map { it.id }.toSet()
        } else {
            emptySet()
        }

        val combined = if (!query.isNullOrBlank()) {
            val textMatches = dao.search(query, limit)
            (layerPicks + textMatches).distinctBy { it.id }
        } else {
            layerPicks
        }

        // 2026-08-20: Prism.filter() не фильтрует по смыслу — отдаёт почти весь
        // спектр слоёв безусловно (query там влияет только на BLUE/PURPLE через
        // спецслова "старое"/"архив"). Раньше финальная сортировка шла только по
        // createdAt, из-за чего реальные текстовые совпадения из dao.search()
        // тонули среди нерелевantных, но более новых записей (найдено при
        // подключении памяти к промпту buttonGenerate — модель получала не тот
        // стикер). Теперь при непустом query записи, реально найденные
        // dao.search(), сортируются первыми; при пустом query поведение не
        // меняется (buttonShow без текста работает как раньше).
        val result = combined
            .sortedWith(
                compareByDescending<Sticker> { it.id in textMatchIds }
                    .thenByDescending { it.createdAt }
            )
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
