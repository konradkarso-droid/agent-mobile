package com.uroboros.memory.nav

import java.io.DataInputStream
import java.io.InputStream

/**
 * Векторы начальных форм для прибора «смысл» ([Meaning]): начальная форма →
 * 300 чисел, расположение слова среди других по смыслу. Вектор слова сам по
 * себе ничего не значит; значит близость двух векторов (у «клинок» и «меч»
 * она высокая, у «клинок» и «выходной» — низкая).
 *
 * Файл — `app/src/main/assets/meaning_vectors.bin`, собран из векторов Navec
 * (источник, лицензия и пересборка — `meaning_vectors_SOURCE.md` рядом с ним).
 *
 * ФОРМАТ (числа — старший байт вперёд):
 *  - 4 байта `UMV1`;
 *  - шесть int: слов N, длина вектора DIM, кусков M, образцов на кусок K,
 *    чисел в куске SUB (DIM = M·SUB), шаг полной записи словаря R;
 *  - образцы: M·K·SUB float — кусок j, образец c, число s лежат на месте
 *    (j·K + c)·SUB + s;
 *  - словарь: N записей в порядке возрастания байтов UTF-8; запись — байт
 *    «сколько байтов общих с предыдущей» (у каждой R-й — 0, она полная),
 *    байт длины хвоста, хвост;
 *  - коды: N·M байт — у слова i кусок j собран из образца с номером
 *    байт[i·M + j].
 *
 * ЗАЧЕМ ТАК, А НЕ ЧИСЛА ПОДРЯД. Вектор собирается из M кусков, каждый кусок —
 * один из K готовых образцов: на слово уходит M байт вместо DIM чисел. Слова
 * словаря хранят общее начало с соседом один раз — как основа и окончание у
 * словаря начальных форм. Векторы форм слова не хранятся вовсе: форму к
 * начальной приводит словарь начальных форм ([LemmaDictionary]).
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - слова не из файла (редкие, новые, латиница) вектора не имеют — [vector]
 *    отдаёт null, и это «не знаю слова», а не «далеко по смыслу»;
 *  - близость векторов — не синонимия: близки и противоположности
 *    («горячий» — «холодный»), и соседи по роду («понедельник» — «суббота»).
 */
class MeaningVectors private constructor(
    val size: Int,
    val dim: Int,
    private val m: Int,
    private val k: Int,
    private val sub: Int,
    private val restart: Int,
    private val codebook: FloatArray,
    private val vocab: ByteArray,
    /** Начало каждой R-й (полной) записи словаря в [vocab]. */
    private val restartAt: IntArray,
    private val codes: ByteArray,
) {
    /** Сколько байт занимают массивы файла в памяти — для строки прибора. */
    val arrayBytes: Long get() = codebook.size * 4L + vocab.size + restartAt.size * 4L + codes.size

    /** Номер начальной формы [lemma] в файле или -1 — слова нет. */
    fun indexOf(lemma: String): Int {
        val key = lemma.toByteArray(Charsets.UTF_8)
        // Последняя полная запись, которая не больше ключа.
        var lo = 0
        var hi = restartAt.size - 1
        var block = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val at = restartAt[mid]
            val c = compare(vocab, at + 2, vocab[at + 1].toInt() and 0xFF, key)
            if (c <= 0) { block = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (block < 0) return -1
        var pos = restartAt[block]
        var word = ByteArray(0)
        val first = block * restart
        val last = minOf(size, first + restart)
        for (i in first until last) {
            val shared = vocab[pos].toInt() and 0xFF
            val tail = vocab[pos + 1].toInt() and 0xFF
            word = word.copyOf(shared) + vocab.copyOfRange(pos + 2, pos + 2 + tail)
            pos += 2 + tail
            val c = compare(word, 0, word.size, key)
            if (c == 0) return i
            if (c > 0) return -1
        }
        return -1
    }

    /** Вектор начальной формы [lemma], собранный из кусков; null — слова в файле нет. */
    fun vector(lemma: String): FloatArray? {
        val i = indexOf(lemma)
        if (i < 0) return null
        val out = FloatArray(dim)
        for (j in 0 until m) {
            val c = codes[i * m + j].toInt() and 0xFF
            val from = (j * k + c) * sub
            for (s in 0 until sub) out[j * sub + s] = codebook[from + s]
        }
        return out
    }

    companion object {
        private val MAGIC = "UMV1".toByteArray(Charsets.US_ASCII)

        /** Байты [a] с [from] длиной [len] против [key] — без знака, как сортировал сборщик. */
        private fun compare(a: ByteArray, from: Int, len: Int, key: ByteArray): Int {
            val n = minOf(len, key.size)
            for (i in 0 until n) {
                val x = a[from + i].toInt() and 0xFF
                val y = key[i].toInt() and 0xFF
                if (x != y) return x - y
            }
            return len - key.size
        }

        /** Читает файл целиком; не по формату — исключение с причиной. */
        fun read(input: InputStream): MeaningVectors {
            val d = DataInputStream(input.buffered())
            val magic = ByteArray(4)
            d.readFully(magic)
            check(magic.contentEquals(MAGIC)) { "не файл векторов" }
            val n = d.readInt(); val dim = d.readInt(); val m = d.readInt()
            val k = d.readInt(); val sub = d.readInt(); val r = d.readInt()
            check(n > 0 && m > 0 && k in 1..256 && sub > 0 && m * sub == dim && r > 0) { "заголовок не сходится" }
            val codebook = FloatArray(m * k * sub) { d.readFloat() }
            val vocab = java.io.ByteArrayOutputStream()
            val restartAt = IntArray((n + r - 1) / r)
            for (i in 0 until n) {
                if (i % r == 0) restartAt[i / r] = vocab.size()
                val shared = d.readUnsignedByte()
                val tail = d.readUnsignedByte()
                check(i % r != 0 || shared == 0) { "полная запись словаря с общим началом" }
                vocab.write(shared); vocab.write(tail)
                val bytes = ByteArray(tail)
                d.readFully(bytes)
                vocab.write(bytes)
            }
            val codes = ByteArray(n * m)
            d.readFully(codes)
            check(d.read() == -1) { "лишние байты в конце файла" }
            return MeaningVectors(n, dim, m, k, sub, r, codebook, vocab.toByteArray(), restartAt, codes)
        }
    }
}

/**
 * Векторы смысла на процесс: читаются один раз, не в главном потоке. Пока не
 * загружены или не загрузились, прибор «смысл» говорит об этом строкой, а
 * отбор и ответ идут как без него — прибор ни на что не влияет.
 */
object MeaningHolder {

    /** Файл векторов в assets приложения. Одно место имени для всех, кто грузит. */
    const val ASSET = "meaning_vectors.bin"

    sealed class State {
        object Loading : State()
        data class Failed(val reason: String) : State()
        data class Ready(val vectors: MeaningVectors, val millis: Long) : State()
    }

    @Volatile
    var state: State = State.Loading
        private set

    private var started = false

    /** Загруженные векторы; не загружены — null. */
    val loaded: MeaningVectors? get() = (state as? State.Ready)?.vectors

    /** Строка «Подробно» о самом файле: сколько слов, за сколько прочитан, сколько места. */
    fun meterLine(): String = when (val s = state) {
        is State.Ready -> "Векторы смысла: слов ${s.vectors.size}, загружены за ${s.millis} мс · " +
            "массивы ${"%.1f".format(s.vectors.arrayBytes / 1_048_576.0)} МБ · потребитель: прибор «смысл»"
        State.Loading -> "Векторы смысла: загружаются"
        is State.Failed -> "Векторы смысла: не загрузились (${s.reason})"
    }

    /** Читает файл из [open]. Повторный вызов ничего не делает; любая ошибка — [State.Failed]. */
    fun load(open: () -> InputStream) {
        synchronized(this) {
            if (started) return
            started = true
        }
        state = try {
            val t0 = System.nanoTime()
            val vectors = open().use { MeaningVectors.read(it) }
            State.Ready(vectors, (System.nanoTime() - t0) / 1_000_000)
        } catch (e: Throwable) {
            State.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
