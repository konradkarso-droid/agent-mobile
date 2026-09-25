package com.uroboros.memory.dream

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Одна попытка вывода по одному сну одной ночи (правило — в [Conclusion]).
 * Хранятся и принятые, и отброшенные: по отброшенной строке видно, что сон
 * уже пробовался (см. [ConclusionStep]), и владелец видит на экране, что и
 * почему отсеяно.
 *
 * ПОЧЕМУ СВОЯ ТАБЛИЦА, А НЕ СНЫ И НЕ ЗАПИСИ. Сон — связь настоящих записей по
 * номерам, её собирает код (см. [Dream]). Вывод — текст, который сочинила
 * модель. Положи его к снам или в записи памяти, и сочинённое прочиталось бы
 * агентом как сон или как запись. Здесь это исключено устройством: таблицу
 * снов и записи читает подача модели, эту — только показ владельцу
 * ([ConclusionView]) и счёт разрядки любопытства ([CuriosityGauge]).
 *
 * Строки копятся по ночам и сами не удаляются: не больше
 * [Conclusion.MAX_PER_NIGHT] за ночь.
 */
@Entity(tableName = "conclusions")
data class ConclusionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Ночь вывода; то же значение, что у [DreamNight.nightAt]. */
    val nightAt: Long,

    /** Ключ сна, по которому вывод, — как у [Dream]: ночь сна и строка номеров. */
    val dreamNightAt: Long,
    val dreamRecordIds: String,

    /**
     * Вывод после разбора ([Conclusion.parse]). У отказа по разбору — первая
     * строка ответа как есть, может быть пустой. Строка пишется только когда
     * модель ответила: сбой строки не оставляет (см. [ConclusionStep]).
     */
    val text: String,

    /** Прошёл проверку ([Conclusion.check]). */
    val accepted: Boolean,

    /** Причина отказа; null у прошедшего. */
    val reason: String?,
)

/** Ключ сна в таблице выводов: ночь сна и строка номеров, как у [Dream]. */
data class ConclusionKey(val dreamNightAt: Long, val dreamRecordIds: String) {
    companion object {
        fun of(dream: Dream) = ConclusionKey(dream.nightAt, dream.recordIds)
    }
}
