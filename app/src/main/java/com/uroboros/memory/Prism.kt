package com.uroboros.memory

object Prism {
    private val LAYER_INTERVALS_MS: Map<Layer, Long?> = mapOf(
        Layer.RED to null,
        Layer.ORANGE to 24L * 60 * 60 * 1000,
        Layer.YELLOW to 7L * 24 * 60 * 60 * 1000,
        Layer.GREEN to 30L * 24 * 60 * 60 * 1000,
        Layer.BLUE to 365L * 24 * 60 * 60 * 1000,
        Layer.PURPLE to null
    )

    private val WARM_DEBOUNCE_MS: Map<Layer, Long> = mapOf(
        Layer.RED to 0L,
        Layer.ORANGE to 60_000L,
        Layer.YELLOW to 120_000L,
        Layer.GREEN to 180_000L,
        Layer.BLUE to 60_000L,
        Layer.PURPLE to 0L
    )

    private val LAYERS_ORDER = listOf(
        Layer.RED, Layer.ORANGE, Layer.YELLOW, Layer.GREEN, Layer.BLUE, Layer.PURPLE
    )

    /**
     * Потолок прогрева (2026-08-22, по результатам первого снимка канарейки).
     *
     * Что нашли: все 7 записей в RED оказались обычными строками ("Меня зовут
     * Тест", "Трава зелёного цвета", куски кода из TOTE-цикла) с накрученными
     * счётчиками обращений — 4, 6, 8. Ни одного принципа, ни одного identity.
     * Они доехали до RED прогревом: GREEN → YELLOW → ORANGE → RED, по ступени
     * за обращение.
     *
     * Почему это тупик, а не просто неточность: у RED интервал null (и это
     * правильно — принципы не должны остывать), а остывание работает ТОЛЬКО
     * через migrateExpired, который отбирает строки по expiryTime <= now.
     * Запись без срока в отбор не попадает никогда. Прогрев — ступень за
     * обращение (секунды), остывание — ступень за интервал (часы и дни).
     * Асимметрия плюс отсутствие выхода наверху = храповик: любая читаемая
     * запись рано или поздно застревает в слое принципов навсегда.
     *
     * Решение: прогрев не поднимает выше ORANGE. В RED можно попасть только
     * через classify() — то есть по явной метке, а не по частоте чтения. Это
     * тот же принцип, что уже проведён на стороне записи в TrustedMediator:
     * статус задаётся явно, а не выводится из поведения. Запись читают часто
     * потому, что она важна; важной её частота чтения не делает.
     *
     * Чего это НЕ чинит: семь уже застрявших записей. У них expiryTime уже
     * null, и правка кода их не расколдует — нужен отдельный разовый ремонт
     * данных.
     */
    private val WARM_CEILING = Layer.ORANGE

    /** Метка записи об идентичности — единственный вход в RED, см. [classify]. */
    const val IDENTITY_TAG = "identity"

    /**
     * Все слои — срез памяти по рангу при пустом запросе (просмотр без слов).
     *
     * Раньше здесь стояла функция, выбиравшая слои по словам запроса: BLUE и
     * PURPLE открывались словами вроде «старое» или «архив». Теперь слои для
     * поиска по словам задают окна круга (HourglassMemory, DolmenCircle), и
     * холодное окно смотрит всегда, без волшебных слов. Единственным
     * читателем остался пустой запрос, которому нужна вся память, — поэтому
     * функция стала списком.
     *
     * split()/filter() ниже оставлены нетронутыми: они чистые функции, могут быть
     * покрыты тестами и ещё пригодиться — просто больше не стоят в горячем пути.
     */
    val ALL_LAYERS: List<String> = LAYERS_ORDER.map { it.name }

    fun split(stickers: List<Sticker>): Map<Layer, List<Sticker>> =
        LAYERS_ORDER.associateWith { layer -> stickers.filter { it.layer == layer.name } }

    fun filter(spectrum: Map<Layer, List<Sticker>>, query: String): List<Sticker> {
        val result = mutableListOf<Sticker>()
        result += spectrum[Layer.RED].orEmpty()
        result += spectrum[Layer.ORANGE].orEmpty()
        result += spectrum[Layer.YELLOW].orEmpty()
        result += spectrum[Layer.GREEN].orEmpty()
        val q = query.lowercase()
        if ("старое" in q || "прошлое" in q) {
            result += spectrum[Layer.BLUE].orEmpty()
        }
        if ("архив" in q || "забытое" in q) {
            result += spectrum[Layer.PURPLE].orEmpty()
        }
        return result
    }

    /**
     * Единственный путь в RED: запись с меткой [IDENTITY_TAG].
     *
     * ЧТО ЛЕЖИТ В RED. То, из чего агент состоит: вечные факты об
     * идентичности — имя, нажитое о себе. Не правила работы: правила живут в
     * системной стене (BibleSoftWall), которая стоит первой в каждом запросе
     * и подписана как правила. Запись из RED сегодня в ответ не идёт вовсе:
     * ни одно окно отбора красный не ищет (см. HourglassMemory.searchByWords),
     * и к модели он приходит только через стену (LlmEngine.wallFor) — и только
     * принятый человеком, не больше трёх строк (dream.SelfLine).
     *
     * ПОЧЕМУ ТОЛЬКО МЕТКА, А НЕ СЛОВО В ТЕКСТЕ. У RED нет срока, а остывание
     * отбирает записи по expiryTime, — попавшая сюда запись не уйдёт вниз
     * никогда. Сделать запись вечной вправе только тот, кто ставит метку
     * нарочно. Угадывать это по словам нельзя: сюда приходит любая сохранённая
     * речь, включая вопросы, и вопрос «какие у тебя принципы?» по слову
     * «принципы» стал бы вечной записью. Поэтому текст записи на вход в RED не
     * влияет вовсе — проверки на молчание в PrismLayerPathTest это закрепляют.
     *
     * ЧЕГО НЕ УМЕЕТ. Метку несёт вызывающий, и здесь не проверяется, вправе
     * ли он её ставить: кто передал [IDENTITY_TAG], тот и попал в RED.
     *
     * Ветка ORANGE ("текущая", "задача") намеренно осталась подстрокой: у
     * ORANGE есть срок в сутки, и ложное попадание туда рассасывается само.
     * Сужать имеет смысл только вход в слой без срока.
     *
     * Записи, уже лежащие в RED без срока, этим не чинятся: у них expiryTime
     * уже null, и вернуть их в спектр может только разовый ремонт данных —
     * HourglassMemory.repairStuckLayers.
     */
    fun classify(sticker: Sticker): Pair<Layer, Long?> {
        val text = sticker.content.lowercase()
        if (sticker.tag == IDENTITY_TAG) {
            return Layer.RED to LAYER_INTERVALS_MS[Layer.RED]
        }
        if ("текущая" in text || "задача" in text) {
            return Layer.ORANGE to LAYER_INTERVALS_MS[Layer.ORANGE]
        }
        return when (sticker.importance) {
            Importance.HIGH.name -> Layer.YELLOW to LAYER_INTERVALS_MS[Layer.YELLOW]
            Importance.LOW.name -> Layer.BLUE to LAYER_INTERVALS_MS[Layer.BLUE]
            else -> Layer.GREEN to LAYER_INTERVALS_MS[Layer.GREEN]
        }
    }

    fun colderLayer(current: Layer): Layer {
        val idx = LAYERS_ORDER.indexOf(current)
        return if (idx < LAYERS_ORDER.size - 1) LAYERS_ORDER[idx + 1] else Layer.PURPLE
    }

    /**
     * Слой на ступень теплее — но не выше WARM_CEILING (см. его KDoc).
     *
     * Возврат того же слоя означает "прогревать некуда": вызывающий
     * (HourglassMemory.getContext) проверяет warmer != currentLayer и в этом
     * случае не трогает ни слой, ни expiryTime. То есть запись на потолке
     * продолжает жить со своим сроком и нормально остывает — застрять,
     * как раньше в RED, она не может.
     */
    fun warmerLayer(current: Layer): Layer {
        val idx = LAYERS_ORDER.indexOf(current)
        if (idx <= 0) return Layer.RED
        val ceilingIdx = LAYERS_ORDER.indexOf(WARM_CEILING)
        return if (idx - 1 < ceilingIdx) current else LAYERS_ORDER[idx - 1]
    }

    fun newInterval(layer: Layer): Long? = LAYER_INTERVALS_MS[layer]

    /**
     * Куда запись должна была доехать к моменту [now], если бы остывание шло
     * непрерывно, а не только тогда, когда его позвали.
     *
     * Возвращает слой и новый срок. Запись, чей срок ещё не наступил (или срока
     * нет вовсе — RED и PURPLE), возвращается как есть. Наступивший срок
     * считается истёкшим: граница `<=` та же, что в StickerDao.getExpired, иначе
     * запись на самой границе отбиралась бы и не двигалась.
     *
     * ПОЧЕМУ СРОК СЧИТАЕТСЯ ОТ СТАРОГО СРОКА, А НЕ ОТ [now]. Срок записи — это
     * момент выхода из слоя. Если новый срок отсчитывать от момента вызова, каждая
     * пауза в вызовах прибавляется к жизни записи насовсем: записи, пролежавшей
     * месяц без обращений, досталась бы одна ступень и полный новый интервал, как
     * будто месяца не было. Отсчёт от старого срока делает результат независимым
     * от того, как часто и когда вызывали: один вызов через 40 дней даёт то же,
     * что ежечасные вызовы все 40 дней. На этом и держится то, что остывание можно
     * звать откуда угодно и сколько угодно раз.
     *
     * Цикл конечен: каждый шаг сдвигает срок вперёд на положительный интервал, а
     * конец спектра — слой без срока. Больше пяти шагов не бывает.
     *
     * Чего функция не делает: не прогревает (прогрев идёт по обращению, в
     * HourglassMemory, и сюда не сливается) и не смотрит на запись дальше слоя и
     * срока — содержимое, авторство и бит спора ей не передаются вовсе.
     */
    fun catchUp(layer: Layer, expiryTime: Long?, now: Long): Pair<Layer, Long?> {
        var currentLayer = layer
        var expiry = expiryTime
        while (true) {
            val exitAt = expiry ?: break
            if (exitAt > now) break
            currentLayer = colderLayer(currentLayer)
            expiry = newInterval(currentLayer)?.let { exitAt + it }
        }
        return currentLayer to expiry
    }

    fun warmDebounce(layer: Layer): Long = WARM_DEBOUNCE_MS[layer] ?: 0L
}
