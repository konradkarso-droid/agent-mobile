package com.uroboros.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Sticker::class, ActionEvidence::class, LastStableSnapshot::class],
    version = 6,
    exportSchema = false
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun stickerDao(): StickerDao
    abstract fun actionEvidenceDao(): ActionEvidenceDao
    abstract fun lastStableSnapshotDao(): LastStableSnapshotDao

    companion object {
        // Item 6b/8 (2026-08-17): новая таблица для снимка последнего стабильного
        // состояния TOTE-цикла. Написана как настоящая миграция (не destructive),
        // чтобы не стирать уже накопленные Sticker'ы на обновлении — тестовые данные
        // на устройстве сейчас не бесценны, но сама привычка мигрировать схему
        // (а не всегда пересоздавать с нуля) пригодится и для будущих изменений.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `last_stable_snapshot` (
                        `id` INTEGER NOT NULL,
                        `code` TEXT NOT NULL,
                        `savedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: MemoryDatabase? = null
        fun getInstance(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "uroboros_memory.db"
                ).addMigrations(MIGRATION_5_6)
                 .fallbackToDestructiveMigration() // подстраховка для версий < 5, не для 5→6
                 .build().also { INSTANCE = it }
            }
        }
    }
}
