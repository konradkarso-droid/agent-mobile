package com.uroboros.memory.dream

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DreamDao {

    /**
     * Сны одной ночи. Повтор ключа молча пропускается: одна цепочка в одну ночь
     * снится один раз (см. [Dream]).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(dreams: List<Dream>)

    /**
     * Итог ночи. Повтор ключа — ошибка, а не пропуск: два прохода с одним
     * началом слили бы сны двух ночей в одну.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNight(night: DreamNight)

    /**
     * Последняя ночь — или null, если прохода не было ни разу. Берётся по
     * итогам, а не по снам: ночь без снов тоже ночь (см. [DreamNight]).
     */
    @Query("SELECT * FROM nights ORDER BY nightAt DESC LIMIT 1")
    suspend fun lastNight(): DreamNight?

    @Query("SELECT * FROM dreams WHERE nightAt = :nightAt")
    suspend fun ofNight(nightAt: Long): List<Dream>

    /**
     * Сны не старше [since], которые хоть раз подхвачены, вспомнены или
     * получили ответ, — только они и могут сжать пружину любопытства (см.
     * [CuriosityPressure]). Остальные дали бы ноль, и читать их незачем.
     */
    @Query(
        "SELECT * FROM dreams WHERE nightAt >= :since " +
            "AND (pickedUpCount > 0 OR recalledCount > 0 OR answeredCount > 0)"
    )
    suspend fun stirredSince(since: Long): List<Dream>

    /**
     * Когда владельцу в последний раз предложено спросить о сне; null — ни
     * разу. Нужно, чтобы знать, ждёт ли вопрос ответа (см. [CuriosityAsk]).
     */
    @Query("SELECT MAX(askedAt) FROM dreams")
    suspend fun lastAskedAt(): Long?

    /**
     * Лидеры по касаниям без подсказки последних [limit] ночей, от новых к
     * старым ([DreamNight.unpromptedLeaderId]; null — лидера не было или его
     * тогда не записывали). Ряд для условия кандидата, см. [UnpromptedLeader].
     */
    @Query("SELECT unpromptedLeaderId FROM nights ORDER BY nightAt DESC LIMIT :limit")
    suspend fun lastUnpromptedLeaders(limit: Int): List<Long?>
}
