package com.uroboros.memory.judge

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Доступ к разобранным парам ([JudgeVerdict]).
 *
 * Запросов ровно столько, сколько нужно прогону и экрану. Ничего впрок — лишний
 * запрос со временем начинает жить своей жизнью и расходиться с тем, как
 * механизм устроен на самом деле.
 */
@Dao
interface JudgeVerdictDao {

    /**
     * Перезапись, а не отказ при совпадении ключа: та же пара тем же судьёй
     * должна дать тот же ответ, и если прогон почему-то вернулся к уже
     * разобранной паре, свежая строка не хуже прежней.
     *
     * ОСТОРОЖНО: перезапись сбрасывает и отметку человека, потому что строка
     * кладётся целиком. Прогон до разобранных пар не доходит — он их
     * пропускает, — так что сегодня этого не случается; появится другой
     * писатель, и отметку придётся сохранять отдельно.
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

    /**
     * Пары с данным вердиктом судьи и данным ответом человека, свежие первыми.
     *
     * Одним запросом на все случаи, а не тремя похожими: экрану нужны то
     * непросмотренные споры, то просмотренные, и три почти одинаковых запроса
     * разошлись бы при первой же правке условия.
     */
    @Query(
        "SELECT * FROM judge_verdicts " +
            "WHERE loadFingerprint = :fingerprint AND verdict = :verdict AND humanVerdict = :human " +
            "ORDER BY judgedAt DESC"
    )
    suspend fun pairs(fingerprint: String, verdict: String, human: String): List<JudgeVerdict>

    /** Что сказал человек о паре. Снятие отметки — тот же вызов с UNREVIEWED и null. */
    @Query(
        "UPDATE judge_verdicts SET humanVerdict = :human, reviewedAt = :at " +
            "WHERE firstId = :firstId AND secondId = :secondId AND loadFingerprint = :fingerprint"
    )
    suspend fun mark(
        firstId: Long,
        secondId: Long,
        fingerprint: String,
        human: String,
        at: Long?,
    ): Int

    /** Сколько пар разобрано этим судьёй — для прибора прогона. */
    @Query("SELECT COUNT(*) FROM judge_verdicts WHERE loadFingerprint = :fingerprint")
    suspend fun countFor(fingerprint: String): Int

    /**
     * Все вердикты данного судьи. Для счёта по кольцам сита: кольцо — функция
     * текстов пары, в таблице его нет, и посчитать его можно только по строкам
     * целиком (см. JudgeLauncher.counters).
     */
    @Query("SELECT * FROM judge_verdicts WHERE loadFingerprint = :fingerprint")
    suspend fun all(fingerprint: String): List<JudgeVerdict>

    /** Сколько пар с данным вердиктом судьи. */
    @Query(
        "SELECT COUNT(*) FROM judge_verdicts WHERE loadFingerprint = :fingerprint AND verdict = :verdict"
    )
    suspend fun countVerdict(fingerprint: String, verdict: String): Int

    /**
     * Сколько пар с данным ответом человека.
     *
     * Три числа — найдено, просмотрено, признано промахом — считаются порознь
     * намеренно: по одному из них не отличить молчащего судью от неоткрытого
     * списка, а по двум — согласие от отмахивания.
     */
    @Query(
        "SELECT COUNT(*) FROM judge_verdicts WHERE loadFingerprint = :fingerprint AND humanVerdict = :human"
    )
    suspend fun countHuman(fingerprint: String, human: String): Int

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

    /**
     * Убрать показания судей, которых больше нет.
     *
     * Сменилась модель или промпт — прежние строки перестают отвечать хоть на
     * какой-нибудь вопрос: они об отношении пар по мнению судьи, который больше
     * не запускается. Держать их значило бы копить по комплекту на каждого
     * судью, которого мы когда-либо пробовали.
     *
     * Вместе с ними уходят и отметки человека по тем парам. Это осознанно:
     * отметка говорит «я посмотрел на ЭТОТ вердикт», а вердикт сменился.
     */
    @Query("DELETE FROM judge_verdicts WHERE loadFingerprint != :fingerprint")
    suspend fun forgetOtherJudges(fingerprint: String): Int
}
