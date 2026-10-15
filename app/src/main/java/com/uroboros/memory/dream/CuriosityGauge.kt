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
    private val conclusions: ConclusionDao,
) {

    constructor(context: Context) : this(MemoryDatabase.getInstance(context))

    constructor(db: MemoryDatabase) : this(db.dreamDao(), db.stickerDao(), db.conclusionDao())

    suspend fun read(now: Long = System.currentTimeMillis()): CuriosityPressure.Result {
        // Только сны с ненулевыми счетами: остальные дали бы ноль. Записи —
        // по номеру и без отметки обращения.
        // Сны с принятым выводом разряжены (см. CuriosityPressure, «РАЗРЯДКА»),
        // а до рассказа ждут его со своим выводом («НО ДО РАССКАЗА»). На набор
        // записей — последний принятый вывод; ночи несказанного — после первого.
        val accepted = conclusions.accepted().sortedBy { it.id }
        val concluded = accepted.mapTo(HashSet()) { ConclusionKey(it.dreamNightAt, it.dreamRecordIds) }
        val texts = accepted.associate { it.dreamRecordIds to it.text }
        val firstNight = accepted.groupBy { it.dreamRecordIds }.mapValues { (_, rows) -> rows.minOf { it.nightAt } }
        val nights = firstNight.mapValues { (_, at) -> dreams.nightsAfter(at) }
        // Сны окна — для давления; сны с записями выводов любой ночи — для
        // несказанного («НЕСКАЗАННОЕ НЕ ОСТЫВАЕТ»).
        val rows = dreams.stirredSince(now - CuriosityPressure.WINDOW_MS) +
            (if (texts.isEmpty()) emptyList() else dreams.withRecords(texts.keys.toList()))
        val byId = rows.flatMap { it.ids() }.distinct().associateWith { stickers.getById(it) }
        return CuriosityPressure.measure(rows, { byId[it] }, now, concluded, texts) { nights[it] ?: 0 }
    }
}
