package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.Prism
import com.uroboros.memory.Sticker
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
        // Путь строки о себе (см. SelfLine.meterTail) — тоже из базы.
        val pending = stickers.pendingIdentity()?.let { it.id to it.basedOnId }
        return UnpromptedLeader.meter(standing, content, nights) +
            SelfLine.meterTail(pending, stickers.countAcceptedIdentity(), dreams.lastSelfLineOutcome())
    }

    /**
     * Строка у записи в очереди, которую предложил агент (см.
     * SelfLine.originLine); null — запись не такая. Числа основания читаются
     * сейчас, при показе.
     */
    suspend fun originLine(sticker: Sticker): String? {
        val baseId = sticker.basedOnId ?: return null
        if (sticker.tag != Prism.IDENTITY_TAG) return null
        val base = stickers.getById(baseId)
        val nights = dreams.lastUnpromptedLeaders(UnpromptedLeader.NIGHTS_WINDOW)
        return SelfLine.originLine(
            baseId = baseId,
            baseText = base?.content,
            touches = base?.userMatchUnpromptedCount ?: 0,
            nightsLed = UnpromptedLeader.nightsLed(baseId, nights),
        )
    }
}
