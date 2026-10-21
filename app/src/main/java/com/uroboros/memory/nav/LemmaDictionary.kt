package com.uroboros.memory.nav

import com.uroboros.util.TextFold
import java.io.InputStream

/**
 * Словарь начальных форм: для слова — его начальная форма («людьми» →
 * «человек», «шёл» → «идти») и разбор: часть речи, лицо, число, пометка
 * имени, фамилии, отчества, места. Файл — `app/src/main/assets/lemma_forms.txt`
 * ([read]); источник, лицензия, формат и пересборка — рядом, в
 * `lemma_forms_SOURCE.md`.
 *
 * Слова сравниваются в виде util.TextFold (регистр и «ё» не важны). Начальная
 * форма отдаётся в том же виде: нижний регистр, «е» вместо «ё».
 *
 * КАК ХРАНИТСЯ. Тот же приём, что у таблицы глаголов ([RetellTable]): лексема —
 * основа и образец окончаний. Образцов на весь словарь около трёх тысяч, основ —
 * по одной на лексему. В памяти: основы одним массивом байт (буква — байт),
 * их начала и номера образцов — массивами чисел, окончания — таблицей
 * «окончание → в каких образцах оно есть и с каким разбором». Слово ищется так:
 * для каждого места разреза хвост проверяется по таблице окончаний, голова —
 * двоичным поиском среди основ; разбор есть, если у найденной основы тот же
 * образец. Приставки «наи-» и «по-» сравнительных степеней («наилучший»,
 * «побольше») отрезаются отдельно: в образце они стоят перед основой.
 *
 * ЧЕГО НЕ УМЕЕТ.
 *  - Незнакомое слово — пустой ответ, без догадки. Выдумать разбор словарь не
 *    может по устройству: ответ бывает, только если слово целиком — форма
 *    лексемы словаря. Имена, новые слова, опечатки, латиница — «не знаю слова».
 *  - Омонимы не различает: «вина» — и «вина», и «вино»; «стекло» — и
 *    существительное, и глагол «стечь». Отдаются все разборы, выбирает
 *    вызывающий.
 *  - Падеж и род — только у имён (пометка Name: nomn, gent, datv, accs, ablt,
 *    loct; masc, femn, ms-f — общий, «Саша»). У остальных слов в разборе часть
 *    речи, лицо, число, пометки имён, у глагола — наклонение и время (impr —
 *    повелительное, incl — «давай вместе»: «пойдём»; past, pres, futr), у
 *    прилагательного-местоимения — Apro («мой», «твою»), у вводного слова —
 *    Prnt, у вопросительного — Ques. Падеж у всех слов умножил бы образцы, а
 *    потребителям он не нужен.
 *  - Формы по начальной строит только для имён ([nameForms]); для остальных
 *    слов — только разбор.
 *  - Смысла нет: «код» и «программа» — разные слова.
 *  - Сам не растёт: слово, которого нет в OpenCorpora, не появится, пока словарь
 *    не пересобран.
 *  - Ошибки словаря-источника переходят как есть: «болтаем» в нём ещё и краткое
 *    причастие.
 *
 * Основы `RiskTrigger.stem` словарь не заменяет: на них подобраны пороги эха,
 * зеркала и опоры, и замена молча сдвинула бы их все. Словарь — отдельный
 * признак рядом с основами.
 */
class LemmaDictionary private constructor(
    private val tags: Array<Set<String>>,
    private val lemmaEndings: Array<String>,
    /** Ключ — «приставка|окончание»; значение — пары (номер образца, номер разбора) подряд. */
    private val byEnding: HashMap<String, IntArray>,
    /** Приставки, которые встречаются в образцах, кроме пустой. */
    private val prefixes: List<String>,
    private val stemBytes: ByteArray,
    private val stemStart: IntArray,
    private val stemPattern: ShortArray,
    /** Только образцы с пометкой Name: номер образца → его окончания «приставка|окончание» и номера разборов. */
    private val nameEndings: HashMap<Int, List<Pair<String, Int>>>,
) {
    /** Один разбор слова: начальная форма и пометки (часть речи, лицо, число, пометки имён). */
    data class Analysis(val lemma: String, val tags: Set<String>) {
        /** Часть речи разбора (обозначения OpenCorpora: NOUN, VERB, ADJF…); пометок без части речи не бывает. */
        val partOfSpeech: String get() = tags.first { it in PARTS_OF_SPEECH }
    }

    /**
     * Имя из словаря: [gender] — род имени (masc, femn, ms-f — общий; null —
     * словарь не знает), [forms] — формы единственного числа по падежу (nomn,
     * gent, datv, accs, ablt, loct), в виде util.TextFold. В одном падеже форм
     * бывает две: «машей» и «машею».
     *
     * Род — грамматический род имени по словарю-источнику, а не пол человека.
     */
    class NameLexeme(val gender: String?, val forms: Map<String, List<String>>)

    /**
     * Лексемы-имена, у которых [name] — начальная форма (именительный
     * единственного). Нарицательные омонимы («любовь», «вера») не берутся: у
     * них склонение своё («любви», а не «Любови»). Незнакомое имя — пустой
     * список; две лексемы — имя двоякое («Мишель»: мужское склоняется, женское
     * нет), выбирает вызывающий.
     */
    fun nameForms(name: String): List<NameLexeme> {
        val folded = TextFold.fold(name)
        if (folded.isEmpty() || folded.any { encode(it) == NOT_A_LETTER }) return emptyList()
        val out = ArrayList<NameLexeme>()
        val seen = HashSet<Pair<String, Int>>()
        match("", folded) { stem, pattern, tag ->
            val t = tags[tag]
            if ("Name" !in t || "nomn" !in t || "sing" !in t) return@match
            if (stem + lemmaEndings[pattern] != folded || !seen.add(stem to pattern)) return@match
            val entries = nameEndings[pattern] ?: return@match
            val forms = LinkedHashMap<String, MutableList<String>>()
            for ((key, ft) in entries) {
                val tg = tags[ft]
                if ("sing" !in tg) continue
                val case = tg.firstOrNull { it in NAME_CASES } ?: continue
                val bar = key.indexOf('|')
                val form = key.substring(0, bar) + stem + key.substring(bar + 1)
                val list = forms.getOrPut(case) { ArrayList() }
                if (form !in list) list += form
            }
            out += NameLexeme(t.firstOrNull { it in NAME_GENDERS }, forms)
        }
        return out
    }

    /** Лексем в словаре (по основе на лексему). */
    val lexemeCount: Int get() = stemPattern.size

    /** Образцов окончаний. */
    val patternCount: Int get() = lemmaEndings.size

    /**
     * Сколько байт занимают массивы словаря — без накладных расходов Java на
     * объекты и строки таблицы окончаний. Нижняя граница, а не замер: замер
     * всей кучи на стенде лежит в `lemma_forms_SOURCE.md`.
     */
    val arrayBytes: Long
        get() = stemBytes.size.toLong() + stemStart.size * 4L + stemPattern.size * 2L +
            byEnding.values.sumOf { it.size * 4L }

    /** Все разборы слова в любом регистре и с любой «ё»; незнакомое слово — пустой список. */
    fun analyze(word: String): List<Analysis> {
        val folded = TextFold.fold(word)
        if (folded.isEmpty() || folded.any { encode(it) == NOT_A_LETTER }) return emptyList()
        val out = LinkedHashSet<Analysis>()
        collect("", folded, out)
        for (p in prefixes) if (folded.startsWith(p)) collect(p, folded.substring(p.length), out)
        return out.toList()
    }

    /** Начальные формы слова; незнакомое слово — пустое множество. */
    fun lemmas(word: String): Set<String> = analyze(word).mapTo(LinkedHashSet()) { it.lemma }

    /** Слово — форма из словаря. */
    fun knows(word: String): Boolean = analyze(word).isNotEmpty()

    private fun collect(prefix: String, rest: String, out: MutableSet<Analysis>) {
        match(prefix, rest) { stem, pattern, tag -> out += Analysis(stem + lemmaEndings[pattern], tags[tag]) }
    }

    /** Каждое прочтение [rest] (после приставки [prefix]): основа, номер образца, номер разбора. */
    private inline fun match(prefix: String, rest: String, found: (String, Int, Int) -> Unit) {
        val bytes = ByteArray(rest.length) { encode(rest[it]) }
        for (cut in 0..rest.length) {
            val pairs = byEnding[prefix + "|" + rest.substring(cut)] ?: continue
            var row = lowerBound(bytes, cut)
            while (row < stemPattern.size && compareStem(row, bytes, cut) == 0) {
                val pattern = stemPattern[row].toInt()
                var i = 0
                while (i < pairs.size) {
                    if (pairs[i] == pattern) found(rest.substring(0, cut), pattern, pairs[i + 1])
                    i += 2
                }
                row++
            }
        }
    }

    /** Первая строка основ, не меньшая первых [length] байт [key]. */
    private fun lowerBound(key: ByteArray, length: Int): Int {
        var lo = 0
        var hi = stemPattern.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareStem(mid, key, length) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Основа строки [row] против первых [length] байт [key]: знак сравнения. */
    private fun compareStem(row: Int, key: ByteArray, length: Int): Int {
        val from = stemStart[row]
        val size = stemStart[row + 1] - from
        val n = minOf(size, length)
        for (i in 0 until n) {
            val d = stemBytes[from + i] - key[i]
            if (d != 0) return d
        }
        return size - length
    }

    companion object {
        /** Первая строка файла — его формат. */
        const val FORMAT = "LEM1"

        /** Обозначения частей речи OpenCorpora, которые есть в словаре. */
        val PARTS_OF_SPEECH = setOf(
            "NOUN", "ADJF", "ADJS", "COMP", "VERB", "INFN", "PRTF", "PRTS", "GRND", "NUMR", "ADVB",
            "NPRO", "PRED", "PREP", "CONJ", "PRCL", "INTJ",
        )

        private const val NOT_A_LETTER: Byte = 0

        private val NAME_CASES = setOf("nomn", "gent", "datv", "accs", "ablt", "loct")
        private val NAME_GENDERS = setOf("masc", "femn", "ms-f")

        /**
         * Буква — байт, с сохранением порядка строк файла: «-» меньше букв, буквы
         * по алфавиту. Основы в файле отсортированы как строки, и двоичный поиск
         * по байтам идёт в том же порядке. Не буква словаря — [NOT_A_LETTER].
         */
        private fun encode(c: Char): Byte = when (c) {
            '-' -> 1
            in 'а'..'я' -> (c - 'а' + 2).toByte()
            else -> NOT_A_LETTER
        }

        /**
         * Словарь из строк файла. Формат (UTF-8, по строке на запись):
         *  - `LEM1`;
         *  - `T n`, затем n строк — разборы, пометки через запятую («NOUN,sing»);
         *  - `E n`, затем n строк — окончания «приставка|окончание|номер разбора»
         *    (приставка и окончание бывают пустыми);
         *  - `P n`, затем n строк — образцы «окончание начальной формы|номера
         *    окончаний через пробел»;
         *  - `S n`, затем n строк — основы по алфавиту «k хвост номер образца»: первые
         *    k букв — как у предыдущей основы, k пишется цифрами вплотную к хвосту.
         * Строка не по формату — ошибка чтения: словарь либо цел, либо не загружен.
         */
        fun read(lines: Sequence<String>): LemmaDictionary {
            val it = lines.iterator()
            fun next(): String = if (it.hasNext()) it.next() else error("файл словаря оборвался")
            fun count(tag: String): Int {
                val head = next()
                require(head.startsWith("$tag ")) { "ждали раздел $tag, а там «$head»" }
                return head.substring(tag.length + 1).toInt()
            }
            require(next() == FORMAT) { "не тот формат словаря" }

            val tags = Array(count("T")) { next().split(',').toSet() }

            val entryCount = count("E")
            val entryKey = arrayOfNulls<String>(entryCount)
            val entryTag = IntArray(entryCount)
            val prefixes = LinkedHashSet<String>()
            for (i in 0 until entryCount) {
                val parts = next().split('|')
                require(parts.size == 3) { "окончание не по формату: $i" }
                entryKey[i] = parts[0] + "|" + parts[1]
                entryTag[i] = parts[2].toInt()
                if (parts[0].isNotEmpty()) prefixes += parts[0]
            }

            val patternCount = count("P")
            require(patternCount <= Short.MAX_VALUE) { "образцов больше, чем вмещает номер" }
            val lemmaEndings = Array(patternCount) { "" }
            val collected = HashMap<String, MutableList<Int>>()
            val nameEndings = HashMap<Int, List<Pair<String, Int>>>()
            for (p in 0 until patternCount) {
                val line = next()
                val bar = line.indexOf('|')
                require(bar >= 0) { "образец не по формату: $p" }
                lemmaEndings[p] = line.substring(0, bar)
                val ids = line.substring(bar + 1).split(' ').map { it.toInt() }
                for (id in ids) {
                    collected.getOrPut(entryKey[id]!!) { ArrayList() }.apply { add(p); add(entryTag[id]) }
                }
                if (ids.any { "Name" in tags[entryTag[it]] }) nameEndings[p] = ids.map { entryKey[it]!! to entryTag[it] }
            }
            val byEnding = HashMap<String, IntArray>(collected.size * 2)
            for ((k, v) in collected) byEnding[k] = v.toIntArray()

            val stemCount = count("S")
            val stemStart = IntArray(stemCount + 1)
            val stemPattern = ShortArray(stemCount)
            var bytes = ByteArray(stemCount * 8)
            var used = 0
            var prev = ByteArray(0)
            for (r in 0 until stemCount) {
                val line = next()
                val space = line.lastIndexOf(' ')
                require(space >= 0) { "основа не по формату: $r" }
                var digits = 0
                while (digits < space && line[digits].isDigit()) digits++
                val shared = line.substring(0, digits).toInt()
                require(shared <= prev.size) { "основа $r ссылается на несуществующее начало" }
                val tail = line.substring(digits, space)
                val stem = ByteArray(shared + tail.length)
                System.arraycopy(prev, 0, stem, 0, shared)
                for (i in tail.indices) {
                    val b = encode(tail[i])
                    require(b != NOT_A_LETTER) { "в основе $r не буква словаря" }
                    stem[shared + i] = b
                }
                if (used + stem.size > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, used + stem.size))
                System.arraycopy(stem, 0, bytes, used, stem.size)
                stemStart[r] = used
                used += stem.size
                stemPattern[r] = line.substring(space + 1).toInt().also {
                    require(it in 0 until patternCount) { "основа $r: нет образца $it" }
                }.toShort()
                prev = stem
            }
            stemStart[stemCount] = used
            require(!it.hasNext() || it.next().isEmpty()) { "после основ лишние строки" }

            return LemmaDictionary(
                tags, lemmaEndings, byEnding, prefixes.toList(), bytes.copyOf(used), stemStart, stemPattern,
                nameEndings,
            )
        }
    }
}

/**
 * Словарь начальных форм на процесс: грузится один раз, не в главном потоке
 * (см. [load]). Потребитель — портрет по теме (Portrait.topic); строка
 * «Подробно» показывает цену в памяти и времени. Выключатель [ENABLED]
 * снимает и загрузку, и цену, и тогда портрет по теме не ищется.
 */
object LemmaHolder {

    const val ENABLED = true

    sealed class State {
        object Loading : State()
        object Off : State()
        data class Failed(val reason: String) : State()
        data class Ready(val dictionary: LemmaDictionary, val millis: Long, val heapBytes: Long?) : State()
    }

    @Volatile
    var state: State = State.Loading
        private set

    private var started = false

    /** Загруженный словарь; не загружен — null. */
    val loaded: LemmaDictionary? get() = (state as? State.Ready)?.dictionary

    /**
     * Строка «Подробно». Память — двумя числами: «массивы» — точный размер
     * данных словаря; «куча» — рост занятой памяти Java за загрузку, грубый
     * (параллельно работают другие потоки, сборщик мусора не обязан убрать всё),
     * поэтому она бывает больше и меньше настоящего; не измерилась — так и сказано.
     */
    fun meterLine(): String = when (val s = state) {
        is State.Ready -> {
            val heap = s.heapBytes?.let { "куча ≈ ${mb(it)} МБ" } ?: "кучу измерить не вышло"
            "Словарь начальных форм: лексем ${s.dictionary.lexemeCount}, образцов ${s.dictionary.patternCount}, " +
                "загружен за ${s.millis} мс · массивы ${mb(s.dictionary.arrayBytes)} МБ, $heap · потребители: портрет по теме, определитель лица, облака"
        }
        State.Loading -> "Словарь начальных форм: загружается"
        State.Off -> "Словарь начальных форм: выключен"
        is State.Failed -> "Словарь начальных форм: не загрузился (${s.reason})"
    }

    private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        System.gc()
        return rt.totalMemory() - rt.freeMemory()
    }

    /**
     * Читает словарь из [open]. Повторный вызов ничего не делает. Любая ошибка
     * (нет файла, файл не по формату, нехватка памяти) — [State.Failed] с
     * причиной; приложение работает как без словаря.
     */
    /** Файл словаря в assets приложения. Одно место имени для всех, кто грузит. */
    const val ASSET = "lemma_forms.txt"

    fun load(open: () -> InputStream) {
        synchronized(this) {
            if (started) return
            started = true
        }
        if (!ENABLED) {
            state = State.Off
            return
        }
        state = try {
            val before = usedHeap()
            val t0 = System.nanoTime()
            val dictionary = open().bufferedReader(Charsets.UTF_8).useLines { LemmaDictionary.read(it) }
            val millis = (System.nanoTime() - t0) / 1_000_000
            val grown = usedHeap() - before
            PersonForm.lemmas = dictionary
            State.Ready(dictionary, millis, grown.takeIf { it > 0 })
        } catch (e: Throwable) {
            State.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
