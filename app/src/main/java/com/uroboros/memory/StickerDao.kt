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

    @Query("SELECT *
