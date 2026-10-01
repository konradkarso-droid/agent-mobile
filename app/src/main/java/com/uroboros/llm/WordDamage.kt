package com.uroboros.llm

import com.uroboros.util.TextFold
import java.io.DataInputStream
import java.io.InputStream

/**
 * Детектор порчи слов в ответе модели.
 *
 * ЧТО СЧИТАЕТСЯ ПОРЧЕЙ. Несуществующее слово, которым модель обходит запрет на
 * повтор: «людьмами», «воспаминания», «быстрогоwipeания», «процессов新陈代谢а».
 * Такая копия опасна вдвойне: прибор эха ([EchoCheck]) её уже не узнаёт, а в
 * ленте она становится образцом и размножается.
 *
 * ДВА СЛОЯ.
 *  1) Трафарет. В слове есть сочетание из 6 знаков подряд (с началом «^» и
 *     концом «$» слова), которого нет ни в одной словоформе словаря. Не
 *     проверяются: слова короче 4 букв (сочетания из 6 знаков в них нет), слова
 *     целиком заглавными (сокращения: «РОЯЗГСФ»), слова, стоящие в запросе в
 *     ТОЧНО той же форме (имена: «Уроборос», «Одмин»). Сверка с запросом по
 *     основе не годится: «людьмами» спряталось бы за «людьми» из прошлого
 *     ответа — а это и есть главная порча.
 *  2) Буквы. Латиница или иероглиф вплотную к русской букве; заглавная посреди
 *     русского слова («навЫков»); латинское слово от 3 букв вне кавычек и
 *     скобок. Иероглифы и латиница в кавычках или скобках — не порча: это
 *     перевод или термин, а китайский агенту разрешён, перевод даётся в скобках.
 *
 * ЧЕГО НЕ УМЕЕТ.
 *  - Слово, все кусочки которого бывают в настоящих словах («отвечением»),
 *    пропускает.
 *  - Ошибку формы из настоящих слов («в моей памятей», «управление людям») не
 *    видит: это не порча, и прибор эха такую копию узнаёт сам — он сравнивает
 *    основы.
 *  - Новых слов, которых нет ни в словаре, ни в запросе («нейросеть», «промпт»),
 *    не знает — считает порчей.
 *  - Латинское слово, о котором просили, но без кавычек («R — Red»), считает
 *    порчей. Иероглиф после пробела или запятой («Хорошо,谢谢») — не считает.
 *  Отсюда ложные отказы — в разговорах о чужих языках и новых словах.
 *
 * ЧИСЛА И ИХ ОБЛАСТЬ. Модель Qwen2.5-3B, русская речь агента: на ответах с
 * DRY порчу ловит почти всю, на речи без DRY ложные отказы — единицы на сотни
 * ответов, все из перечисленных выше случаев. Перепроверяется прогоном
 * [check] по выгрузке журнала с телефона и ручным просмотром отмеченного.
 *
 * Трафарет — `app/src/main/assets/word_stencil6.bin`, формат и источник —
 * рядом, в `word_stencil6_SOURCE.md`. В памяти — массив чисел, около 7 МБ.
 */
class WordDamage private constructor(private val codes: LongArray) {

    /** Сочетаний в трафарете. */
    val size: Int get() = codes.size

    /**
     * Найденные испорченные куски — как их показать человеку; пустой список —
     * порчи нет. [request] — слова запроса из [requestWords]: слово ответа,
     * стоящее там в той же форме, трафаретом не проверяется.
     */
    fun check(answer: String, request: Set<String>): List<String> {
        val found = ArrayList<String>()
        val caps = CAPS.findAll(answer).mapTo(HashSet()) { TextFold.fold(it.value) }
        for (m in WORD.findAll(TextFold.fold(answer))) {
            val w = m.value
            if (w in caps || w in request) continue
            if (!fits(w)) found += w
        }
        GLUED.find(answer)?.let { found += it.value }
        LATIN.find(QUOTED.replace(answer, " "))?.let { found += it.value }
        return found
    }

    /** Все ли сочетания слова есть в трафарете. Слово — только буквы а–я. */
    private fun fits(word: String): Boolean {
        val s = "^" + word + "$"
        for (i in 0..s.length - N) {
            if (codes.binarySearch(pack(s, i)) < 0) return false
        }
        return true
    }

    /**
     * Проверка на заведомо испорченном и заведомо настоящем слове. Ложь — трафарет
     * прочитан неверно (пустой, не тот файл): детектор молчал бы на всём, и
     * молчание было бы неотличимо от «порчи нет».
     */
    fun selfCheck(): Boolean =
        check("людьмами", emptySet()).isNotEmpty() && check("людьми", emptySet()).isEmpty()

    companion object {
        /** Длина сочетания в знаках вместе с «^» и «$». */
        const val N = 6

        private val WORD = Regex("[а-я]+")
        private val CAPS = Regex("(?<![\\p{L}\\p{N}_])[А-ЯЁ]{2,}(?![\\p{L}\\p{N}_])")
        private val GLUED = Regex(
            "\\S*(?:[а-яёА-ЯЁ][a-zA-Z]|[a-zA-Z][а-яёА-ЯЁ]|[а-яё][А-ЯЁ]|" +
                "[а-яё][\\u4e00-\\u9fff]|[\\u4e00-\\u9fff][а-яё])\\S*"
        )
        private val QUOTED = Regex("«[^»]*»|\"[^\"]*\"|“[^”]*”|\\([^)]*\\)")
        private val LATIN = Regex("(?<![\\p{L}\\p{N}_])[a-zA-Z]{3,}(?![\\p{L}\\p{N}_])")
        private val MAGIC = byteArrayOf('U'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(), '6'.code.toByte())

        /** Слова запроса в том виде, в каком [check] сверяет с ними слова ответа. */
        fun requestWords(text: String): Set<String> =
            WORD.findAll(TextFold.fold(text)).mapTo(HashSet()) { it.value }

        /** Знак сочетания — 6 бит: «^» 0, «$» 1, а..я 2..33. */
        private fun code(c: Char): Long = when (c) {
            '^' -> 0L
            '$' -> 1L
            else -> (c - 'а' + 2).toLong()
        }

        private fun pack(s: String, from: Int): Long {
            var v = 0L
            for (j in 0 until N) v = (v shl 6) or code(s[from + j])
            return v
        }

        /**
         * Читает трафарет: «UST6», число сочетаний (4 байта, старший вперёд), затем
         * разности соседних сочетаний по возрастанию, каждая — varint (по 7 бит,
         * младшие вперёд). Файл не тот или обрезан — исключение с причиной.
         */
        fun read(input: InputStream): WordDamage {
            val d = DataInputStream(input.buffered())
            val magic = ByteArray(4)
            d.readFully(magic)
            kotlin.check(magic.contentEquals(MAGIC)) { "не файл трафарета" }
            val n = d.readInt()
            kotlin.check(n > 0) { "трафарет пуст" }
            val codes = LongArray(n)
            var prev = 0L
            for (i in 0 until n) {
                var delta = 0L
                var shift = 0
                while (true) {
                    val b = d.read()
                    kotlin.check(b >= 0) { "трафарет обрезан на сочетании $i из $n" }
                    delta = delta or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                kotlin.check(i == 0 || delta > 0) { "сочетания не по возрастанию" }
                prev += delta
                codes[i] = prev
            }
            return WordDamage(codes)
        }

        /** Трафарет из списка словоформ — для тестов и сверки; формы вне а–я (после приведения) пропускаются. */
        fun fromWords(words: Iterable<String>): WordDamage {
            val set = HashSet<Long>()
            for (raw in words) {
                val w = TextFold.fold(raw)
                if (!w.matches(WORD)) continue
                val s = "^" + w + "$"
                for (i in 0..s.length - N) set += pack(s, i)
            }
            return WordDamage(set.toLongArray().also { it.sort() })
        }
    }
}

/**
 * Детектор порчи на всё время жизни процесса. Загружается один раз, не в
 * главном потоке ([load] вызывает экран при создании).
 *
 * Не загружен или не прошёл самопроверку — [damage] null, и тот, кто его
 * спрашивает, работает как без детектора; [meterLine] говорит об этом в
 * «Подробно», чтобы «порчи нет» не путалось с «детектор не работает».
 */
object WordDamageHolder {

    sealed class State {
        object Loading : State()
        data class Failed(val reason: String) : State()
        data class Ready(val damage: WordDamage, val millis: Long) : State()
    }

    @Volatile
    var state: State = State.Loading
        private set

    private var started = false

    /** Детектор, если загружен и прошёл самопроверку; иначе null. */
    val damage: WordDamage? get() = (state as? State.Ready)?.damage

    fun meterLine(): String = when (val s = state) {
        State.Loading -> "Детектор порчи: загружается"
        is State.Failed -> "Детектор порчи НЕ РАБОТАЕТ: ${s.reason} — испорченные слова в ленту пропускаются"
        is State.Ready -> "Детектор порчи: сочетаний ${s.damage.size}, загружен за ${s.millis} мс"
    }

    /**
     * Читает трафарет из [open]. Повторный вызов ничего не делает. Любая ошибка
     * (нет файла, обрезан, нехватка памяти) или проваленная самопроверка —
     * [State.Failed] с причиной.
     */
    fun load(open: () -> InputStream) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val t0 = System.nanoTime()
        state = try {
            val d = open().use { WordDamage.read(it) }
            if (d.selfCheck()) State.Ready(d, (System.nanoTime() - t0) / 1_000_000)
            else State.Failed("самопроверка не прошла — файл трафарета не тот")
        } catch (e: Throwable) {
            State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
