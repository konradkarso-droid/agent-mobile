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

    /**
     * Разовый ремонт провенанса (2026-08-24). Строки, порождённые самим агентом
     * до правки "дыры №4", лежат в базе с source = USER_STATED: тогда saveEvent()
     * провенанс не принимал вообще, и вывод агента о собственной работе
     * записывался как сказанное пользователем.
     *
     * Правка кода прошлые строки не переписывает — они до сих пор попадают в
     * getContext() и читаются моделью как показания человека. Наблюдалось живьём:
     * на вопрос пользователя модель ответила "функцию sumPositive ВЫ запустили
     * 6 раз", пересказав ему его же словами отчёт агента.
     *
     * Отбор идёт по СОДЕРЖИМОМУ, а не по дате: дата сработала бы только на этой
     * конкретной базе и промахнулась бы на записях, перенесённых с другого
     * устройства. Условие source = USER_STATED в самом запросе делает операцию
     * идемпотентной — повторный вызов не найдёт ничего и вернёт 0.
     *
     * Это сознательное исключение из правила "провенанс ставится один раз при
     * создании и не пересматривается" (Sticker.kt). Исключение разовое и
     * ограничено историческими записями; ничего в текущем пути записи оно
     * не меняет.
     *
     * @return сколько записей перемаркировано.
     */
    suspend fun repairToteProvenance(): Int {
        val fixed = dao.reassignProvenanceByPrefix(
            contentPrefix = HISTORICAL_TOTE_PREFIX,
            oldSource = SourceKind.USER_STATED.name,
            newSource = SourceKind.AGENT_INFERRED.name,
            newConfidence = ConfidenceLevel.INFERRED.name
        )
        if (fixed > 0) {
            Log.d("HourglassMemory", "repairToteProvenance: перемаркировано $fixed записей")
        }
        return fixed
    }

    suspend fun getContext(query: String?, limit: Int): List<Sticker> {
        migrateExpired()

        val layers = Prism.layersFor(query)

        if (query.isNullOrBlank()) {
            // Прежнее поведение сохранено: пустой запрос отдаёт срез памяти по рангу
            // и НЕ считается обращением — ни счётчик, ни прогрев не трогаются.
            return dao.getRanked(layers, limit)
        }

        // Два канала, намеренно НЕ сливаемые в один рейтинг (разделённая
        // непрерывность). Каждый отвечает за своё, и свежесть одного не может
        // вытеснить уместность другого.
        val principles = dao.getRanked(listOf(Layer.RED.name), PRINCIPLE_LIMIT)

        val roomLeft = (limit - principles.size).coerceAtLeast(0)
        val matches = if (roomLeft == 0) emptyList() else searchByWords(query, roomLeft, layers)

        val result = (principles + matches).distinctBy { it.id }.take(limit)

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

    /**
     * Переменный канал: записи, действительно связанные с ВОПРОСОМ.
     *
     * Зачем понадобилось (26.08.2026). Прежний dao.search(query, limit) искал
     * весь вопрос ЦЕЛИКОМ как подстроку содержимого — `content LIKE '%<весь
     * вопрос>%'`. Совпадение при таком условии практически невозможно, так что
     * текстовый канал молча не находил ничего никогда, а весь блок памяти
     * набивался соседней выборкой "топ по рангу", одинаковой для любого
     * вопроса. Наблюдалось живьём: на "Сколько будет семью восемь?" и на
     * "Какой длины должно быть полотенце?" подтягивались одни и те же пять
     * записей на 628 знаков, и модель отвечала "в семье восемь детей" —
     * добросовестно связывая вопрос с посторонним текстом, который ей вручили.
     *
     * Что делаем: ищем по отдельным значимым словам вопроса. Запросов
     * получается несколько, но это обращения к локальной базе — миллисекунды;
     * платим мы не за поиск, а за то, что попадёт в запрос к модели.
     *
     * Слои. dao.search не ограничен слоями вообще и заглянул бы в архив, чего
     * замысел Призмы не допускает без явной просьбы. Поэтому найденное
     * фильтруется по тем же слоям, что вернул Prism.layersFor — BLUE и PURPLE
     * попадают в список, только если в вопросе есть слова вроде "архив" или
     * "старое". RED отсеивается: принципы уже пришли постоянным каналом, и
     * второй раз занимать ими место незачем.
     *
     * Пустой результат — нормальный исход, а не сбой. Лучше "не знаю", чем
     * уверенная чушь из чужой записи.
     */
    private suspend fun searchByWords(
        query: String,
        limit: Int,
        allowedLayers: List<String>
    ): List<Sticker> {
        val words = meaningfulWords(query)
        if (words.isEmpty()) return emptyList()

        val found = mutableListOf<Sticker>()
        for (word in words) {
            found += dao.search(word, limit)
        }
        return found
            .distinctBy { it.id }
            .filter { it.layer != Layer.RED.name && it.layer in allowedLayers }
            .sortedWith(rankOrder())
            .take(limit)
    }

    /**
     * Слова вопроса, по которым имеет смысл искать.
     *
     * Три отсева, каждый по своей причине:
     *  - короткие слова: предлоги и частицы, совпадут с чем угодно;
     *  - список служебных слов: "сколько", "должно", "будет" — длинные, но
     *    встречаются в любом вопросе и тащат за собой случайные записи. Список
     *    заведомо неполон и пополняется по мере того, как мусор себя покажет;
     *  - потолок числа слов: длинный вопрос иначе даёт десятки обращений к базе
     *    и вычерпывает всю память подряд, возвращая нас к тому же результату.
     *
     * Окончание у длинных слов отбрасывается: ищем "полотенц" вместо
     * "полотенце", иначе "полотенцем" в записи не совпадёт. Отрезаем только у
     * слов от семи букв — у коротких обрубок стал бы совпадать со всем подряд.
     * Это грубая замена морфологии, а не морфология: настоящее разделение по
     * смыслу потребовало бы второй модели в памяти, чего устройство не
     * выдержит.
     */
    private fun meaningfulWords(query: String): List<String> =
        query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= MIN_WORD_LENGTH && it !in STOP_WORDS }
            .map { if (it.length >= 7) it.dropLast(2) else it }
            .distinct()
            .take(MAX_SEARCH_WORDS)

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

    private companion object {
        /**
         * Префикс вакцина-строк в том виде, в каком их писала прежняя версия кода.
         *
         * Литерал здесь СОЗНАТЕЛЬНО продублирован, а не взят из KotlinCodingTask:
         * ремонт работает с историческими строками, уже лежащими в базе, и не
         * должен меняться вслед за тем, как задача формирует новые записи.
         * Общая константа связала бы прошлое с будущим и при первой же правке
         * формулировки молча перестала бы находить старьё.
         */
        const val HISTORICAL_TOTE_PREFIX = "[TOTE]"

        /**
         * Сколько принципов постоянный канал кладёт в контекст.
         *
         * Потолок, а не "сколько есть": RED пополняется только явным признаком
         * (Prism.classify — тег identity или слово "принцип"), но со временем
         * записей там станет больше, чем помещается в бюджет запроса. Предел
         * поставлен заранее, пока в него ничего не упирается: это граница, а не
         * догадка о поведении, и выводить её снизу нечему.
         *
         * Когда RED перерастёт этот предел, отбор ВНУТРИ принципов придётся
         * задать явно — сейчас берутся первые по рангу, то есть по важности и
         * свежести, и это временное решение, а не осмысленный выбор.
         */
        const val PRINCIPLE_LIMIT = 3

        /** Короче этого слово совпадёт с чем угодно. */
        const val MIN_WORD_LENGTH = 4

        /** Потолок числа слов, по которым идёт поиск. */
        const val MAX_SEARCH_WORDS = 6

        /**
         * Слова, которые есть почти в любом вопросе. Длинные, поэтому предыдущий
         * отсев их не ловит, а тащат они за собой что попало.
         */
        val STOP_WORDS = setOf(
            "что", "чтобы", "если", "или", "как", "какой", "какая", "какое", "какие",
            "когда", "где", "куда", "почему", "зачем", "сколько",
            "быть", "было", "были", "будет", "будут", "есть",
            "должно", "должен", "должна", "должны", "можно", "нужно", "надо",
            "это", "этот", "эта", "эти", "тот", "там", "тут",
            "для", "при", "про", "над", "под", "без", "перед", "после",
            "меня", "тебя", "него", "неё", "нему", "мной", "тобой",
            "очень", "просто", "только", "ещё", "уже", "тоже", "также",
            "твой", "твоя", "свой", "своя", "мой", "моя"
        )
    }
}
