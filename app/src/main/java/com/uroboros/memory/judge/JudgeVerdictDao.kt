package com.uroboros.memory.judge

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Доступ к разобранным парам ([JudgeVerdict]).
 *
 * Запросов ровно столько, сколько нужно прогону и экрану: положить вердикт,
 * спросить про одну пару, взять споры, сосчитать сделанное и убрать вердикты по
 * исчезнувшим записям. Ничего впрок — лишний запрос со временем начинает жить
 * своей жизнью и расходиться с тем, как механизм устроен на самом деле.
 */
@Dao
interface JudgeVerdictDao {

    /**
     * Перезапись, а не отказ при совпадении ключа: та же пара тем же судьёй
     * должна дать тот же ответ, и если прогон почему-то вернулся к уже
     * разобранной паре, свежая строка не хуже прежней.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(verdict: JudgeVerdict)

    /**
     * Разобрана ли пара этим судьёй. Ноль означает «нет», и пара идёт в работу.
     *
     * Номера обязаны прийти упорядоченными, меньший первым, — см. [JudgeVerdict].
     * Перепутанные местами дадут ноль, и пара будет разобрана второй раз.
     */
    @Query(
        "SELECT COUNT(*) FROM judge_verdicts " +
            "WHERE firstId = :firstId AND secondId = :secondId AND loadFingerprint = :fingerprint"
    )
    suspend fun judged(firstId: Long, secondId: Long, fingerprint: String): Int

    /** Пары с данным вердиктом, свежие первыми. Экрану нужны спорные. */
    @Query(
        "SELECT * FROM judge_verdicts " +
            "WHERE loadFingerprint = :fingerprint AND verdict = :verdict " +
            "ORDER BY judgedAt DESC"
    )
    suspend fun withVerdict(fingerprint: String, verdict: String): List<JudgeVerdict>

    /** Сколько пар разобрано этим судьёй — для прибора прогона. */
    @Query("SELECT COUNT(*) FROM judge_verdicts WHERE loadFingerprint = :fingerprint")
    suspend fun countFor(fingerprint: String): Int

    /**
     * Убрать вердикты, чьих записей в памяти больше нет.
     *
     * Без этого удалённая запись оставляла бы после себя вердикты, которые
     * невозможно ни показать, ни объяснить: на экране была бы пара, одной
     * половины которой не существует. Возвращает число убранных строк, чтобы
     * уборку было видно, а не приходилось верить, что она случилась.
     */
    @Query(
        "DELETE FROM judge_verdicts " +
            "WHERE firstId NOT IN (SELECT id FROM stickers) " +
            "OR secondId NOT IN (SELECT id FROM stickers)"
    )
    suspend fun forgetVerdictsOfDeletedStickers(): Int
}
