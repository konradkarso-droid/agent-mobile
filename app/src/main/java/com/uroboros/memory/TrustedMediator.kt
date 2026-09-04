package com.uroboros.memory

import android.content.Context

/**
 * Фасад памяти — единственная точка записи и чтения Sticker'ов для остального кода.
 *
 * Дыра №4 (аудит 2026-08-21): у saveEvent() не было параметров source/confidence,
 * хотя поля на Sticker существуют и подтверждены на устройстве (item 3, трек A).
 * Из-за этого ВСЁ, включая вакцина-строки, порождённые самим агентом, писалось со
 * значениями по умолчанию USER_STATED/OBSERVED — то есть агент помечал собственные
 * выводы как сказанное пользователем и наблюдённое. Это ровно обратное замыслу трека A.
 *
 * Решение: у source и confidence НЕТ значений по умолчанию. Каждый вызывающий обязан
 * назвать происхождение явно. Умолчание здесь было бы тихой подменой провенанса —
 * тот же класс ошибки, что и no-authority-via-text-claim, только на стороне записи.
 * Значения по умолчанию на самой сущности Sticker сознательно ОСТАВЛЕНЫ: они
 * описывают строки, созданные до миграции 6→7, и к новым записям отношения не имеют.
 *
 * Что НЕ трогается этой правкой (осознанно, см. разбор): dao.getAll() в горячем пути
 * getContext() и отсутствие транзакции при обновлении слоёв. Это вторая половина
 * дыры №4, она затрагивает уже оттестированную логику ранжирования и делается
 * отдельно, не в один заход с изменением контракта записи.
 */
class TrustedMediator(context: Context) {
    private val dao = MemoryDatabase.getInstance(context).stickerDao()
    private val hourglass = HourglassMemory(dao)
    private val snapshotDao = MemoryDatabase.getInstance(context).lastStableSnapshotDao()

    /**
     * Item 6, подшаг 1c (2026-08-22): канарейка ходит в DAO через фасад, а не напрямую
     * из UI — иначе L5 обращался бы в L0 через голову L2 (ARCHITECTURE.md §1).
     * MemoryCanary создаётся здесь один раз: он без состояния, хранит только ссылку
     * на dao, так что отдельный экземпляр на каждый вызов был бы мусором на ровном месте.
     */
    private val canary = MemoryCanary(dao)

    /**
     * Готовый текст снимка состояния памяти. ТОЛЬКО ЧТЕНИЕ — ни одной записи.
     *
     * Сознательно возвращается строка, а не MemorySnapshot: снимок "до" сейчас
     * никуда не сохраняется (это было бы либо новая таблица, либо стикер — а стикер
     * изменил бы то самое число total, которое канарейка и меряет). Пользователь
     * фиксирует "до" сам, до первого живого прогона sweep'а. Когда появится место
     * для хранения baseline, здесь добавится второй метод, возвращающий сам объект.
     */
    suspend fun memoryCanaryReport(): String = canary.format(canary.snapshot())

    /**
     * Item 6, ремонт данных (2026-08-22). Разовый вызов: возвращает в спектр
     * записи, застрявшие без expiryTime из-за прежнего храповика прогрева.
     * Проброс без логики — решение о том, что и куда чинить, принимает
     * HourglassMemory через Prism.classify, а не фасад.
     *
     * @return сколько записей было починено.
     */
    suspend fun repairStuckLayers(): Int = hourglass.repairStuckLayers()

    /**
     * Разовый ремонт провенанса (2026-08-24). Перемаркирует исторические
     * вакцина-строки, помеченные как сказанное пользователем, в вывод агента.
     * Проброс без логики — критерий и значения решает HourglassMemory.
     *
     * @return сколько записей перемаркировано.
     */
    suspend fun repairToteProvenance(): Int = hourglass.repairToteProvenance()

    /**
     * Сохраняет запись в память. source/confidence обязательны — см. KDoc класса.
     *
     * Ориентир для вызывающих:
     *  - ввод пользователя         -> USER_STATED  + OBSERVED
     *  - вывод/итог работы агента  -> AGENT_INFERRED + INFERRED
     *  - распознанный текст с фото -> OCR_EXTRACTED + UNCERTAIN
     */
    suspend fun saveEvent(
        content: String,
        source: SourceKind,
        confidence: ConfidenceLevel,
        tag: String = "general"
    ): Long {
        val sticker = Sticker(
            content = content,
            tag = tag,
            source = source.name,
            confidence = confidence.name
        )
        return hourglass.saveEvent(sticker)
    }

    /**
     * Достать записи под запрос. purpose обязателен и умолчания не имеет — по той же
     * причине, что source/confidence у saveEvent: умолчание молча приписало бы
     * обращению чужой смысл, только здесь на стороне чтения.
     *
     * Что от него зависит: засчитывается ли записям польза (item 3). Засчитывается
     * лишь ANSWERING_USER, и лишь тем, кто нашёлся по словам вопроса. Смысл значений
     * и почему ось именно "зачем", а не "кто спрашивает", — у RetrievalPurpose.
     *
     * Фасад решения не принимает: он не выбирает purpose за вызывающего и не
     * подставляет его по догадке. Назвать цель может только тот, кто знает, ради чего
     * спрашивает.
     */
    suspend fun getContextFor(
        purpose: RetrievalPurpose,
        query: String? = null,
        limit: Int = 10
    ): List<Sticker> {
        return hourglass.getContextFor(purpose, query, limit)
    }

    /**
     * То же самое плюс готовая строка о том, чем закончился отбор: сколько слов
     * искали, сколько записей нашлось, сколько прошло, сколько отсеяно и почему.
     *
     * Проброс без логики — состояния отбора и их формулировки решает
     * HourglassMemory, фасад не пересказывает их своими словами. Наружу идёт
     * готовый текст, а не сам тип: за пределами памяти он нужен только чтобы его
     * показать, и ветвиться по нему в UI было бы решением об отборе, принятым
     * мимо того, кто отбирает. Та же форма, что у memoryCanaryReport().
     */
    suspend fun getContextWithSummary(
        purpose: RetrievalPurpose,
        query: String? = null,
        limit: Int = 10
    ): ContextResult {
        return hourglass.getContextWithSummary(purpose, query, limit)
    }

    suspend fun totalStickers(): Int = dao.count()
    suspend fun getPendingReview(): List<Sticker> = dao.getPendingReview()
    suspend fun clearAllPendingReview(): Int = dao.clearAllReviewPending()

    /** Item 6b/8: перезаписывает единственный снимок последнего стабильного состояния. */
    suspend fun saveStableSnapshot(code: String) {
        snapshotDao.save(LastStableSnapshot(code = code))
    }

    /** Item 6b/8: читает снимок последнего стабильного состояния (null, если ещё не было). */
    suspend fun getStableSnapshot(): LastStableSnapshot? = snapshotDao.get()
}
