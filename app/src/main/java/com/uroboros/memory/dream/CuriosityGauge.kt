package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.StickerDao

/**
 * Чтение давления любопытства из базы. Счёт — в [CuriosityPressure]; здесь
 * только чтение, ничего не пишется и не греется.
 */
class CuriosityGauge(
    private val dreams: DreamDao,
    private val stickers: StickerDao,
) {

    constructor(context: Context) : this(
        MemoryDatabase.getInstance(context).dreamDao(),
        MemoryDatabase.getInstance(context).stickerDao(),
    )

    suspend fun read(now: Long = System.currentTimeMillis()): CuriosityPressure.Result {
        // Только сны с ненулевыми счетами: остальные дали бы ноль. Записи —
        // по номеру и без отметки обращения.
        val rows = dreams.stirredSince(now - CuriosityPressure.WINDOW_MS)
        val byId = rows.flatMap { it.ids() }.distinct().associateWith { stickers.getById(it) }
        return CuriosityPressure.measure(rows, { byId[it] }, now)
    }
}
