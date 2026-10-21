package com.uroboros.gate.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * База врат — отдельный файл, не база памяти и не база ленты.
 *
 * ПОЧЕМУ ОТДЕЛЬНО. По той же причине, что лента разговора (см.
 * [com.uroboros.llm.JournalDatabase], «почему отдельно от памяти»): ошибка в
 * схеме недоверенного канала не должна иметь власти над проверенным. Врата —
 * самая открытая наружу часть: в неё пишут чужие агенты. Не откроется база
 * врат — молчат врата, память и разговор живут как жили.
 *
 * ДЕСТРУКТИВНОГО ОТКАТА НЕТ, как у ленты: доверенности, знакомства и
 * поручительства — история, которую владелец подтверждал руками. Подняли
 * [SCHEMA_VERSION] и не написали миграцию — база не откроется вовсе: громкий
 * отказ вместо тихой потери.
 *
 * ЧТО ОБЯЗАН СДЕЛАТЬ ВЫЗЫВАЮЩИЙ. Обращение может бросить (схема, порча
 * файла). Врата без базы не принимают ничего: вызывающий ловит отказ и
 * показывает строку прибора «Врата: база не открылась — …», а не падает.
 *
 * ЧЕГО НЕТ: шифрования своим ключом — файл лежит во внутреннем хранилище
 * приложения, которое шифрует сам Android, как и базы памяти и ленты;
 * токена бота — он не хранится в базах вовсе. Выгрузка ([com.uroboros.memory.DatabaseExporter])
 * кладёт эту базу открытым файлом вместе с остальными.
 *
 * Файл подпадает под `res/xml/data_extraction_rules.xml`, как и остальные
 * базы: в облако и на другое устройство не переносится.
 */
@Database(
    entities = [
        GateNumberRow::class, GateKnownKeyRow::class, GateProxyRow::class, GateEvidenceRow::class,
        GateAcquaintanceRow::class, GateVouchRow::class, GateCardReadingRow::class, GateCardSentRow::class,
        GateOutgoingRow::class, GateAttemptRow::class, GateSignKeyRow::class,
    ],
    version = GateDatabase.SCHEMA_VERSION,
    exportSchema = false,
)
abstract class GateDatabase : RoomDatabase() {

    abstract fun gateDao(): GateDao

    companion object {

        /**
         * Версия схемы врат — своя, отдельно от баз памяти и ленты. При подъёме
         * сюда же добавляется миграция: без неё база не откроется.
         */
        const val SCHEMA_VERSION = 3

        const val FILE_NAME = "uroboros_gate.db"

        /**
         * Версия 2: ключи подписи агентов (`gate_sign_keys`, [GateSignKeyRow])
         * и столбец `signKey` у показаний карточек. Старые показания — без
         * ключа: карточек с ключом до этой версии не было, NULL — правда.
         *
         * Текст `CREATE TABLE` — слово в слово тот, что строит сам Room для
         * [GateSignKeyRow]: Room сверяет схему при открытии и не откроет базу
         * при расхождении. Поэтому у таблицы нет индекса — его имя и форму
         * пришлось бы повторять руками.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `gate_card_readings` ADD COLUMN `signKey` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gate_sign_keys` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`agent` TEXT NOT NULL, " +
                        "`signKey` TEXT NOT NULL, " +
                        "`seenMs` INTEGER NOT NULL, " +
                        "`trustedMs` INTEGER, " +
                        "`how` TEXT NOT NULL, " +
                        "`endedMs` INTEGER" +
                        ")",
                )
            }
        }

        /**
         * Версия 3: столбец `encKey` у показаний карточек — ключ шифрования
         * ([com.uroboros.gate.GateBox]). Старые показания — без ключа: карточек
         * с ним до этой версии не было, NULL — правда.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `gate_card_readings` ADD COLUMN `encKey` TEXT")
            }
        }

        @Volatile
        private var INSTANCE: GateDatabase? = null

        fun getInstance(context: Context): GateDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, GateDatabase::class.java, FILE_NAME)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
