package com.uroboros.memory.nav

import com.uroboros.memory.Layer
import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sticker
import com.uroboros.memory.allMeaningfulWords
import com.uroboros.memory.normalizeExact
import com.uroboros.util.TextFold
import kotlin.math.sqrt

/**
 * Прибор «смысл»: какую запись нашёл бы поиск по смыслу там, где окна круга
 * ищут по буквам. Только показывает — модели ничего не подаётся, отбор и
 * ответ от прибора не зависят. Чистая логика: тексты и векторы на входе,
 * строка на выходе.
 *
 * ЗАЧЕМ. Окна круга (memory.DolmenCircle) находят запись, только если в ней
 * есть те же слова или основы, что в реплике. Запись, где о том же сказано
 * другими словами («коронка на зубе» — «был у стоматолога»), не находится
 * ни одним окном. Прибор показывает в «Подробно», что принёс бы поиск по
 * смыслу, чтобы по живым разговорам было видно, уместно ли это, раньше, чем
 * такие записи пойдут модели.
 *
 * КАК СЧИТАЕТСЯ. Спектр текста — среднее векторов начальных форм его
 * значимых слов ([MeaningVectors]; значимые — те же, что ищет окно вопроса,
 * memory.allMeaningfulWords). Частое в памяти слово весит меньше редкого:
 * вес [SIF_A] / ([SIF_A] + доля слова среди слов всех записей). Из спектров
 * снимается общая для всех записей составляющая (первая главная): без этого
 * всё похоже на всё. Близость реплики и записи — косинус спектров, плюс
 * [NOUN_BONUS], если у них есть пара разных существительных, близких не меньше
 * [NOUN_CLOSE] («клинок» — «меч»). Лучшая запись пула — с наибольшей
 * близостью; «нашёл бы» — если близость не ниже [THRESHOLD].
 *
 * ПУЛ — записи за теми же заборами, что у окон круга: не на проверке и не
 * отвергнутые, не красный слой, не реплика, которую модель и так видит в
 * ленте, не одни вопросы, просьбы или обычаи, и то, что пропускает зеркало
 * ([MirrorFilter]). Записи, уже усаженные кругом или портретом, не берутся:
 * прибор отвечает на вопрос, что смысл ПРИБАВИЛ бы к словам.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - уместна примерно треть находок выше порога (слепая разметка 30 находок
 *    по выгрузке, см. [THRESHOLD]); остальное — записи «рядом по словарю», а не
 *    по делу, и чем выше близость, тем уместность не выше;
 *  - длинная реплика о новом предмете размывает спектр: «Предпочитаю лёгкие
 *    клинки…» не находит «клуб исторического фехтования»;
 *  - близки и противоположности, и соседи по роду («понедельник» — «суббота»,
 *    «месяц» — «полгода»); многозначность не различается («оперативная
 *    память» телефона и память агента);
 *  - заявлений статуса не отличает: на «Кто я?» поднимает «Я — Админ». Пока
 *    модели не подаётся — не беда; до подачи нужна граница;
 *  - слово без вектора (редкое, новое, латиница) в спектр не входит — пропуск,
 *    а не «далеко»; слово короче четырёх букв не входит тоже, по правилу
 *    значимых слов («меч», «сон», «кот»), — у записи «Меч» спектра нет;
 *  - веса слов и общая составляющая считаются по всем записям заново на
 *    каждом ходе: время видно в строке прибора.
 */
object Meaning {

    /**
     * Порог «нашёл бы». Замерено, не объявлено: верхняя четверть лучших
     * близостей по ходам со свободным местом на выгрузке (644 записи, 521
     * реплика владельца, векторы этого файла); при нём находка примерно в
     * каждом четвёртом таком ходе, уместных по слепой разметке 10 из 30.
     * Перепроверять тем же стендом при смене файла векторов или правила.
     */
    const val THRESHOLD = 0.384

    /**
     * Близость двух существительных, с которой они считаются близкими. Замерено:
     * средняя близость двадцатого ближайшего соседа существительного по
     * векторам этого файла — то есть «в первой двадцатке соседей».
     */
    const val NOUN_CLOSE = 0.423

    /** Надбавка за пару близких существительных. Объявленное число, не подобранное. */
    const val NOUN_BONUS = 0.1

    /** Сглаживание веса слова. Объявленное число, не подобранное (обычное для такого веса). */
    const val SIF_A = 1e-3

    /** Сколько шагов уточнения общей составляющей. Объявленное число: больше не меняет третьего знака. */
    private const val PC_STEPS = 60

    /** Чем кончилось. */
    sealed class Result {
        /** В реплике нет ни одного значимого слова с вектором. */
        data class NoWords(val words: Int) : Result()

        /** Пул пуст: всё уже в круге или за заборами. */
        object EmptyPool : Result()

        /** Ни у одной записи пула нет спектра (ни одного слова с вектором). */
        data class NoSpectra(val pool: Int) : Result()

        /**
         * Лучшая запись пула. [nouns] — пара «существительное реплики →
         * существительное записи», давшая надбавку; null — надбавки нет.
         * [above] — сколько записей пула не ниже порога.
         */
        data class Best(
            val record: Sticker,
            val score: Double,
            val nouns: Pair<String, String>?,
            val above: Int,
            val pool: Int,
        ) : Result() {
            val found: Boolean get() = score >= THRESHOLD
        }
    }

    /**
     * Можно ли записи [record] быть в пуле — заборы окон круга (KDoc объекта).
     * [excludedExact] — реплики ленты и текущая, приведённые [normalizeExact];
     * [excludedRaw] — они же как есть; [taken] — номера записей, уже усаженных.
     */
    fun eligible(
        record: Sticker,
        excludedRaw: Set<String>,
        excludedExact: Set<String>,
        taken: Set<Long>,
        address: Coordinates.Address,
        retell: RetellTable?,
    ): Boolean =
        !record.reviewPending && record.rejectedAt == null && record.layer != Layer.RED.name &&
            record.id !in taken && record.content !in excludedRaw && normalizeExact(record.content) !in excludedExact &&
            !RiskTrigger.isOnlyQuestions(record.content) && !RiskTrigger.isOnlyRequests(record.content) &&
            !RiskTrigger.isOnlySocial(record.content) && MirrorFilter.keeps(record, address, retell)

    /**
     * Лучшая запись [pool] для реплики [question]. [all] — все записи памяти:
     * по ним веса слов и общая составляющая (KDoc объекта).
     */
    fun probe(
        question: String,
        all: List<Sticker>,
        pool: List<Sticker>,
        vectors: MeaningVectors,
        dictionary: LemmaDictionary,
    ): Result {
        val lemmaCache = HashMap<String, String>()
        fun lemmasOf(text: String): List<String> = allMeaningfulWords(text).map { w ->
            lemmaCache.getOrPut(w) { TextFold.fold(dictionary.analyze(w).firstOrNull()?.lemma ?: w) }
        }
        val vecCache = HashMap<String, FloatArray?>()
        fun vec(lemma: String): FloatArray? = vecCache.getOrPut(lemma) { vectors.vector(lemma) }

        val recordLemmas = all.associate { it.id to lemmasOf(it.content) }
        val counts = HashMap<String, Int>()
        for (ls in recordLemmas.values) for (l in ls) counts[l] = (counts[l] ?: 0) + 1
        val total = counts.values.sum().coerceAtLeast(1).toDouble()

        fun spectrum(lemmas: List<String>): DoubleArray? {
            val acc = DoubleArray(vectors.dim)
            var weights = 0.0
            for (l in lemmas) {
                val v = vec(l) ?: continue
                val w = SIF_A / (SIF_A + (counts[l] ?: 0) / total)
                for (i in acc.indices) acc[i] += w * v[i]
                weights += w
            }
            if (weights == 0.0) return null
            for (i in acc.indices) acc[i] /= weights
            return acc
        }

        val raw = all.mapNotNull { r -> spectrum(recordLemmas.getValue(r.id))?.let { r.id to it } }.toMap()
        val questionLemmas = lemmasOf(question)
        val q0 = spectrum(questionLemmas)
            ?: return Result.NoWords(questionLemmas.size)
        if (pool.isEmpty()) return Result.EmptyPool
        val u = firstComponent(raw.values.toList(), vectors.dim)
        fun finish(x: DoubleArray): DoubleArray? {
            val p = if (u == null) 0.0 else dot(x, u)
            val y = DoubleArray(x.size) { x[it] - p * (u?.get(it) ?: 0.0) }
            val n = sqrt(dot(y, y))
            return if (n == 0.0) null else DoubleArray(y.size) { y[it] / n }
        }
        val q = finish(q0) ?: return Result.NoWords(questionLemmas.size)

        val questionSet = questionLemmas.toSet()
        val nounCache = HashMap<String, Boolean>()
        fun noun(l: String): Boolean = nounCache.getOrPut(l) {
            val a = dictionary.analyze(l)
            a.isNotEmpty() && a.all { it.partOfSpeech == "NOUN" } && l !in Anchors.GENERIC_NOUNS
        }
        val questionNouns = questionSet.filter { noun(it) && vec(it) != null }

        var best: Result.Best? = null
        var above = 0
        var withSpectrum = 0
        for (r in pool.sortedBy { it.id }) {
            val lemmas = recordLemmas[r.id] ?: lemmasOf(r.content)
            val spec = (raw[r.id] ?: spectrum(lemmas))?.let { finish(it) } ?: continue
            withSpectrum++
            var score = dot(q, spec)
            var pair: Pair<String, String>? = null
            val recordNouns = lemmas.toSet().filter { it !in questionSet && noun(it) && vec(it) != null }
            loop@ for (a in questionNouns) for (c in recordNouns) {
                if (cosine(vec(a)!!, vec(c)!!) >= NOUN_CLOSE) { pair = a to c; break@loop }
            }
            if (pair != null) score += NOUN_BONUS
            if (score >= THRESHOLD) above++
            if (best == null || score > best.score) best = Result.Best(r, score, pair, 0, pool.size)
        }
        if (withSpectrum == 0) return Result.NoSpectra(pool.size)
        return best!!.copy(above = above)
    }

    /**
     * Строка «Подробно». Печатается на каждом ходе, и когда ничего не нашлось:
     * «нашёл бы», «лучшее ниже порога», «нечем искать» и «искать не в чем»
     * лечатся разным. [free] — сколько мест круга осталось после окон слов:
     * окно смысла садилось бы только на них.
     */
    fun line(result: Result, free: Int, millis: Long): String {
        val seats = if (free > 0) "мест свободно $free" else "мест нет — окно молчало бы"
        val tail = " · $seats · $millis мс"
        return "Смысл (модели не подаётся): " + when (result) {
            is Result.NoWords -> "в реплике нет слов с вектором (значимых слов ${result.words})$tail"
            Result.EmptyPool -> "искать не в чем — все записи в круге или за заборами$tail"
            is Result.NoSpectra -> "ни у одной из ${result.pool} записей пула нет слов с вектором$tail"
            is Result.Best -> {
                val text = result.record.content.replace(Regex("\\s+"), " ").let { if (it.length > 80) it.take(80) + "…" else it }
                val nouns = result.nouns?.let { " · общее: ${it.first} → ${it.second}" } ?: ""
                val head = if (result.found) "нашёл бы «$text» (№${result.record.id})"
                else "лучшее ниже порога — «$text» (№${result.record.id})"
                head + " · близость ${"%.2f".format(result.score)} при пороге ${"%.2f".format(THRESHOLD)}$nouns" +
                    " · выше порога ${result.above} из ${result.pool}$tail"
            }
        }
    }

    /**
     * Весь прибор одним вызовом — для экрана: пул за заборами ([eligible]),
     * поиск ([probe]) и строка ([line]) со временем счёта. [all] — все записи
     * памяти; [taken] — уже усаженные кругом и портретом; [excludedTexts] —
     * реплики ленты и текущая; [free] — свободные места круга. Нет векторов
     * или словаря — [offLine]; сбой — словами, ход от прибора не зависит.
     */
    fun meter(
        question: String,
        all: List<Sticker>,
        taken: Set<Long>,
        free: Int,
        excludedTexts: List<String>,
        address: Coordinates.Address,
        retell: RetellTable?,
        vectors: MeaningVectors?,
        dictionary: LemmaDictionary?,
    ): String {
        if (vectors == null || dictionary == null) return offLine(vectors != null, dictionary != null)
        return try {
            val t0 = System.nanoTime()
            val raw = excludedTexts.filter { it.isNotBlank() }.toSet()
            val exact = raw.mapTo(HashSet()) { normalizeExact(it) }
            val pool = all.filter { eligible(it, raw, exact, taken, address, retell) }
            val result = probe(question, all, pool, vectors, dictionary)
            line(result, free, (System.nanoTime() - t0) / 1_000_000)
        } catch (e: Exception) {
            "Смысл (модели не подаётся): сбой — ${e.javaClass.simpleName}"
        }
    }

    /**
     * Строка, когда считать нечем. Причина загрузки — в строках «Векторы
     * смысла:» и «Словарь начальных форм:» той же группы.
     */
    fun offLine(vectorsLoaded: Boolean, dictionaryLoaded: Boolean): String =
        "Смысл (модели не подаётся): не считаю — " + listOfNotNull(
            if (vectorsLoaded) null else "векторы смысла не загружены",
            if (dictionaryLoaded) null else "словарь начальных форм не загружен",
        ).joinToString(", ")

    /**
     * Первая главная составляющая набора [rows] (без вычитания среднего) —
     * степенным методом; null — набор пуст или весь нулевой.
     */
    private fun firstComponent(rows: List<DoubleArray>, dim: Int): DoubleArray? {
        if (rows.isEmpty()) return null
        var u = DoubleArray(dim) { 1.0 / sqrt(dim.toDouble()) }
        repeat(PC_STEPS) {
            val next = DoubleArray(dim)
            for (r in rows) {
                val p = dot(r, u)
                for (i in 0 until dim) next[i] += p * r[i]
            }
            val n = sqrt(dot(next, next))
            if (n == 0.0) return null
            u = DoubleArray(dim) { next[it] / n }
        }
        return u
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa == 0.0 || bb == 0.0) 0.0 else ab / sqrt(aa * bb)
    }
}
