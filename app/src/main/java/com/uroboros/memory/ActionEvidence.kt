package com.uroboros.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Неизменяемая запись о проверке действия gate'ом — evidence-trail.
 * Никогда не обновляется и не удаляется после создания (append-only).
 * signalBreakdown хранится как простая строка "key=value;key=value" —
 * без Gson/JSON-библиотек, чтобы не тащить новую зависимость ради одной таблицы.
 */
@Entity(tableName = "action_evidence")
data class ActionEvidence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val actionType: String,
    val requestedBy: String,
    val confirmedBy: String?,
    val provenance: String,
    val result: String,
    val riskWeight: Double,
    val signalBreakdown: String,
    val reason: String
)

/** Превращает ActionVerdict + исходный ActionRequest в запись для сохранения. */
fun ActionVerdict.toEvidence(request: ActionRequest): ActionEvidence {
    val breakdown = signalBreakdown.entries.joinToString(";") { (k, v) -> "$k=$v" }
    return ActionEvidence(
        actionType = request.type.name,
        requestedBy = request.requestedBy,
        confirmedBy = request.confirmedBy,
        provenance = request.provenance.name,
        result = result.name,
        riskWeight = riskWeight,
        signalBreakdown = breakdown,
        reason = reason
    )
}
