package com.uroboros.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LastStableSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(snapshot: LastStableSnapshot)

    @Query("SELECT * FROM last_stable_snapshot WHERE id = 1")
    suspend fun get(): LastStableSnapshot?

    @Query("DELETE FROM last_stable_snapshot")
    suspend fun clear()
}
