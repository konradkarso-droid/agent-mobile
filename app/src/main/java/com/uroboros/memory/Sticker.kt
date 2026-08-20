package com.uroboros.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class Layer { RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE }
enum class Importance { LOW, MEDIUM, HIGH }

// Item 3 / Track A (info-source trust, designed 2026-08-20): два новых поля,
// tag-only — никогда не участвуют в Prism decay/ranking математике.
// Устанавливаются один раз при создании, затем не пересматриваются.
enum class SourceKind { USER_STATED, AGENT_INFERRED, OCR_EXTRACTED }
enum class ConfidenceLevel { OBSERVED, INFERRED, UNCERTAIN }

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
    var reviewPending: Boolean = false,
    val source: String = SourceKind.USER_STATED.name,
    val confidence: String = ConfidenceLevel.OBSERVED.name
)
