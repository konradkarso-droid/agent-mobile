package com.uroboros.memory.nav

import com.uroboros.util.TextFold
import java.io.InputStream

/**
 * Таблица глаголов для разворота ([Retelling]): пары форм 1-го и 2-го лица
 * единственного числа («работаю работаешь»). Файл — `app/src/main/assets/verb_person_stems.txt`
 * ([readStems]), источник, лицензия и формат — рядом, в `verb_person_stems_SOURCE.md`.
 *
 * Поиск в обе стороны: ключ — форма после util.TextFold, ответ — парная форма
 * как в таблице (с «ё»).
 *
 * НЕОДНОЗНАЧНАЯ форма — та, у которой после приведения больше одной пары:
 * «лечу» (лететь — летишь, лечить — лечишь), двойники «е/ё». Пары для неё нет
 * ([pairOf] — null), она отвечает на [isAmbiguous]: разворот не берёт
 * предложение с такой формой целиком, иначе выйдет «ты лечу» или неверный глагол.
 *
 * КАК ХРАНИТСЯ. Пара — это общая основа и образец окончаний: «работаю
 * работаешь» — основа «работа» и образец «ю / ешь». Образцов на всю таблицу
 * несколько десятков, пар — десятки тысяч, поэтому в памяти лежат только
 * образцы и при каждом — набор основ (тот же приём, что у морфологических
 * словарей). Форма ищется так: для каждого образца, чьё окончание стоит у
 * формы в конце, отрезать окончание и найти основу в наборе образца; парная
 * форма — основа плюс второе окончание. Ответ тот же, что у словаря «форма →
 * пара», а памяти нужно в несколько раз меньше.
 *
 * Строка не по формату пропускается и считается ([skippedLines]).
 */
class RetellTable private constructor(
    private val patterns: List<Pattern>,
    /** Пар таблицы (основ во всех образцах). */
    val pairCount: Int,
    val skippedLines: Int,
) {
    /**
     * Образец окончаний: [first] — у формы 1-го лица, [second] — 2-го, как в
     * таблице (с «ё»). [stems] — основы образца: ключ — основа после
     * util.TextFold, значение — основа как в таблице, или массив таких, если
     * после приведения совпали две (двойники «е/ё»).
     */
    private class Pattern(val first: String, val second: String) {
        val foldedFirst = TextFold.fold(first)
        val foldedSecond = TextFold.fold(second)
        val stems = HashMap<String, Any>()

        fun add(stem: String) {
            val key = TextFold.fold(stem)
            when (val old = stems[key]) {
                null -> stems[key] = stem
                is String -> if (old != stem) stems[key] = arrayOf(old, stem)
                is Array<*> -> if (stem !in old) stems[key] = (old as Array<String>) + stem
            }
        }

        fun originals(foldedStem: String): List<String> = when (val v = stems[foldedStem]) {
            null -> emptyList()
            is String -> listOf(v)
            else -> (v as Array<String>).toList()
        }
    }

    /** Все парные формы для формы [key], уже приведённой util.TextFold. */
    private fun partners(key: String): Set<String> {
        var out: MutableSet<String>? = null
        for (p in patterns) {
            if (key.endsWith(p.foldedFirst)) {
                for (stem in p.originals(key.substring(0, key.length - p.foldedFirst.length))) {
                    (out ?: HashSet<String>().also { out = it }) += stem + p.second
                }
            }
            if (key.endsWith(p.foldedSecond)) {
                for (stem in p.originals(key.substring(0, key.length - p.foldedSecond.length))) {
                    (out ?: HashSet<String>().also { out = it }) += stem + p.first
                }
            }
        }
        return out ?: emptySet()
    }

    /** Парная форма для слова в любом регистре и с любой «ё»; нет пары или форма неоднозначна — null. */
    fun pairOf(word: String): String? = partners(TextFold.fold(word)).singleOrNull()

    fun isAmbiguous(word: String): Boolean = partners(TextFold.fold(word)).size > 1

    /**
     * Слово — форма глагола из таблицы (1-го или 2-го лица), в любом регистре и
     * с любой «ё»; неоднозначная форма тоже глагол. Для определителя лица
     * (PersonForm.verbs): отличить «пишу» от «температуру».
     */
    fun knowsVerb(word: String): Boolean = partners(TextFold.fold(word)).isNotEmpty()

    /**
     * Неоднозначных форм в таблице. Считается один раз, при первом вопросе: для
     * этого перебираются все формы таблицы.
     */
    val ambiguousCount: Int by lazy {
        val forms = HashSet<String>()
        for (p in patterns) for (stem in p.stems.keys) {
            forms += stem + p.foldedFirst
            forms += stem + p.foldedSecond
        }
        forms.count { partners(it).size > 1 }
    }

    companion object {
        private val SPACES = Regex("\\s+")

        /** Первая строка файла таблицы — её формат. */
        const val FORMAT = "UVS1"

        /** Как в файле пишется пустое окончание. */
        private const val EMPTY_ENDING = "-"

        /**
         * Таблица из строк-пар «работаю работаешь», по паре в строке, через
         * пробел. Для тестов и для пересборки файла: приложение читает файл
         * основ ([readStems]).
         */
        fun parse(lines: Sequence<String>): RetellTable {
            val builder = Builder()
            var skipped = 0
            for (line in lines) {
                val parts = line.trim().split(SPACES)
                if (parts.size != 2 || parts[0].isEmpty()) {
                    if (line.isNotBlank()) skipped++
                    continue
                }
                builder.addPair(parts[0], parts[1])
            }
            return builder.build(skipped)
        }

        /**
         * Таблица из файла основ (`verb_person_stems.txt`):
         *  - первая строка — [FORMAT];
         *  - вторая — число образцов N;
         *  - N строк образцов «окончание-1-го-лица окончание-2-го-лица» через
         *    пробел, пустое окончание — «-»; номер образца — порядок строки с нуля;
         *  - дальше основы по алфавиту, по одной в строке: «КОНЕЦ НОМЕР», где
         *    КОНЕЦ — число букв, общих с началом предыдущей основы, и сразу за
         *    ним — остаток основы («5ал» после «работа» — «работал»), НОМЕР —
         *    номер образца.
         * Файл не того формата — исключение с причиной; строка основы не по
         * формату — пропущена и посчитана.
         */
        fun readStems(lines: Sequence<String>): RetellTable {
            val rows = lines.iterator()
            check(rows.hasNext() && rows.next().trim() == FORMAT) { "не файл основ $FORMAT" }
            check(rows.hasNext()) { "нет числа образцов" }
            val count = rows.next().trim().toIntOrNull()
            check(count != null && count > 0) { "нет числа образцов" }
            val patterns = (0 until count).map {
                check(rows.hasNext()) { "файл обрезан на образцах" }
                val parts = rows.next().trim().split(SPACES)
                check(parts.size == 2) { "образец не из двух окончаний" }
                Pattern(ending(parts[0]), ending(parts[1]))
            }
            var prev = ""
            var pairs = 0
            var skipped = 0
            while (rows.hasNext()) {
                val line = rows.next()
                if (line.isBlank()) continue
                val m = STEM_LINE.matchEntire(line.trim())
                val keep = m?.groupValues?.get(1)?.toIntOrNull()
                val index = m?.groupValues?.get(3)?.toIntOrNull()
                if (m == null || keep == null || index == null || keep > prev.length || index !in patterns.indices) {
                    skipped++
                    continue
                }
                val stem = prev.substring(0, keep) + m.groupValues[2]
                patterns[index].add(stem)
                prev = stem
                pairs++
            }
            return RetellTable(patterns, pairs, skipped)
        }

        private val STEM_LINE = Regex("(\\d+)(\\D*) (\\d+)")

        private fun ending(s: String) = if (s == EMPTY_ENDING) "" else s

        /**
         * Строки файла основ для этой таблицы пар — для пересборки файла из
         * таблицы пар (см. `verb_person_stems_SOURCE.md`).
         */
        fun stemsFileLines(table: RetellTable): List<String> {
            val out = ArrayList<String>()
            out += FORMAT
            out += table.patterns.size.toString()
            for (p in table.patterns) {
                out += "${p.first.ifEmpty { EMPTY_ENDING }} ${p.second.ifEmpty { EMPTY_ENDING }}"
            }
            val all = table.patterns.withIndex()
                .flatMap { (i, p) -> p.stems.values.flatMap { v -> if (v is String) listOf(v) else (v as Array<String>).toList() }.map { it to i } }
                .sortedWith(compareBy({ it.first }, { it.second }))
            var prev = ""
            for ((stem, i) in all) {
                val keep = stem.commonPrefixWith(prev).length
                out += "$keep${stem.substring(keep)} $i"
                prev = stem
            }
            return out
        }

        /** Таблица без пар: местоимения разворот меняет, глаголы — нет. */
        val EMPTY = RetellTable(emptyList(), 0, 0)
    }

    /** Раскладка пар на основу и образец; образцы — по частоте, частые первыми. */
    private class Builder {
        private val pairs = ArrayList<Pair<String, Pair<String, String>>>()

        fun addPair(a: String, b: String) {
            val common = a.commonPrefixWith(b).length
            pairs += a.substring(0, common) to (a.substring(common) to b.substring(common))
        }

        fun build(skipped: Int): RetellTable {
            val order = pairs.groupingBy { it.second }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
                    .thenBy { it.key.first }.thenBy { it.key.second })
                .map { it.key }
            val byEnding = order.associateWith { Pattern(it.first, it.second) }
            for ((stem, endings) in pairs) byEnding.getValue(endings).add(stem)
            return RetellTable(order.map { byEnding.getValue(it) }, pairs.size, skipped)
        }
    }
}

/**
 * Состояние таблицы разворота на всё время жизни процесса. Загружается один
 * раз, не в главном потоке ([load] вызывает экран при создании).
 *
 * Память, которую таблица занимает на телефоне, не мерена; прибор показывает
 * число пар и время загрузки, чтобы это было видно.
 *
 * ДВА ВЫКЛЮЧАТЕЛЯ, потому что у таблицы два потребителя с разной проверкой:
 *
 * [ENABLED] — грузить ли таблицу вообще. Пока false, таблица не читается,
 * прибор пишет «разворот: выключен», и оба потребителя обходятся без неё.
 * Выключенная таблица выключает и определителю лица (PersonForm.verbs) «я»
 * глаголом без местоимения: такие слова остаются «не ясно».
 *
 * [MIXED] — отдавать ли таблицу развороту СМЕШАННЫХ записей в отборе
 * ([table]: зеркало в отборе, дверь сна, ассоциация — MirrorFilter.retold).
 * Выключен: стенд на живом ходе показал, что пересказ смешанного
 * предложения-обрывка («вспомню в процессе, - ты понял») растит путаницу —
 * модель выдаёт строки стены за слова собеседника. Включать после правила,
 * какие предложения смешанной записи брать, проверенного стендом. Пока он
 * выключен, смешанная запись владельца снимается зеркалом, как без разворота.
 *
 * Портрет собеседника (Portrait) берёт таблицу мимо [MIXED] — через
 * [loaded]: он пересказывает только предложения без обращения к агенту, где
 * повернуть надо одно лицо, а не два. Слей выключатели в один — и включение
 * портрета молча включило бы разворот смешанных, который стенд забраковал.
 */
object RetellHolder {

    const val ENABLED = true

    const val MIXED = false

    sealed class State {
        object Loading : State()
        object Off : State()
        data class Failed(val reason: String) : State()
        data class Ready(val table: RetellTable, val millis: Long) : State()
    }

    @Volatile
    var state: State = State.Loading
        private set

    private var started = false

    /**
     * Таблица для разворота смешанных записей в отборе: есть, только если
     * загружена и включён [MIXED]; иначе null — смешанные снимаются.
     */
    val table: RetellTable? get() = if (MIXED) loaded else null

    /** Загруженная таблица, мимо [MIXED], — для портрета собеседника; не загружена — null. */
    val loaded: RetellTable? get() = (state as? State.Ready)?.table

    /**
     * Строка «Подробно» о таблице. Печатается всегда: таблицей пользуется и
     * определитель лица (PersonForm.verbs), и без неё «я» глаголом на -у/-ю
     * читается как «не ясно» — это должно быть видно.
     */
    fun meterLine(): String = when (val s = state) {
        is State.Ready -> "Таблица глаголов: пар ${s.table.pairCount}, неоднозначных ${s.table.ambiguousCount}, " +
            "загружена за ${s.millis} мс · по ней разворот и лицо глаголов на -у/-ю"
        State.Loading -> "Таблица глаголов: загружается — «я» глаголом без местоимения пока «не ясно»"
        State.Off -> "Таблица глаголов: выключена — «я» глаголом без местоимения всегда «не ясно»"
        is State.Failed -> "Таблица глаголов: не загрузилась (${s.reason}) — «я» глаголом без местоимения «не ясно»"
    }

    /**
     * Читает таблицу из [open]. Повторный вызов ничего не делает. Любая ошибка
     * (нет файла, нехватка памяти) — состояние [State.Failed] с именем класса
     * ошибки; приложение работает как без разворота.
     */
    fun load(open: () -> InputStream) {
        synchronized(this) {
            if (started) return
            started = true
        }
        if (!ENABLED) {
            state = State.Off
            return
        }
        val t0 = System.nanoTime()
        state = try {
            val table = open().bufferedReader(Charsets.UTF_8).useLines { RetellTable.readStems(it) }
            // Та же таблица — определителю лица (PersonForm.verbs).
            PersonForm.verbs = table
            State.Ready(table, (System.nanoTime() - t0) / 1_000_000)
        } catch (e: Throwable) {
            State.Failed(e.javaClass.simpleName)
        }
    }
}
