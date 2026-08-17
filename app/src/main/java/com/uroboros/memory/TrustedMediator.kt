package com.uroboros.memory
import android.content.Context
class TrustedMediator(context: Context) {
    private val dao = MemoryDatabase.getInstance(context).stickerDao()
    private val hourglass = HourglassMemory(dao)
    private val snapshotDao = MemoryDatabase.getInstance(context).lastStableSnapshotDao()

    suspend fun saveEvent(content: String, tag: String = "general"): Long {
        val sticker = Sticker(content = content, tag = tag)
        return hourglass.saveEvent(sticker)
    }
    suspend fun getContext(query: String? = null, limit: Int = 10): List<Sticker> {
        return hourglass.getContext(query, limit)
    }
    suspend fun totalStickers(): Int = dao.count()
    suspend fun getPendingReview(): List<Sticker> = dao.getPendingReview()
    suspend fun clearAllPendingReview(): Int = dao.clearAllReviewPending()

    /** Item 6b/8: перезаписывает единственный снимок последнего стабильного состояния. */
    suspend fun saveStableSnapshot(code: String) {
        snapshotDao.save(LastStableSnapshot(code = code))
    }

    /** Item 6b/8: читает снимок последнего стабильного состояния (null, если ещё не было). */
    suspend fun getStableSnapshot(): LastStableSnapshot? = snapshotDao.get()
}
