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
     * Решение: прогрев не поднимает выше ORANGE. В RED теперь можно попасть
     * только через classify() — то есть по явному признаку (tag == "identity"
     * или слово "принцип" в тексте), а не по частоте чтения. Это тот же
     * принцип, что уже проведён на стороне записи в TrustedMediator: статус
     * задаётся явно, а не выводится из поведения. Частота обращений не даёт
     * записи полномочий принципа.
     *
     * Чего это НЕ чинит: семь уже застрявших записей. У них expiryTime уже
     * null, и правка кода их не расколдует — нужен отдельный разовый ремонт
     * данных.
     */
    private val WARM_CEILING = Layer.ORANGE

    /**
     * Дыра №4, вторая половина (2026-08-21): какие слои вообще участвуют в выборке
     * для данного запроса. Вынесено отдельной функцией, чтобы отбор по слоям делался
     * в SQL (StickerDao.getRanked), а не полным сканом таблицы с последующей
     * фильтрацией в Kotlin.
     *
     * Логика повторяет прежнюю filter() один в один: горячие слои всегда, BLUE и
     * PURPLE — только по тем же ключевым словам. Пустой запрос = вся память
     * (прежний путь брал getAll() без ограничения по слоям).
     *
     * split()/filter() ниже оставлены нетронутыми: они чистые функции, могут быть
     * покрыты тестами и ещё пригодиться — просто больше не стоят в горячем пути.
     */
    fun layersFor(query: String?): List<String> {
        if (query.isNullOrBlank()) return LAYERS_ORDER.map { it.name }

        val layers = mutableListOf(Layer.RED, Layer.ORANGE, Layer.YELLOW, Layer.GREEN)
        val q = query.lowercase()
        if ("старое" in q || "прошлое" in q) {
            layers += Layer.BLUE
        }
        if ("архив" in q || "забытое" in q) {
            layers += Layer.PURPLE
        }
        return layers.map { it.name }
    }

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
     * Единственный путь в RED. Здесь признак явный — тег identity или слово
     * "принцип" в содержимом, — поэтому null-интервал для RED тут уместен:
     * принцип объявлен принципом, он и не должен остывать.
     */
    fun classify(sticker: Sticker): Pair<Layer, Long?> {
        val text = sticker.content.lowercase()
        if (sticker.tag == "identity" || "принцип" in text) {
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

    fun warmDebounce(layer: Layer): Long = WARM_DEBOUNCE_MS[layer] ?: 0L
}
