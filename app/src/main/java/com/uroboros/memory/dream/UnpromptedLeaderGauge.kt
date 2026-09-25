package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.StickerDao

/**
 * Чтение прибора «Нажитое о себе» из базы. Счёт и слова — в
 * [UnpromptedLeader]; здесь только чтение, ничего не пишется и не греется.
 *
 * Читается при показе, а не хранится полем: всё нужное лежит в базе
 * (счётчики касаний и лидеры ночей), поэтому строка переживает и
 * пересоздание экрана, и перезапуск.
 */
class UnpromptedLeaderGauge(
    private val dreams: DreamDao,
    private val stickers: StickerDao,
) {

    constructor(context: Context) : this(
        MemoryDatabase.getInstance(context).dreamDao(),
        MemoryDatabase.getInstance(context).stickerDao(),
    )

    suspend fun line(): String {
        val standing = UnpromptedLeader.standing(stickers.unpromptedTouches())
        // Текст — только первого места, без отметки обращения: прибор смотрит,
        // а не читает память.
        val content = standing.topId?.let { stickers.getById(it)?.content }
        val nights = dreams.lastUnpromptedLeaders(UnpromptedLeader.NIGHTS_WINDOW)
        return UnpromptedLeader.meter(standing, content, nights)
    }
}
