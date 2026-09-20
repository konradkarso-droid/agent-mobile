package com.uroboros.memory.dream

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DreamDao {

    /**
     * Сны одной ночи. Повтор ключа молча пропускается: одна цепочка в одну ночь
     * снится один раз (см. [Dream]).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(dreams: List<Dream>)

    /** Последняя ночь, в которую что-нибудь приснилось, — или null, если снов не было ни разу. */
    @Query("SELECT MAX(nightAt) FROM dreams")
    suspend fun lastNight(): Long?

    @Query("SELECT * FROM dreams WHERE nightAt = :nightAt")
    suspend fun ofNight(nightAt: Long): List<Dream>
}
