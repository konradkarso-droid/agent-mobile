package com.uroboros.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Дыра №4, вторая половина (аудит 2026-08-21): раньше горячий путь getContext()
 * тянул ВСЮ таблицу в память через getAll() (дважды за вызов — ещё раз внутри
 * migrateExpired), фильтровал и сортировал в Kotlin, а потом писал каждую строку
 * целиком через @Update. Две проблемы:
 *
 *   1. O(N) полный скан на каждое обращение к контексту — на нескольких тысячах
 *      стикеров это заметная пауза на слабом устройстве.
 *   2. @Update пишет ВСЕ колонки строки. Два параллельных обращения (например,
 *      UI "Показать" и вакцина-строка из TOTE-цикла) перетирали друг другу
 *      accessCount и — что хуже — layer: прогрев или остывание молча откатывались.
 *
 * Решение: фильтрация, сортировка и лимит уезжают в SQL; обновления становятся
 * точечными по id и по конкретным колонкам. Инкремент accessCount делается
 * выражением в самом UPDATE, а не read-modify-write в Kotlin — потерянных
 * инкрементов больше не бывает по построению.
 */
@Dao
interface StickerDao {
    @Insert
    suspend fun insert(sticker: Sticker): Long

    @Query("SELECT * FROM stickers WHERE id = :id")
    suspend fun getById(id: Long): Sticker?

    @Query("SELECT * FROM stickers ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<Sticker>

    /**
     * Полнотекстовый поиск. reviewPending теперь отсекается В SQL, а не после LIMIT:
     * раньше лимит применялся до фильтра, поэтому наличие спорных записей молча
     * уменьшало число реально возвращаемых результатов.
     */
    @Query(
        "SELECT * FROM stickers WHERE reviewPending = 0 " +
            "AND content LIKE '%' || :query || '%' " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<Sticker>

    /**
     * Ранжированная выборка по набору слоёв — замена getAll()+сортировки в Kotlin.
     * CASE воспроизводит ровно Importance.valueOf(importance).ordinal по убыванию
     * (LOW=0, MEDIUM=1, HIGH=2): сортировка по самой строке дала бы алфавитный
     * порядок HIGH/LOW/MEDIUM, то есть другой результат. Неизвестное значение
     * importance деградирует до 0, а не роняет выборку исключением.
     */
    @Query(
        "SELECT * FROM stickers WHERE reviewPending = 0 AND layer IN (:layers) " +
            "ORDER BY CASE importance WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 1 ELSE 0 END DESC, " +
            "createdAt DESC LIMIT :limit"
    )
    suspend fun getRanked(layers: List<String>, limit: Int): List<Sticker>

    /** Только те строки, которым реально пора менять слой, — вместо скана всей таблицы. */
    @Query("SELECT * FROM stickers WHERE expiryTime IS NOT NULL AND expiryTime <= :now")
    suspend fun getExpired(now: Long): List<Sticker>

    /** Точечная запись слоя: не трогает accessCount, reviewPending и содержимое. */
    @Query("UPDATE stickers SET layer = :layer, expiryTime = :expiryTime WHERE id = :id")
    suspend fun updateLayer(id: Long, layer: String, expiryTime: Long?)

    /** Инкремент внутри SQL — параллельные обращения не теряют счёт. */
    @Query("UPDATE stickers SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE id = :id")
    suspend fun touchAccess(id: Long, now: Long)

    @Query("SELECT * FROM stickers ORDER BY createdAt DESC")
    suspend fun getAll(): List<Sticker>

    @Query("SELECT * FROM stickers WHERE tag = :tag AND layer IN (:layers)")
    suspend fun getByTagInLayers(tag: String, layers: List<String>): List<Sticker>

    @Query("SELECT * FROM stickers WHERE reviewPending = 1 ORDER BY createdAt DESC")
    suspend fun getPendingReview(): List<Sticker>

    @Query("UPDATE stickers SET reviewPending = 0 WHERE id = :id")
    suspend fun clearReviewPending(id: Long)

    @Query("UPDATE stickers SET reviewPending = 0 WHERE reviewPending = 1")
    suspend fun clearAllReviewPending(): Int

    /**
     * Полная перезапись строки. Оставлена для RiskTrigger в saveEvent() — там строка
     * только что вставлена и никто другой её ещё не видит. В путях чтения НЕ
     * использовать: см. KDoc интерфейса.
     */
    @Update
    suspend fun update(sticker: Sticker)

    @Query("SELECT COUNT(*) FROM stickers")
    suspend fun count(): Int

    // --- Canary (2026-08-22): read-only метрики для снимка "до/после" первого
    // реального запуска sweep. Взято из чужого опыта (canary-скрипт praxis):
    // поведенческий дрейф ловится сравнением цифр, а не отсутствием краша.
    //
    // Все запросы ниже — только COUNT/MIN, без загрузки строк в память: снимок
    // не должен сам создавать ту нагрузку, ради контроля которой он снимается.
    // Ни один из них ничего не пишет. Схема БД не меняется, миграция не нужна.

    /** Сколько строк реально просрочено на момент now — размер накопленного долга. */
    @Query("SELECT COUNT(*) FROM stickers WHERE expiryTime IS NOT NULL AND expiryTime <= :now")
    suspend fun countExpired(now: Long): Int

    /** Сколько строк в конкретном слое — для распределения по спектру. */
    @Query("SELECT COUNT(*) FROM stickers WHERE layer = :layer")
    suspend fun countInLayer(layer: String): Int

    /** Спорные записи. Sweep их не трогает: число обязано остаться прежним. */
    @Query("SELECT COUNT(*) FROM stickers WHERE reviewPending = 1")
    suspend fun countPendingReview(): Int

    /** Самый старый истёкший срок — показывает, насколько давно копится долг. */
    @Query("SELECT MIN(expiryTime) FROM stickers WHERE expiryTime IS NOT NULL AND expiryTime <= :now")
    suspend fun oldestExpiredAt(now: Long): Long?

    // --- Canary, добавка 2026-08-22 (после первого снимка на устройстве).
    //
    // Первый реальный замер показал "Просрочено 0" при 14 записях. Это могло
    // означать две разные вещи, и снимок их не различал:
    //   (а) сроки у записей есть, но все ещё не наступили — sweep'у просто рано;
    //   (б) сроков нет вовсе (expiryTime IS NULL) — sweep'у нечего разбирать
    //       никогда, и ждать бессмысленно.
    // Оба запроса ниже существуют ровно ради этого различения.

    /**
     * Ближайший ещё НЕ наступивший срок истечения, либо null если таких нет.
     *
     * Условие "> :now" обязательно: без него MIN вернул бы самый старый
     * просроченный срок, то есть продублировал бы oldestExpiredAt и выдал бы
     * прошлое за "ближайшее будущее". Сейчас просроченных нет и разницы не
     * видно — тем важнее заложить правильно, пока это ничего не стоит.
     */
    @Query("SELECT MIN(expiryTime) FROM stickers WHERE expiryTime IS NOT NULL AND expiryTime > :now")
    suspend fun nextExpiryAt(now: Long): Long?

    /**
     * Сколько строк вообще без срока. Это и есть диагноз случая (б): такие
     * записи не попадут в sweep ни сегодня, ни через год.
     */
    @Query("SELECT COUNT(*) FROM stickers WHERE expiryTime IS NULL")
    suspend fun countWithoutExpiry(): Int
}
