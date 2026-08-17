package com.uroboros.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Item 6b/8: снимок последнего стабильного (успешно скомпилированного) состояния
 * TOTE-цикла. Не история версий и не настоящий git — одна строка, перезаписывается
 * при каждой успешной компиляции. id жёстко зафиксирован на 1, чтобы insert с
 * REPLACE-стратегией всегда затирал предыдущий снимок, а не копил историю.
 * Читается только при эвакуации — как якорь для отката, а не на каждом шаге.
 */
@Entity(tableName = "last_stable_snapshot")
data class LastStableSnapshot(
    @PrimaryKey val id: Int = 1,
    val code: String,
    val savedAt: Long = System.currentTimeMillis()
)
