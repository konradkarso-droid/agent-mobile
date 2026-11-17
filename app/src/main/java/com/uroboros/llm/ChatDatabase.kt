package com.uroboros.llm

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Лента чатов телеграма ([ChatLine]) — своя база `uroboros_chats.db`.
 *
 * ПОЧЕМУ НЕ ТАБЛИЦА В БАЗЕ ЛЕНТЫ ЭКРАНА. Две причины:
 *  - данные телеграма учить модели запрещено его условиями, а из ленты экрана
 *    собираются дневные пары учёбы — отдельный файл не попадёт в них по
 *    ошибке и стирается целиком, если переписку надо удалить;
 *  - новая база создаётся с нуля и миграции не требует: ошибка в ней не может
 *    сломать открытие ленты экрана, переход которой проверяется только на
 *    телефоне.
 *
 * Выгружается вместе с остальными (memory.DatabaseExporter). Деструктивного
 * отката нет: при подъёме версии сюда добавляется миграция.
 */
@Database(entities = [ChatLine::class], version = ChatDatabase.SCHEMA_VERSION, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatLineDao(): ChatLineDao

    companion object {
        const val SCHEMA_VERSION = 3

        /**
         * Поле [ChatLine.recall]. У прежних строк оно пустое — и это правда
         * только для ответов, данных до того, как чатам стали подаваться
         * записи памяти; у ответов между этим и появлением поля поданные
         * записи не восстановить. Форма — как у `String? = null`: TEXT без
         * значения по умолчанию.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_lines ADD COLUMN recall TEXT")
            }
        }

        /**
         * Поле [ChatLine.dreamNote]. У прежних строк оно пустое — и это правда:
         * до него описание ночи в телеграм не подавалось. Форма — как у
         * `String? = null`: TEXT без значения по умолчанию.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_lines ADD COLUMN dreamNote TEXT")
            }
        }

        const val FILE_NAME = "uroboros_chats.db"

        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, FILE_NAME)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }
    }
}
