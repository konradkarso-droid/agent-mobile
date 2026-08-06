package com.uroboros.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Sticker::class], version = 3, exportSchema = false)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun stickerDao(): StickerDao
    companion object {
        @Volatile
        private var INSTANCE: MemoryDatabase? = null
        fun getInstance(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "uroboros_memory.db"
                ).fallbackToDestructiveMigration()
                 .build().also { INSTANCE = it }
            }
        }
    }
}
