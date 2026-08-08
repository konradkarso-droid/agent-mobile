package com.uroboros.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ActionEvidenceDao {

    @Insert
    suspend fun insert(evidence: ActionEvidence)

    // Только чтение — записи никогда не редактируются и не удаляются вручную.
    @Query("SELECT * FROM action_evidence ORDER BY timestamp DESC")
    suspend fun getAll(): List<ActionEvidence>

    @Query("SELECT * FROM action_evidence WHERE result = 'DENY' ORDER BY timestamp DESC")
    suspend fun getDenied(): List<ActionEvidence>
}
