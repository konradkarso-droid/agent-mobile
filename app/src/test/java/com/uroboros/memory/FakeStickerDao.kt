package com.uroboros.memory

/**
 * Подставной StickerDao для обычных JVM-тестов. Живёт в тестовом наборе
 * исходников, в APK не попадает.
 *
 * Устройство. Ответы задаются лямбдами, обращения записываются списками. Ни
 * настоящей базы, ни Android — значит тест запускается на CI за миллисекунды и
 * его результат не зависит от содержимого устройства.
 *
 * НЕПОДГОТОВЛЕННЫЙ МЕТОД БРОСАЕТ ИСКЛЮЧЕНИЕ, а не возвращает ноль или пустой
 * список. Методов в интерфейсе 28, а любой путь чтения трогает шесть-восемь;
 * молчаливое умолчание у остальных двадцати означало бы зелёный тест на пустом
 * месте — проверяемый код позвал бы что-то незамеченное и получил бы правдоподобный
 * ответ ни о чём. Падение с именем метода вдобавок отвечает на вопрос, что путь
 * трогает НА САМОМ ДЕЛЕ, а не по чьей-то памяти: неожиданное падение здесь — это
 * находка, а не поломка теста.
 *
 * ЧЕГО ЭТА ПОДДЕЛКА НЕ УМЕЕТ, и это важнее того, что умеет:
 *
 * 1. Она не воспроизводит SQLite. Настоящий searchAnyCase сравнивает байты, и
 *    кириллическое слово с заглавной буквы поэтому не находится строчным
 *    префиксом — ради чего второй параметр и заведён. Здесь ответ отдаёт лямбда
 *    теста, то есть найдётся ровно то, что тест положил.
 *
 *    Отсюда граница: через эту подделку доказуемо только то, что вызывающий
 *    ЗАПРАШИВАЕТ оба варианта регистра. Что он их НАХОДИТ — свойство SQL, и
 *    проверяется оно либо на устройстве, либо тестом с базой в памяти. Тест,
 *    заявляющий здесь второе, будет зелёным при сломанном запросе.
 *
 *    То же и вдвойне про searchHiddenAnyCase. Он обязан искать теми же двумя
 *    условиями LIKE, что и searchAnyCase, иначе видимое и скрытое сосчитаются
 *    по разным множествам. Здесь оба ответа приходят от разных лямбд теста,
 *    так что расхождение самих запросов эта подделка не увидит НИКОГДА —
 *    равенство держится только соседством в StickerDao.kt. Доказуемо здесь
 *    лишь то, что вызывающий спрашивает оба, на тех же ступенях и теми же
 *    префиксами.
 *
 * 2. Она не сортирует и не режет лимитом. Настоящие getRanked и searchAnyCase
 *    делают ORDER BY и LIMIT на стороне базы; здесь лямбда возвращает что дали,
 *    включая случай, когда записей больше запрошенного лимита. Тест, которому
 *    важен лимит базы, должен резать сам — иначе он проверит не то.
 *
 * 3. Она ничего не хранит между вызовами: записанные touchAccess и touchUserMatch
 *    не меняют того, что вернёт следующий getRanked. Проверять здесь можно факт
 *    обращения, а не его последствия.
 */
class FakeStickerDao : StickerDao {

    // --- Что отдавать (задаёт тест) ---

    /** Ответ на getRanked(layers, limit). */
    var onGetRanked: ((layers: List<String>, limit: Int) -> List<Sticker>)? = null

    /** Ответ на searchAnyCase(query, queryCapitalized, limit). */
    var onSearchAnyCase: ((query: String, queryCapitalized: String, limit: Int) -> List<Sticker>)? = null

    /**
     * Ответ на searchHiddenAnyCase(query, queryCapitalized, limit) — записи в
     * карантине. Лямбда СВОЯ, а не общая с onSearchAnyCase: тест обязан иметь
     * возможность задать разные ответы видимому и скрытому поиску, иначе
     * случай "нашлось ноль, а трое скрыты" — ради которого счётчик и заведён —
     * выразить было бы нечем.
     */
    var onSearchHiddenAnyCase: ((query: String, queryCapitalized: String, limit: Int) -> List<HiddenRow>)? = null

    /** Ответ на getExpired(now) — путь чтения зовёт его первым, через migrateExpired. */
    var onGetExpired: ((now: Long) -> List<Sticker>)? = null

    // --- Что записалось (читает тест) ---

    data class SearchCall(val query: String, val queryCapitalized: String, val limit: Int)
    data class RankedCall(val layers: List<String>, val limit: Int)
    data class LayerUpdate(val id: Long, val layer: String, val expiryTime: Long?)

    /** Обращения к searchAnyCase по порядку — по ним и читается лестница. */
    val searchCalls = mutableListOf<SearchCall>()

    /**
     * Обращения к searchHiddenAnyCase. Отдельный список, а не общий с
     * searchCalls: слитые в один, они не дали бы проверить главное — что
     * скрытое спрашивается на ТЕХ ЖЕ ступенях, что и видимое, и что счётчик
     * скрытых не двигает спуск по лестнице.
     */
    val hiddenSearchCalls = mutableListOf<SearchCall>()
    val rankedCalls = mutableListOf<RankedCall>()
    val layerUpdates = mutableListOf<LayerUpdate>()
    val touchedAccess = mutableListOf<Long>()
    val touchedUserMatch = mutableListOf<Long>()

    /** Только запрошенные строчные префиксы, в порядке обращения. */
    val searchedPrefixes: List<String> get() = searchCalls.map { it.query }

    // --- Подготовленные методы ---

    override suspend fun searchAnyCase(
        query: String,
        queryCapitalized: String,
        limit: Int
    ): List<Sticker> {
        searchCalls += SearchCall(query, queryCapitalized, limit)
        val answer = onSearchAnyCase ?: unprepared("searchAnyCase")
        return answer(query, queryCapitalized, limit)
    }

    override suspend fun searchHiddenAnyCase(
        query: String,
        queryCapitalized: String,
        limit: Int
    ): List<HiddenRow> {
        hiddenSearchCalls += SearchCall(query, queryCapitalized, limit)
        val answer = onSearchHiddenAnyCase ?: unprepared("searchHiddenAnyCase")
        return answer(query, queryCapitalized, limit)
    }

    override suspend fun getRanked(layers: List<String>, limit: Int): List<Sticker> {
        rankedCalls += RankedCall(layers, limit)
        val answer = onGetRanked ?: unprepared("getRanked")
        return answer(layers, limit)
    }

    override suspend fun getExpired(now: Long): List<Sticker> {
        val answer = onGetExpired ?: unprepared("getExpired")
        return answer(now)
    }

    override suspend fun updateLayer(id: Long, layer: String, expiryTime: Long?) {
        layerUpdates += LayerUpdate(id, layer, expiryTime)
    }

    override suspend fun touchAccess(id: Long, now: Long) {
        touchedAccess += id
    }

    override suspend fun touchUserMatch(id: Long) {
        touchedUserMatch += id
    }

    // --- Неподготовленные: падают с именем метода ---

    override suspend fun insert(sticker: Sticker): Long = unprepared("insert")
    override suspend fun getById(id: Long): Sticker? = unprepared("getById")
    override suspend fun getRecent(limit: Int): List<Sticker> = unprepared("getRecent")
    override suspend fun search(query: String, limit: Int): List<Sticker> = unprepared("search")
    override suspend fun getAll(): List<Sticker> = unprepared("getAll")
    override suspend fun getByTagInLayers(tag: String, layers: List<String>): List<Sticker> =
        unprepared("getByTagInLayers")

    override suspend fun getPendingReview(): List<Sticker> = unprepared("getPendingReview")
    override suspend fun clearReviewPending(id: Long) = unprepared("clearReviewPending")
    override suspend fun clearAllReviewPending(): Int = unprepared("clearAllReviewPending")
    override suspend fun update(sticker: Sticker) = unprepared("update")

    override suspend fun count(): Int = unprepared("count")
    override suspend fun countExpired(now: Long): Int = unprepared("countExpired")
    override suspend fun countInLayer(layer: String): Int = unprepared("countInLayer")
    override suspend fun countPendingReview(): Int = unprepared("countPendingReview")
    override suspend fun oldestExpiredAt(now: Long): Long? = unprepared("oldestExpiredAt")
    override suspend fun nextExpiryAt(now: Long): Long? = unprepared("nextExpiryAt")
    override suspend fun countWithoutExpiry(): Int = unprepared("countWithoutExpiry")
    override suspend fun sumUserMatches(): Int = unprepared("sumUserMatches")
    override suspend fun countWithUserMatches(): Int = unprepared("countWithUserMatches")
    override suspend fun maxUserMatches(): Int = unprepared("maxUserMatches")

    override suspend fun reassignProvenanceByPrefix(
        contentPrefix: String,
        oldSource: String,
        newSource: String,
        newConfidence: String
    ): Int = unprepared("reassignProvenanceByPrefix")

    private fun unprepared(name: String): Nothing = throw IllegalStateException(
        "FakeStickerDao: проверяемый код позвал $name(), а тест этого не готовил. " +
            "Либо путь чтения изменился и теперь трогает больше, чем считалось, " +
            "либо тесту не хватает настройки. Разбираться надо с обоими вариантами, " +
            "а не подставлять сюда пустой ответ."
    )
}
