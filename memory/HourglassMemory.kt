package com.uroboros.memory

import android.util.Log

/**
 * Дыра №4, вторая половина (аудит 2026-08-21). Что изменилось и почему:
 *
 * 1. migrateExpired() больше не сканирует всю таблицу — запрашивает только строки,
 *    у которых expiryTime реально истёк, и пишет точечным UPDATE по id.
 *
 * 2. getContext() больше не вызывает dao.getAll() (раньше — дважды за вызов).
 *    Отбор по слоям, отсев reviewPending, ранжирование и лимит выполняются в SQL
 *    (StickerDao.getRanked + Prism.layersFor). Результат тот же: слой-выборка
 *    ограничивается сверху тем же limit'ом, а запись, не вошедшая в топ-limit
 *    своей выборки, не могла бы войти и в итоговый топ-limit — её уже обгоняют
 *    limit других.
 *
 * 3. Обновления доступа/прогрева стали точечными по колонкам вместо @Update всей
 *    строки. Инкремент accessCount живёт в SQL-выражении. Раньше параллельные
 *    обращения перетирали друг другу счётчик и слой: прогрев мог молча откатиться.
 *
 * 4. Возвращаемые объекты собираются через copy() — сущность Sticker больше не
 *    мутируется на месте в бизнес-логике (см. ARCHITECTURE.md, §3.3).
 *
 * ЧЕГО ЗДЕСЬ НЕТ, сознательно: обёртки withTransaction. Точечные UPDATE по
 * колонкам уже снимают класс "потерянного обновления" ради которого транзакция
 * и заводилась бы; остаётся лишь неатомарность связки "прочитали строку — решили
 * прогревать". Это эвристика с дебаунсом, устаревшее на миллисекунды чтение на
 * неё не влияет, а транзакция потребовала бы протащить сам объект базы через
 * HourglassMemory и TrustedMediator — расширение поверхности правки ради
 * несущественного выигрыша.
 */
class HourglassMemory(private val dao: StickerDao) {

    private val HOT_LAYERS = listOf(Layer.RED.name, Layer.ORANGE.name, Layer.YELLOW.name, Layer.GREEN.name)

    /** Безопасный порядок важности: неизвестное значение не роняет выборку. */
    private fun importanceRank(raw: String): Int =
        runCatching { Importance.valueOf(raw).ordinal }.getOrDefault(Importance.MEDIUM.ordinal)

    private fun rankOrder(): Comparator<Sticker> =
        compareByDescending<Sticker> { importanceRank(it.importance) }
            .thenByDescending { it.createdAt }

    suspend fun migrateExpired() {
        val now = System.currentTimeMillis()
        for (sticker in dao.getExpired(now)) {
            val newLayer = Prism.colderLayer(Layer.valueOf(sticker.layer))
            val newExpiry = Prism.newInterval(newLayer)?.let { now + it }
            dao.updateLayer(sticker.id, newLayer.name, newExpiry)
        }
    }

    /**
     * Разовый ремонт данных (2026-08-22). Чинит записи, застрявшие ВНЕ спектра
     * из-за прежнего храповика прогрева: они доехали до RED по обращениям,
     * получили expiryTime = null и стали невидимы для migrateExpired навсегда.
     *
     * Потолок прогрева в Prism закрыл путь на будущее, но уже застрявшие строки
     * правкой кода не расколдовываются — отсюда эта функция.
     *
     * Куда возвращать, решает Prism.classify — тот же код, что и при создании
     * записи. Отдельного SQL-условия сознательно нет: оно повторяло бы правила
     * классификации наизнанку и разошлось бы с ними при первом же изменении.
     *
     * Две категории пропускаются, и обе — законно без срока:
     *  - PURPLE: конец спектра, холоднее некуда, следующего срока не бывает;
     *  - настоящие принципы: classify вернул RED с null-интервалом, значит
     *    признак явный (tag identity / слово "принцип"), и трогать их нельзя.
     *
     * Функция идемпотентна: повторный запуск на уже починенной базе не найдёт
     * ни одной строки без срока, кроме законных, и вернёт 0.
     *
     * dao.getAll() здесь допустим в отличие от горячего пути: операция разовая,
     * при старте, вне цикла чтения контекста.
     *
     * @return сколько записей возвращено в спектр.
     */
    suspend fun repairStuckLayers(): Int {
        val now = System.currentTimeMillis()
        var repaired = 0
        for (sticker in dao.getAll()) {
            if (sticker.expiryTime != null) continue
            if (sticker.layer == Layer.PURPLE.name) continue

            val (layer, interval) = Prism.classify(sticker)
            if (interval == null) continue

            dao.updateLayer(sticker.id, layer.name, now + interval)
            repaired++
        }
        if (repaired > 0) {
            Log.d("HourglassMemory", "repairStuckLayers: вернул в спектр $repaired записей")
        }
        return repaired
    }

    suspend fun getContext(query: String?, limit: Int): List<Sticker> {
        migrateExpired()

        val layers = Prism.layersFor(query)

        if (query.isNullOrBlank()) {
            // Прежнее поведение сохранено: пустой запрос отдаёт срез памяти по рангу
            // и НЕ считается обращением — ни счётчик, ни прогрев не трогаются.
            return dao.getRanked(layers, limit)
        }

        val layerPicks = dao.getRanked(layers, limit)
        val textMatches = dao.search(query, limit)
        val result = (layerPicks + textMatches)
            .distinctBy { it.id }
            .sortedWith(rankOrder())
            .take(limit)

        val now = System.currentTimeMillis()
        return result.map { sticker ->
            val timeSinceLastAccess = now - sticker.lastAccessedAt
            val isFirstAccess = sticker.accessCount == 0

            val currentLayer = Layer.valueOf(sticker.layer)
            val debounce = Prism.warmDebounce(currentLayer)
            val warmer = Prism.warmerLayer(currentLayer)
            val shouldWarm = (isFirstAccess || timeSinceLastAccess >= debounce) && warmer != currentLayer

            dao.touchAccess(sticker.id, now)

            if (shouldWarm) {
                val newExpiry = Prism.newInterval(warmer)?.let { now + it }
                dao.updateLayer(sticker.id, warmer.name, newExpiry)
                sticker.copy(
                    accessCount = sticker.accessCount + 1,
                    lastAccessedAt = now,
                    layer = warmer.name,
                    expiryTime = newExpiry
                )
            } else {
                sticker.copy(
                    accessCount = sticker.accessCount + 1,
                    lastAccessedAt = now
                )
            }
        }
    }

    suspend fun saveEvent(sticker: Sticker): Long {
        val (layer, interval) = Prism.classify(sticker)
        sticker.layer = layer.name
        sticker.expiryTime = interval?.let { System.currentTimeMillis() + it }
        val newId = dao.insert(sticker)

        // RiskTrigger — настоящий жёсткий чекпоинт (ставит reviewPending), но его
        // сбой не должен каскадом ронять сам факт сохранения. Здесь @Update всей
        // строки допустим: строка только что вставлена, параллельных читателей ещё нет.
        try {
            val hotPool = dao.getByTagInLayers(sticker.tag, HOT_LAYERS).filter { it.id != newId }
            val saved = sticker.copy(id = newId)
            val decision = RiskTrigger.evaluate(saved, hotPool)
            Log.d(
                "RiskTrigger",
                "sticker id=$newId tag=${sticker.tag} shouldReview=${decision.shouldReview} reasons=${decision.reasons}"
            )
            if (decision.shouldReview) {
                saved.reviewPending = true
                dao.update(saved)
            }
        } catch (e: Exception) {
            Log.e("RiskTrigger", "evaluation failed for sticker id=$newId, save not blocked", e)
        }

        return newId
    }
}
