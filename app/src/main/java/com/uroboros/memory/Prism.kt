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

    fun warmerLayer(current: Layer): Layer {
        val idx = LAYERS_ORDER.indexOf(current)
        return if (idx > 0) LAYERS_ORDER[idx - 1] else Layer.RED
    }

    fun newInterval(layer: Layer): Long? = LAYER_INTERVALS_MS[layer]

    fun warmDebounce(layer: Layer): Long = WARM_DEBOUNCE_MS[layer] ?: 0L
}
