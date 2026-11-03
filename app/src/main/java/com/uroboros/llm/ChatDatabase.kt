package com.uroboros.llm

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
        const val SCHEMA_VERSION = 1

        const val FILE_NAME = "uroboros_chats.db"

        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, FILE_NAME)
                    .build().also { INSTANCE = it }
            }
    }
}
