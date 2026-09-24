package com.uroboros.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.uroboros.memory.dream.AgentRecallDao
import com.uroboros.memory.dream.Dream
import com.uroboros.memory.dream.DreamDao
import com.uroboros.memory.dream.DreamNight
import com.uroboros.memory.dream.DreamServedDao
import com.uroboros.memory.judge.JudgeVerdict
import com.uroboros.memory.judge.JudgeVerdictDao

@Database(
    entities = [
        Sticker::class, ActionEvidence::class, LastStableSnapshot::class, JudgeVerdict::class,
        Dream::class, DreamNight::class,
    ],
    version = 20,
    exportSchema = false
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun stickerDao(): StickerDao
    abstract fun actionEvidenceDao(): ActionEvidenceDao
    abstract fun lastStableSnapshotDao(): LastStableSnapshotDao
    abstract fun judgeVerdictDao(): JudgeVerdictDao
    abstract fun dreamDao(): DreamDao
    abstract fun dreamServedDao(): DreamServedDao
    abstract fun agentRecallDao(): AgentRecallDao

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

        // Хранилище разобранных судьёй пар (см. JudgeVerdict). Только новая
        // таблица: существующих строк миграция не касается вовсе, поэтому
        // испортить ею память нельзя — в отличие от двух миграций выше, которые
        // правили сами записи.
        //
        // Пустая таблица — верное начальное состояние: до этой версии пары никто
        // не разбирал, и дописывать историческим парам какой-либо вердикт значило
        // бы выдумывать оценку, которой не было. Первый прогон разберёт их сам.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `judge_verdicts` (
                        `firstId` INTEGER NOT NULL,
                        `secondId` INTEGER NOT NULL,
                        `loadFingerprint` TEXT NOT NULL,
                        `forward` TEXT NOT NULL,
                        `backward` TEXT NOT NULL,
                        `verdict` TEXT NOT NULL,
                        `judgedAt` INTEGER NOT NULL,
                        `spentMs` INTEGER NOT NULL,
                        PRIMARY KEY(`firstId`, `secondId`, `loadFingerprint`)
                    )
                    """.trimIndent()
                )
            }
        }

        // Отметка человека на разобранной паре (см. JudgeVerdict.humanVerdict).
        // Две колонки к существующей таблице; строки, разобранные до этой
        // версии, получают "не смотрел" — единственное верное для них значение.
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `judge_verdicts` ADD COLUMN `humanVerdict` TEXT NOT NULL " +
                        "DEFAULT 'UNREVIEWED'"
                )
                db.execSQL("ALTER TABLE `judge_verdicts` ADD COLUMN `reviewedAt` INTEGER")
            }
        }

        // Сны ночного прохода (см. dream.Dream). Только новая таблица, записей
        // миграция не касается. Пустая таблица верна: до этой версии снов не было.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dreams` (
                        `nightAt` INTEGER NOT NULL,
                        `recordIds` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        PRIMARY KEY(`nightAt`, `recordIds`)
                    )
                    """.trimIndent()
                )
            }
        }

        // Отметка отвержения (см. Sticker.rejectedAt). Только новая пустая
        // колонка: у всех существующих записей null, то есть «не отвергалась», и
        // это верно — до этой версии отвергать было нечем.
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stickers ADD COLUMN rejectedAt INTEGER")
            }
        }

        // Итоги ночей сна (см. dream.DreamNight). Только новая таблица, записей
        // миграция не касается. Сны, приснившиеся до этой версии, остаются без
        // итога: какой он был, уже не восстановить, и выдумывать его нельзя.
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `nights` (
                        `nightAt` INTEGER NOT NULL,
                        `dreams` INTEGER NOT NULL,
                        `dreamers` INTEGER NOT NULL,
                        `dreamersCold` INTEGER NOT NULL,
                        `dreamersArchive` INTEGER NOT NULL,
                        `coldDreams` INTEGER NOT NULL,
                        `archiveDreams` INTEGER NOT NULL,
                        `skippedHidden` INTEGER NOT NULL,
                        `skippedQuestions` INTEGER NOT NULL,
                        `skippedAgentReports` INTEGER NOT NULL,
                        `ceilingHit` INTEGER NOT NULL,
                        PRIMARY KEY(`nightAt`)
                    )
                    """.trimIndent()
                )
            }
        }

        // Путь отвержения (см. Sticker.rejectedVia). Только новая пустая колонка.
        // У уже отвергнутых записей остаётся null — «путь не записан»: какой он
        // был, из базы не восстановить, а выдумывать его нельзя.
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stickers ADD COLUMN rejectedVia TEXT")
            }
        }

        // Отметка «подан» у сна (см. Dream.servedCount). Старые сны получают 0 и
        // null — «не подавался»: до этой версии подачи не было, так что это
        // правда, а не подстановка.
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dreams ADD COLUMN servedCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE dreams ADD COLUMN lastServedAt INTEGER")
            }
        }

        // Кто начал ночь (см. dream.DreamNight.startedBy). Только новая пустая
        // колонка. У прежних ночей остаётся null — «не записано»: кто их начал,
        // из базы не восстановить, а выдумывать нельзя.
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nights ADD COLUMN startedBy TEXT")
            }
        }

        // Вспоминание агентом (см. Sticker.agentRecallCount) и река ночи (см.
        // dream.DreamNight.riverTributaries). У записей счётчик 0 и время null —
        // вспоминать до этой версии было нечем, так что это правда. У ночей
        // оба числа null — «реки ещё не было», а не «притоков ноль».
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stickers ADD COLUMN agentRecallCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stickers ADD COLUMN lastAgentRecallAt INTEGER")
                db.execSQL("ALTER TABLE nights ADD COLUMN riverTributaries INTEGER")
                db.execSQL("ALTER TABLE nights ADD COLUMN riverDreams INTEGER")
            }
        }

        // Вспомненный сон (см. dream.Dream.recalledCount) — приток реки. У
        // прежних снов ноль и null — правда: вспоминать снов было нечем.
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dreams ADD COLUMN recalledCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE dreams ADD COLUMN lastRecalledAt INTEGER")
            }
        }

        // Просьбы, не снившиеся ночью (см. dream.DreamNight.skippedRequests). У
        // прежних ночей null — «просьбы тогда не узнавались и снились», а не
        // «просьб ноль».
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nights ADD COLUMN skippedRequests INTEGER")
            }
        }

        // Подхваченный владельцем сон (см. dream.Dream.pickedUpCount). У
        // прежних снов ноль и null — правда: подхват до этой версии не
        // считался.
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dreams ADD COLUMN pickedUpCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE dreams ADD COLUMN lastPickedUpAt INTEGER")
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
                ).addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20)
                 .build().also { INSTANCE = it }
            }
        }
    }
}
