package com.uroboros.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stickers")
data class Sticker(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val content: String,

    val createdAt: Long = System.currentTimeMillis(),

    var lastAccessedAt: Long = System.currentTimeMillis(),

    var accessCount: Int = 0,

    val tag: String = "general"
)
