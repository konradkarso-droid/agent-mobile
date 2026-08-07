package com.uroboros.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class Layer { RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE }
enum class Importance { LOW, MEDIUM, HIGH }

@Entity(tableName = "stickers")
data class Sticker(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    var lastAccessedAt: Long = System.currentTimeMillis(),
    var accessCount: Int = 0,
    val tag: String = "general",
    var layer: String = Layer.GREEN.name,
    var expiryTime: Long? = null,
    var importance: String = Importance.MEDIUM.name,
    var reviewPending: Boolean = false
)
