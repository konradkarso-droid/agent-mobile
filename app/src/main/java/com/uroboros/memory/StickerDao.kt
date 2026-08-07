package com.uroboros.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface StickerDao {
    @Insert
    suspend fun insert(sticker: Sticker): Long

    @Query("SELECT * FROM stickers WHERE id = :id")
    suspend fun getById(id: Long): Sticker?

    @Query("SELECT * FROM stickers ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<Sticker>

    @Query("SELECT * FROM stickers WHERE content LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<Sticker>

    @Query("SELECT * FROM stickers ORDER BY createdAt DESC")
    suspend fun getAll(): List<Sticker>

    @Query("SELECT * FROM stickers WHERE tag = :tag AND layer IN (:layers)")
    suspend fun getByTagInLayers(tag: String, layers: List<String>): List<Sticker>

    @Query("SELECT * FROM stickers WHERE reviewPending = 1 ORDER BY createdAt DESC")
    suspend fun getPendingReview(): List<Sticker>

    @Query("UPDATE stickers SET reviewPending = 0 WHERE id = :id")
    suspend fun clearReviewPending(id: Long)

    @Query("UPDATE stickers SET reviewPending = 0 WHERE reviewPending = 1")
    suspend fun clearAllReviewPending(): Int

    @Update
    suspend fun update(sticker: Sticker)

    @Query("SELECT COUNT(*) FROM stickers")
    suspend fun count(): Int
}
