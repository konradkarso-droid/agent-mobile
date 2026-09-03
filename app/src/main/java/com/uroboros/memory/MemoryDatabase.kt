package com.uroboros.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Sticker::class, ActionEvidence::class, LastStableSnapshot::class],
    version = 8,
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

        // Item 3 / Track A (2026-08-20): два новых tag-only поля на Sticker —
        // source (откуда пришёл факт) и confidence (насколько он подтверждён).
        // Существующие строки (созданные до этого поля) считаются проверенными:
        // USER_STATED / OBSERVED — устаревание это отдельная ось (item 5/6),
        // не имеет отношения к source/confidence.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `stickers` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'USER_STATED'"
                )
                db.execSQL(
                    "ALTER TABLE `stickers` ADD COLUMN `confidence` TEXT NOT NULL DEFAULT 'OBSERVED'"
                )
            }
        }

        // Item 3 (обратная эмерджентность вместо ручной важности): счётчик того,
        // сколько раз запись оказалась уместной по запросу ПОЛЬЗОВАТЕЛЯ. Смысл поля
        // и границы его толкования — рядом с самим полем в Sticker.kt.
        //
        // Существующие строки получают 0, и это верное для них значение: их полезность
        // никогда не измерялась. Ноль здесь читается как "не проверено", а не как
        // "бесполезна", поэтому доливать историческим строкам что-то отличное от нуля
        // было бы выдумыванием измерения, которого не было.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `stickers` ADD COLUMN `userMatchCount` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Здесь НЕТ fallbackToDestructiveMigration, и это осознанно.
        //
        // Он выглядит подстраховкой для древних версий, но срабатывает не на них:
        // Room уходит на этот путь при ЛЮБОМ переходе версии, для которого не нашлось
        // миграции, — в том числе когда новую миграцию написали, но забыли добавить
        // в addMigrations ниже. Расплата за такую забывчивость — стёртая база.
        //
        // Направление ошибки выбрано в другую сторону. Без него незарегистрированная
        // миграция роняет приложение при открытии базы. Падение видно сразу и чинится
        // одной строкой; стирание не видно совсем — пустая память на экране выглядит
        // как чистая установка, и отличить одно от другого уже нечем.
        //
        // Чего это решение НЕ умеет: пока миграция не дописана, приложение не
        // открывается вообще. Это цена, а не побочный эффект.
        //
        // Добавляя новую версию схемы: пишется миграция, регистрируется в
        // addMigrations, и только потом меняется поле в сущности.

        @Volatile
        private var INSTANCE: MemoryDatabase? = null
        fun getInstance(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "uroboros_memory.db"
                ).addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                 .build().also { INSTANCE = it }
            }
        }
    }
}
