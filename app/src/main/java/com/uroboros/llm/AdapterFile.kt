package com.uroboros.llm

/**
 * Накладка (LoRA) к модели: как узнать её файл и какую строку показать о ней.
 *
 * Чистая логика, без Android: чтение файлов и вызов движка — в [LlmEngine].
 *
 * КАК УЗНАЁТСЯ ФАЙЛ. По заголовку, а не по имени. И модель, и накладка — файлы
 * GGUF с расширением `.gguf`; различает их ключ `general.type` в начале
 * заголовка: у модели `model`, у накладки `adapter` (так пишет их конвертер
 * llama.cpp). Имя файла человек волен назвать как угодно, и правило по имени
 * однажды молча промахнулось бы. Ключ `adapter.type` тоже признак накладки —
 * на случай файла, где `general.type` не записан.
 *
 * Заголовок, который не прочитался (не GGUF, обрыв, ключа нет в прочитанном
 * куске), — [Kind.UNKNOWN]. Он остаётся в списке моделей, как и было до
 * накладок, и накладкой не считается никогда: спрятать модель из списка или
 * подключить к модели чужой файл хуже, чем показать лишний файл в выборе.
 *
 * ЧЕГО НЕ УМЕЕТ. Не проверяет, под ту ли модель учена накладка: это делает
 * движок при подключении (архитектура и размеры, см. nativeSetAdapterFd в
 * gguf_lib.cpp), и отказ виден строкой [Outcome.Rejected]. Накладку от той же
 * модели, но учёную под другую стену, не отличит никто — это на человеке.
 */
object AdapterFile {

    /**
     * Сколько байт заголовка читать. Объявленное число, не подобранное: в
     * проверенных файлах (Qwen2.5-3B и накладка к ней) нужный ключ стоит вторым
     * и кончается до двухсотого байта; запас — на длинное имя модели перед ним.
     */
    const val HEADER_BYTES = 64 * 1024

    enum class Kind { ADAPTER, MODEL, UNKNOWN }

    /** Что за файл, по первым байтам заголовка GGUF. */
    fun kindOf(header: ByteArray): Kind {
        val r = Reader(header)
        return try {
            if (r.bytes(4).decodeToString() != "GGUF") return Kind.UNKNOWN
            r.u32() // версия формата
            r.u64() // число тензоров
            val kvCount = r.u64()
            var i = 0L
            while (i < kvCount) {
                val key = r.string()
                val type = r.u32().toInt()
                when {
                    key == "general.type" && type == TYPE_STRING ->
                        return if (r.string() == "adapter") Kind.ADAPTER else Kind.MODEL
                    key == "adapter.type" -> return Kind.ADAPTER
                    else -> r.skipValue(type)
                }
                i++
            }
            Kind.UNKNOWN
        } catch (e: IndexOutOfBoundsException) {
            // Кусок кончился раньше, чем нашёлся ключ.
            Kind.UNKNOWN
        } catch (e: IllegalStateException) {
            // Неизвестный тип значения — дальше заголовок не разобрать.
            Kind.UNKNOWN
        }
    }

    /** Какую накладку брать из найденных в папке. */
    sealed class Pick {
        object None : Pick()
        data class One(val index: Int) : Pick()
        data class Several(val count: Int) : Pick()
    }

    /**
     * Ровно одна накладка — она; больше одной — никакая. Выбирать из
     * нескольких по имени или дате значило бы угадывать, какую имел в виду
     * человек, а промах здесь тихий: ответы просто станут другими.
     */
    fun pick(kinds: List<Kind>): Pick {
        val found = kinds.indices.filter { kinds[it] == Kind.ADAPTER }
        return when (found.size) {
            0 -> Pick.None
            1 -> Pick.One(found[0])
            else -> Pick.Several(found.size)
        }
    }

    /** Чем кончилось при загрузке модели — для строки на экране и для отпечатка. */
    sealed class Outcome {
        /** Загрузка не по папке моделей: искать накладку негде. */
        object NoFolder : Outcome()
        object NotFound : Outcome()
        data class Several(val count: Int) : Outcome()
        data class NotOpened(val name: String) : Outcome()
        /** Движок файл не принял; модель работает голой. */
        data class Rejected(val name: String) : Outcome()
        data class On(val name: String, val size: Long) : Outcome()
    }

    /**
     * Итог по выбору и ответу движка.
     *
     * @param opened удалось ли открыть выбранный файл (для [Pick.One]).
     * @param engineState ответ движка после загрузки: 0 — накладки нет,
     *   1 — подключена, 2 — не принял (ADAPTER_* в gguf_lib.cpp).
     */
    fun outcome(pick: Pick, name: String?, size: Long, opened: Boolean, engineState: Int): Outcome =
        when (pick) {
            is Pick.None -> Outcome.NotFound
            is Pick.Several -> Outcome.Several(pick.count)
            is Pick.One -> {
                val n = name ?: "(без имени)"
                when {
                    !opened -> Outcome.NotOpened(n)
                    engineState == ENGINE_ON -> Outcome.On(n, size)
                    // Заказ был, а движок не подключил: не принял файл. Ноль
                    // здесь тоже отказ — значит, заказ до загрузки не дошёл.
                    else -> Outcome.Rejected(n)
                }
            }
        }

    /** Строка для экрана. Каждое «нет» называет причину. */
    fun line(outcome: Outcome): String = when (outcome) {
        is Outcome.NoFolder -> "Накладка: нет — модель загружена не из папки моделей"
        is Outcome.NotFound -> "Накладка: нет — в папке моделей её файла нет"
        is Outcome.Several -> "Накладка: нет — в папке моделей их ${outcome.count}, какую брать, не выбираю"
        is Outcome.NotOpened -> "Накладка: нет — файл ${outcome.name} не открылся"
        is Outcome.Rejected -> "Накладка: нет — движок не принял ${outcome.name} (не к этой модели или файл испорчен)"
        is Outcome.On -> "Накладка: ${outcome.name}"
    }

    /**
     * Доля накладки в отпечатке загрузки. Непуста только у подключённой:
     * состояние, обсчитанное с накладкой, для голой модели чужое, и наоборот.
     * Отказанная накладка модель не меняет — отпечаток тот же, что у голой.
     */
    fun printPart(outcome: Outcome): String =
        if (outcome is Outcome.On) "|lora=${outcome.name}:${outcome.size}" else ""

    private const val ENGINE_ON = 1

    // Типы значений GGUF.
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9

    /** Ширина значения фиксированного размера по типу; null — не фиксированный. */
    private fun fixedWidth(type: Int): Int? = when (type) {
        0, 1, 7 -> 1     // u8, i8, bool
        2, 3 -> 2        // u16, i16
        4, 5, 6 -> 4     // u32, i32, f32
        10, 11, 12 -> 8  // u64, i64, f64
        else -> null
    }

    /** Чтение little-endian по куску; за краем — IndexOutOfBoundsException. */
    private class Reader(private val b: ByteArray) {
        private var p = 0

        fun bytes(n: Long): ByteArray {
            if (n < 0 || n > b.size - p) throw IndexOutOfBoundsException()
            val out = b.copyOfRange(p, p + n.toInt())
            p += n.toInt()
            return out
        }

        fun u32(): Long {
            val x = bytes(4)
            return (0 until 4).fold(0L) { acc, i -> acc or ((x[i].toLong() and 0xFF) shl (8 * i)) }
        }

        fun u64(): Long {
            val x = bytes(8)
            return (0 until 8).fold(0L) { acc, i -> acc or ((x[i].toLong() and 0xFF) shl (8 * i)) }
        }

        fun string(): String = bytes(u64()).decodeToString()

        fun skipValue(type: Int) {
            when (type) {
                TYPE_STRING -> bytes(u64())
                TYPE_ARRAY -> {
                    val elem = u32().toInt()
                    val count = u64()
                    val w = fixedWidth(elem)
                    when {
                        w != null -> {
                            if (count < 0 || count > (b.size - p) / w) throw IndexOutOfBoundsException()
                            bytes(count * w)
                        }
                        elem == TYPE_STRING -> {
                            var i = 0L
                            while (i < count) { bytes(u64()); i++ }
                        }
                        else -> throw IllegalStateException("массив типа $elem")
                    }
                }
                else -> bytes((fixedWidth(type) ?: throw IllegalStateException("тип $type")).toLong())
            }
        }
    }
}
