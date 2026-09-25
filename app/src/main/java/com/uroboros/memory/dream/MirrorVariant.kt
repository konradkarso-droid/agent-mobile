package com.uroboros.memory.dream

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Один вариант зеркала одной ночи (правило — в [Mirror]).
 *
 * ПОЧЕМУ СВОЯ ТАБЛИЦА, А НЕ СНЫ. Сон — связь настоящих записей по номерам, её
 * собирает код, и текст записей подставляется только при показе или подаче
 * (см. [Dream]). Вариант зеркала — текст, который сочинила модель. Подай его
 * модели как сон, и сочинённое прочиталось бы агентом как воспоминание. Здесь
 * это исключено устройством: таблицу снов читает подача модели, эту — только
 * показ владельцу ([MirrorView]) и сверка ([MirrorChecker]).
 *
 * Строки копятся по ночам и сами не удаляются: не больше [Mirror.MAX_VARIANTS]
 * за ночь по кнопке.
 */
@Entity(tableName = "mirror")
data class MirrorVariant(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Ночь, в которую зеркало смотрело; то же значение, что у [DreamNight.nightAt]. */
    val nightAt: Long,

    /** Имя значения [Mirror.Kind]. */
    val kind: String,

    /** Вариант, как его дала модель, без нумерации и кавычек по краям. */
    val text: String,

    /** Номера ходов ленты, на которые смотрело зеркало, с единицы. */
    val fromTurn: Int,
    val toTurn: Int,

    /**
     * Основы текста, на который смотрело зеркало, через запятую: они при сверке
     * не засчитываются (см. [Mirror.check]).
     */
    val excludedStems: String,

    /** Со сколькими репликами владельца вариант уже сверен. */
    val repliesSeen: Int = 0,

    /** Время реплики, в которой вариант сбылся, мс; null — не сбылся. */
    val fulfilledAt: Long? = null,

    /** Совпавшие основы через запятую; null — не сбылся. */
    val fulfilledWords: String? = null,

    /**
     * Сама сбывшаяся реплика — для показа «сбылось в реплике «…»»; null — не
     * сбылся.
     */
    val fulfilledReply: String? = null,
)
