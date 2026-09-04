package com.uroboros.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Строка карантина в урезанном виде: только то, чем её можно отфильтровать и
 * сосчитать. Содержимое НЕ читается — счётчик скрытых говорит, сколько записей
 * не дошло до отбора, и для этого текст записи не нужен. Тот же довод, по
 * которому строки trace в HourglassMemory несут идентификаторы, а не содержимое:
 * список пересекает границу слоя памяти, и дальше им распоряжается вызывающий.
 *
 * Слой здесь обязателен. Видимый путь отбрасывает RED и неразрешённые слои НЕ в
 * SQL, а в Kotlin (см. searchByWords), поэтому и скрытые обязаны фильтроваться
 * теми же двумя строками — иначе запись из архива попала бы в счёт, а такая же
 * видимая не попала бы, и два числа мерили бы разные множества.
 */
data class HiddenRow(
    val id: Long,
    val layer: String
)

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
     *
     * ВНИМАНИЕ (29.08.2026): этот запрос НЕ находит слово, написанное в записи с
     * заглавной буквы, — см. searchAnyCase ниже. Оставлен как есть для тех мест,
     * где регистр заведомо совпадает; путь отбора контекста переведён на
     * searchAnyCase.
     */
    @Query(
        "SELECT * FROM stickers WHERE reviewPending = 0 " +
            "AND content LIKE '%' || :query || '%' " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<Sticker>

    /**
     * Тот же поиск, но не слепнущий на заглавную букву.
     *
     * ЧТО СЛОМАНО. Оператор LIKE в SQLite нечувствителен к регистру ТОЛЬКО для
     * латиницы: для кириллицы он сравнивает байты. Слова вопроса приводятся к
     * нижнему регистру ещё в HourglassMemory.meaningfulWords, а содержимое
     * записи не приводится нигде. Значит слово, стоящее в записи с заглавной
     * буквы, для поиска не существует вовсе.
     *
     * Замер на живой базе 29.08.2026 (13 записей), прямыми запросами:
     *
     *     '%мнем%'   -> 0 записей        '%Мнем%'   -> 1
     *     '%всегда%' -> 0                '%Всегда%' -> 1
     *     '%мой%'    -> 0                '%Мой%'    -> 1
     *
     * Пять пользовательских фраз из шести начинаются со значимого слова, то
     * есть с заглавной буквы, — и каждое такое слово выпадало из отбора.
     *
     * Наблюдённый промах целиком: на вопрос "Какое мнемоническое правило ты
     * знаешь?" слово "мнемоническое" не находилось, оставалось одно "правило"
     * из трёх слов, доля вопроса выходила 33% и запись отсеивалась по узости —
     * в память не уходило НИЧЕГО. С этим запросом доля становится 67% и нужная
     * запись приходит.
     *
     * Это ошибка в сторону пропуска, а она невосстановима: ни одна проверка
     * ниже по конвейеру не добудет запись, которой ей не подали, и молчание на
     * выходе неотличимо от честного "в памяти нет ответа".
     *
     * ЧЕГО ЭТОТ ЗАПРОС НЕ ЧИНИТ, сознательно. Он берёт ровно два варианта —
     * как передали и с заглавной первой буквой, — потому что заглавная в
     * середине предложения бывает почти только в начале слова. ВЕСЬ КАПС и
     * заглавные внутри слова остаются ненайденными. Общего решения в SQLite
     * нет: UPPER() и LOWER() там такие же ASCII-only, как и сам LIKE, поэтому
     * второй вариант обязан прийти готовым из Kotlin. Настоящая починка — это
     * отдельная колонка с приведённым содержимым и миграция схемы; заводить её
     * ради случая, который пока не наблюдался, преждевременно.
     *
     * Два LIKE вместо одного полный скан не удваивают: скан и так один, просто
     * на каждой строке проверяются два условия вместо одного.
     */
    @Query(
        "SELECT * FROM stickers WHERE reviewPending = 0 " +
            "AND (content LIKE '%' || :query || '%' " +
            "OR content LIKE '%' || :queryCapitalized || '%') " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun searchAnyCase(
        query: String,
        queryCapitalized: String,
        limit: Int
    ): List<Sticker>

    /**
     * Зеркало searchAnyCase: те же слова, но по записям В КАРАНТИНЕ.
     *
     * Зачем. Все три запроса пути чтения начинаются с `reviewPending = 0`, то
     * есть спорная запись для отбора не существует — не "нашлась и отброшена", а
     * не нашлась вовсе. На экране это неотличимо от честного "в памяти нет
     * ответа", а лечится противоположным: там двигают пороги, здесь разбирают
     * очередь. Этот запрос существует только чтобы назвать второй случай числом.
     *
     * ДУБЛИРОВАНИЕ, которое нельзя убрать. Пара условий LIKE здесь обязана
     * совпадать с searchAnyCase дословно, иначе два числа посчитают разные
     * множества и разойдутся молча. Свести их в одно место нечем: SQL не умеет
     * звать другой @Query. Держится это равенство ничем, кроме соседства —
     * подставной DAO не воспроизводит SQLite и такого расхождения не поймает
     * (см. границу в LadderSearchTest). Меняя регистровую починку в searchAnyCase
     * — например, добавляя вариант для ВСЕГО КАПСА, — правьте и здесь.
     *
     * ORDER BY стоит не ради порядка, а ради повторяемости: при обрезке лимитом
     * без него набор возвращаемых строк не определён, и один и тот же вопрос мог
     * бы давать разные числа. Прибор, показания которого дрожат сами по себе,
     * хуже грубого.
     *
     * Фильтра по слоям здесь нет намеренно — он живёт в Kotlin, теми же строками,
     * что и у видимого пути. См. KDoc у HiddenRow.
     */
    @Query(
        "SELECT id, layer FROM stickers WHERE reviewPending = 1 " +
            "AND (content LIKE '%' || :query || '%' " +
            "OR content LIKE '%' || :queryCapitalized || '%') " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun searchHiddenAnyCase(
        query: String,
        queryCapitalized: String,
        limit: Int
    ): List<HiddenRow>

    /**
     * Ранжированная выборка по набору слоёв — замена getAll()+сортировки в Kotlin.
     *
     * CASE воспроизводит ровно Importance.valueOf(importance).ordinal по убыванию
     * (LOW=0, MEDIUM=1, HIGH=2): сортировка по самой строке дала бы алфавитный
     * порядок HIGH/LOW/MEDIUM, то есть другой результат. Неизвестное значение
     * importance деградирует до 0, то есть до ранга LOW, а не роняет выборку
     * исключением — то же правило, что и в HourglassMemory.importanceRank.
     *
     * Равенство этой строки и enum ничем внутри файла не держится: SQL не знает
     * про Importance. Держит его снаружи ImportanceRankTest — он падает, если в
     * Importance добавить значение или переставить объявления. Меняя enum,
     * правьте и CASE: тест поймает изменение enum, но саму эту строку он не
     * читает и опечатку в ней не увидит.
     *
     * Чего здесь нет: отметки о том, что сработала ветка ELSE. Мусорное значение
     * важности неотличимо от честного LOW, и по этой выборке о нём не узнать.
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

    /**
     * Item 3: отметка о том, что запись оказалась уместной по запросу пользователя.
     *
     * Отдельно от touchAccess, и слить их нельзя, хотя соблазн есть. touchAccess
     * растёт у всего, что попало в выдачу, а выдачей распоряжается сортировка по
     * важности — то есть его рост частично объясняется тем, что запись уже стояла
     * наверху. Здесь считается другое: запись прошла отбор релевантности по словам
     * запроса, а этот отбор важность не читает. Слияние спрятало бы ровно ту ошибку,
     * ради которой счётчик и заводится, — бесполезную запись, закрепившуюся в топе.
     *
     * lastAccessedAt намеренно не трогается: давность обращения — это ось Prism,
     * и подмешивать в неё второй смысл значило бы сдвигать сроки жизни записей
     * побочным действием.
     *
     * Кто именно считается пользователем, решает не DAO, а вызывающий: сюда доходят
     * только те записи, про которые это уже решено.
     */
    @Query("UPDATE stickers SET userMatchCount = userMatchCount + 1 WHERE id = :id")
    suspend fun touchUserMatch(id: Long)

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

    // --- Наблюдение за счётчиком пользы (item 3).
    //
    // Три запроса нужны втроём и по отдельности мало что говорят. Общая сумма
    // отвечает на вопрос "механизм вообще хоть раз сработал" — без неё «правило
    // написано, но не применяется» неотличимо от «правило применяется, совпадений
    // нет». Число записей со счётом больше нуля вместе с суммой показывает, размазан
    // ли счёт по многим записям или собрался на одной. Максимум даёт верхнюю границу
    // разброса — без него не из чего назначать пороги, когда дойдёт до вывода
    // важности.
    //
    // Все три только читают и ничего не меняют.

    /** Сколько отметок пользы поставлено всего. Ноль здесь значит "не сработало ни разу". */
    @Query("SELECT COALESCE(SUM(userMatchCount), 0) FROM stickers")
    suspend fun sumUserMatches(): Int

    /** Сколько записей хоть раз оказались уместными. */
    @Query("SELECT COUNT(*) FROM stickers WHERE userMatchCount > 0")
    suspend fun countWithUserMatches(): Int

    /** Самая часто пригождавшаяся запись — верхняя граница разброса. */
    @Query("SELECT COALESCE(MAX(userMatchCount), 0) FROM stickers")
    suspend fun maxUserMatches(): Int

    // --- Разовый ремонт провенанса (2026-08-24).
    //
    // Строки, созданные ДО правки "дыры №4", помечены USER_STATED/OBSERVED,
    // хотя писал их сам агент: тогда saveEvent() вообще не принимал провенанс.
    // Правка кода прошлые строки не переписывает, поэтому они до сих пор лежат
    // в горячей памяти и читаются моделью как сказанное пользователем.
    //
    // Критерий по СОДЕРЖИМОМУ, а не по дате. Дата сработала бы только на этой
    // конкретной базе и промахнулась бы на записях, перенесённых с другого
    // устройства. Условие source = :oldSource делает запрос идемпотентным:
    // повторный запуск не найдёт ни одной строки и вернёт 0.
    //
    // Обе колонки меняются одним запросом: провенанс — это пара "кто сказал"
    // и "насколько уверенно", и переставить только половину значило бы
    // заменить одну неправду другой.
    //
    // Это сознательное исключение из правила "провенанс ставится один раз при
    // создании и не пересматривается" (см. Sticker.kt). Запрос существует
    // только ради разового ремонта и не должен вызываться из горячих путей.
    @Query(
        "UPDATE stickers SET source = :newSource, confidence = :newConfidence " +
            "WHERE source = :oldSource AND content LIKE :contentPrefix || '%'"
    )
    suspend fun reassignProvenanceByPrefix(
        contentPrefix: String,
        oldSource: String,
        newSource: String,
        newConfidence: String
    ): Int
}
