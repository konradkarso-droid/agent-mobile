package com.uroboros.memory.judge

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Пара, прочитанная прямым судьёй ([DirectJudge]): доли «спорят» в двух
 * порядках и решение сита.
 *
 * ОТДЕЛЬНО ОТ ВЕРДИКТОВ ПЕЧАТНОГО СУДЬИ ([JudgeVerdict]), и это не две копии
 * одного. Строка вердикта — «модель сказала цифру», по ней считаются
 * разобранные, спорные и доля промахов по отметкам человека, в том числе по
 * кольцам сита слов (JudgeLauncher.counters). Строки сита в той же таблице
 * вошли бы в эти числа как «разобрано, спора нет», и по счёту колец уже нельзя
 * было бы понять, находит ли внешнее кольцо хоть что-то.
 *
 * ОТПЕЧАТОК — модель и направление вместе ([sieveFingerprint]): доля зависит от
 * обоих. При смене любого прежние строки сита сносятся в начале прогона,
 * вердикты печатного судьи при этом не трогаются — они от направления не
 * зависят.
 *
 * Пара неупорядочена, меньший номер в [firstId] — как у [JudgeVerdict].
 */
@Entity(
    tableName = "judge_sieve",
    primaryKeys = ["firstId", "secondId", "sieveFingerprint"],
)
data class JudgeSieveRow(
    val firstId: Long,
    val secondId: Long,
    val sieveFingerprint: String,
    /** Доля «спорят», когда первой подана запись [firstId]. */
    val pForward: Double,
    /** Доля «спорят», когда первой подана запись [secondId]. */
    val pBackward: Double,
    /** Пошла ли пара печатному судье ([DirectJudge.keep]). */
    val kept: Boolean,
    val readAt: Long,
    /** Сколько заняли оба чтения пары — цена сита на этом устройстве. */
    val spentMs: Long,
)

@Dao
interface JudgeSieveDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: JudgeSieveRow)

    @Query("SELECT * FROM judge_sieve WHERE firstId = :firstId AND secondId = :secondId AND sieveFingerprint = :fingerprint")
    suspend fun get(firstId: Long, secondId: Long, fingerprint: String): JudgeSieveRow?

    /** Сколько пар прочитано ситом с этим отпечатком и сколько из них отброшено. */
    @Query("SELECT COUNT(*) FROM judge_sieve WHERE sieveFingerprint = :fingerprint")
    suspend fun countFor(fingerprint: String): Int

    @Query("SELECT COUNT(*) FROM judge_sieve WHERE sieveFingerprint = :fingerprint AND kept = 0")
    suspend fun countDropped(fingerprint: String): Int

    /** Строки другой модели или другого направления — см. «ОТПЕЧАТОК» у [JudgeSieveRow]. */
    @Query("DELETE FROM judge_sieve WHERE sieveFingerprint != :fingerprint")
    suspend fun forgetOtherSieves(fingerprint: String): Int

    /** Строки пар, чьих записей в памяти больше нет, — как у вердиктов. */
    @Query(
        "DELETE FROM judge_sieve " +
            "WHERE firstId NOT IN (SELECT id FROM stickers) OR secondId NOT IN (SELECT id FROM stickers)"
    )
    suspend fun forgetSievesOfDeletedStickers(): Int
}
