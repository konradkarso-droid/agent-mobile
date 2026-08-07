package com.uroboros.memory
import android.content.Context
class TrustedMediator(context: Context) {
    private val dao = MemoryDatabase.getInstance(context).stickerDao()
    private val hourglass = HourglassMemory(dao)
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
}
