package com.uroboros.memory

object RiskTrigger {

    private val NEGATION_MARKERS = setOf(
        "не", "нет", "никогда", "ни", "невозможно", "нельзя"
    )

    private val UNCERTAINTY_MARKERS = setOf(
        "наверное", "возможно", "кажется", "вроде", "не уверен",
        "не уверена", "может быть", "предположительно", "скорее всего"
    )

    private val WORD_NUMBERS = mapOf(
        "ноль" to "0", "один" to "1", "одна" to "1", "одно" to "1",
        "два" to "2", "две" to "2", "три" to "3", "четыре" to "4",
        "пять" to "5", "шесть" to "6", "семь" to "7", "восемь" to "8",
        "девять" to "9", "десять" to "10", "одиннадцать" to "11",
        "двенадцать" to "12", "тринадцать" to "13", "четырнадцать" to "14",
        "пятнадцать" to "15", "шестнадцать" to "16", "семнадцать" to "17",
        "восемнадцать" to "18", "девятнадцать" to "19", "двадцать" to "20",
        "тридцать" to "30", "сорок" to "40", "пятьдесят" to "50",
        "шестьдесят" to "60", "семьдесят" to "70", "восемьдесят" to "80",
        "девяносто" to "90", "сто" to "100"
    )

    // Ordered longest-first so the longest matching suffix is stripped.
    // Deliberately crude (no dictionary, no morphology) — this is cheap
    // friction toward the reviewPending checkpoint, not a linguistic tool.
    // 2026-08-18: added "ия" alongside the existing "ию" — without it, the
    // accusative ("-ию") and nominative ("-ия") forms of the same noun
    // (e.g. "функцию"/"функция") stemmed to different roots, silently killing
    // Jaccard overlap between them (found via item 9's QueryUrgencyClassifier
    // using RiskTrigger.textSimilarity on task descriptions vs. queries).
    private val SUFFIXES = listOf(
        "иями",
        "ями", "ами", "ого", "его", "ому", "ему", "ыми", "ими",
        "ев", "ов", "ам", "ям", "ах", "ях", "ом", "ем", "ей",
        "юю", "ая", "яя", "ое", "ее", "ых", "их", "ию", "ия", "ья", "ье", "ий", "ый",
        "ы", "и", "а", "я", "о", "е", "у", "ю", "й", "ь"
    )

    private const val MIN_ROOT_LENGTH = 3

    // Порог схожести, при котором две записи вообще сравниваются на
    // противоречие. Подтверждён замером на восьми заведомых противоречиях,
    // шести заведомо безобидных парах и всех парах живой базы: 0.5 —
    // наибольшее значение, при котором ложных срабатываний ноль и на
    // выдуманных парах, и на живых. Ниже 0.5 ложные появляются сразу и
    // именно на живом материале: логи прогонов («успех за 3 итераций» и
    // «успех за 5 итераций») начинают считаться противоречащими, хотя это
    // разные прогоны, а не спорные утверждения.
    //
    // Чего порог стоит: пересказ теми же по смыслу, но другими словами
    // ("у паука восемь ног" / "пауки имеют четыре ноги") даёт схожесть
    // около 0.33 и до сравнения чисел не доходит. Опустить порог ради
    // таких пар нельзя — ложные начинаются раньше, чем ловятся эти.
    // Формальными средствами пересказ не берётся вовсе.
    private const val CONTRADICTION_JACCARD_THRESHOLD = 0.5
    private const val LONG_CONTENT_CHARS = 240

    // Порог схожести для распознавания "той же самой" ошибки компилятора
    // (item 7a: лавинообразный расход энергии при зацикливании).
    private const val REPEATED_ERROR_JACCARD_THRESHOLD = 0.7

    data class Decision(
        val shouldReview: Boolean,
        val reasons: List<String>,
        val contradictionCandidateId: Long? = null
    )

    /** За что правило противоречия зацепилось в паре текстов. */
    enum class MarkKind {
        /** Отрицание есть у одной стороны и отсутствует у другой. */
        NEGATION,

        /** Числа сторон разошлись. */
        NUMBER,
    }

    /**
     * Один признак спора, найденный в паре текстов, со словами, которыми он
     * выражен.
     *
     * ЗАЧЕМ СЛОВА, А НЕ ПРОСТО ВИД ПРИЗНАКА. Показ обязан уметь выделить в
     * самой фразе то, за что зацепилось правило. Считать это на экране заново
     * значило бы завести вторую копию правила: она разойдётся с первой, и не
     * будет видно, какая врёт.
     *
     * СТОРОНЫ НЕ СИММЕТРИЧНЫ, И ПУСТАЯ СТОРОНА — ЗАКОННЫЙ ИСХОД. У [NEGATION]
     * слова есть ровно у одной стороны: признак в том и состоит, что у второй
     * отрицания НЕТ, и выделять там нечего. У [NUMBER] пустой может оказаться
     * любая из двух, когда числа одной стороны — подмножество чисел другой.
     *
     * ЧЕГО ЗДЕСЬ НЕТ. Мест в тексте. Правило работает на множествах слов:
     * порядок и смещения теряются на разборе, и восстановить их нечем.
     * Вхождение ищет тот, кто показывает, и отсюда две его границы: слово,
     * встречающееся в тексте несколько раз, выделится везде, а искать надо не
     * различая регистра — слова здесь приведены к нижнему.
     *
     * @property first слова со стороны первого текста, как они стоят в нём
     *                 (для числительных словами — само слово, а не цифра).
     * @property second то же со стороны второго.
     */
    data class ContradictionMark(
        val kind: MarkKind,
        val first: Set<String>,
        val second: Set<String>,
    )

    fun evaluate(candidate: Sticker, sameTagHotStickers: List<Sticker>): Decision {
        val reasons = mutableListOf<String>()

        val contradiction = findContradiction(candidate, sameTagHotStickers)
        if (contradiction != null) {
            reasons += "contradiction"
            return Decision(
                shouldReview = true,
                reasons = reasons,
                contradictionCandidateId = contradiction.id
            )
        }

        var lowWeightCount = 0

        if (importanceOf(candidate.importance) == Importance.HIGH) {
            lowWeightCount++
            reasons += "high_importance"
        }

        if (hasUncertaintyMarker(candidate.content)) {
            lowWeightCount++
            reasons += "uncertainty_marker"
        }

        if (candidate.content.length >= LONG_CONTENT_CHARS) {
            lowWeightCount++
            reasons += "long_content"
        }

        val trigger = lowWeightCount >= 2
        return Decision(
            shouldReview = trigger,
            reasons = if (trigger) reasons else emptyList()
        )
    }

    /**
     * Считает две ошибки компилятора "той же самой" по текстовому сходству
     * (переиспользует ту же стемминг+Jaccard инфраструктуру, что и поиск
     * противоречий, только в обратную сторону: не различие, а повтор).
     * Используется item 7a для лавинообразного расхода энергии при зацикливании.
     */
    fun isRepeatedError(currentError: String, previousError: String): Boolean {
        val a = stemmedTokenize(currentError)
        val b = stemmedTokenize(previousError)
        return jaccard(a, b) >= REPEATED_ERROR_JACCARD_THRESHOLD
    }

    /**
     * Общего назначения коэффициент текстовой схожести (0.0–1.0), без встроенного
     * порога — вызывающий код сам решает, что считать "похоже". Переиспользует ту же
     * стемминг+Jaccard инфраструктуру, что isRepeatedError/findContradiction.
     *
     * Item 9 (2026-08-18): используется классификатором light/heavy для сравнения
     * входящего запроса пользователя с (текущей ошибкой + описанием подзадачи) на
     * швах TOTE-цикла — сюда сознательно НЕ зашит собственный порог, чтобы
     * RiskTrigger оставался общей утилитой, не завязанной на конкретный домен.
     */
    fun textSimilarity(a: String, b: String): Double =
        jaccard(stemmedTokenize(a), stemmedTokenize(b))

    /**
     * Важность записи из строки, лежащей в базе. Незнакомое или пустое
     * значение — [Importance.LOW], а не исключение.
     *
     * Поле строковое, и в нём может оказаться что угодно: значение из старой
     * схемы, опечатка, чужой импорт. Разворачивать его через
     * Importance.valueOf() нельзя: тот бросает на любом незнакомом слове, и
     * падение уносит с собой всю оценку записи — хотя важность здесь лишь
     * один из трёх слабых признаков и решающего голоса не имеет.
     * Направление ошибки выбрано в восстановимую сторону: непонятная
     * важность считается низкой, признак не срабатывает, запись сохраняется
     * как обычная.
     *
     * Ровно так же поступают два других места проекта, где та же строка
     * превращается в важность (ранжирование в отборе и importanceRank). Это
     * третий экземпляр одного приёма, и он приведён к тем же двум.
     *
     * Сравнение точное, без приведения регистра и обрезки пробелов: строку
     * пишет код, а не человек, и молчаливое исправление здесь скрыло бы
     * настоящий разнобой в том, кто и как её ставит.
     *
     * ЧТО ЭТО НЕ ЧИНИТ. Признак "high_importance" сегодня не срабатывает
     * никогда, и по другой причине: важность никто не выставляет, у всех
     * записей значение по умолчанию. Здесь исправлено только поведение при
     * мусоре в поле.
     */
    private fun importanceOf(raw: String): Importance =
        Importance.values().firstOrNull { it.name == raw } ?: Importance.LOW

    private fun hasUncertaintyMarker(text: String): Boolean {
        val words = tokenize(text)
        return UNCERTAINTY_MARKERS.any { marker ->
            if (marker.contains(' ')) text.lowercase().contains(marker) else words.contains(marker)
        }
    }

    /**
     * Противоречат ли два текста по тому же правилу, по которому запись
     * встаёт в очередь на проверку.
     *
     * Вынесено наружу, чтобы у правила была ОДНА реализация. Экран разбора
     * сопоставляет записи, уже вытащенные из базы, и без этого предиката ему
     * пришлось бы либо звать проверку по разу на запись, либо завести вторую
     * копию правила рядом. Вторая копия расходится с первой, и не видно,
     * какая врёт.
     *
     * ЧТО ЭТО НЕ ЗНАЧИТ. Предикат отвечает на вопрос о ПАРЕ и ничего не знает
     * о том, как решалось при сохранении. Там противник ищется перебором и
     * поиск останавливается на первом подошедшем (см. [findContradiction]),
     * поэтому две записи могут противоречить друг другу по этому предикату,
     * а бит при сохранении был поднят из-за третьей. Обратное тоже бывает:
     * бит поднимается и вовсе без противника — по двум слабым признакам
     * сразу (см. [evaluate]).
     *
     * ЧЕГО НЕ УМЕЕТ. Правило грубое намеренно: это дешёвое трение к проверке
     * человеком, а не разбор смысла. Пересказ теми же по смыслу, но другими
     * словами не берётся вовсе — до сравнения отрицаний и чисел такая пара не
     * доходит, схожесть ниже порога. Порог и его область — у
     * [CONTRADICTION_JACCARD_THRESHOLD].
     *
     * Записи не сравниваются с самими собой: об этом заботится вызывающий,
     * предикату идентичности записей не видно — он получает только тексты.
     */
    fun contradicts(a: String, b: String): Boolean =
        contradictionMarks(a, b).isNotEmpty()

    /**
     * Те же признаки, по которым отвечает [contradicts], но названные
     * поимённо и со словами, которыми они выражены.
     *
     * ЭТО ТО ЖЕ САМОЕ ПРАВИЛО, А НЕ ВТОРОЕ. [contradicts] спрашивает у него
     * же и отвечает "да", когда список непуст. Двух реализаций нет и заводить
     * их нельзя: разойдясь, они дадут экран, противоречащий очереди.
     *
     * ПУСТОЙ СПИСОК ОЗНАЧАЕТ "НЕ ПРОТИВОРЕЧАТ", и другого смысла у него нет.
     * Обратное тоже верно и нужнее: у пары, признанной спорящей, признаков
     * всегда хотя бы один. Ноль признаков рядом с названным противником —
     * поломка, а не тишина, и показывающий вправе на это опереться.
     *
     * ПОРЯДОК ПРИЗНАКОВ УСТОЙЧИВ: сперва отрицание, потом числа. Экран,
     * перечисляющий их подряд, не должен переставляться от запуска к запуску.
     *
     * ЧЕГО НЕ УМЕЕТ — всё то же, что у [contradicts]: пара сравнивается только
     * после порога схожести, пересказ другими словами не берётся вовсе. Плюс
     * своё: числа сверяются как множества, поэтому названы будут только те,
     * что есть у одной стороны и нет у другой. Общее число, стоящее рядом с
     * разошедшимся, не назовётся, хотя спор может быть именно о нём.
     */
    fun contradictionMarks(a: String, b: String): List<ContradictionMark> {
        val wordsA = stemmedTokenize(a)
        val wordsB = stemmedTokenize(b)
        if (jaccard(wordsA, wordsB) < CONTRADICTION_JACCARD_THRESHOLD) return emptyList()

        val marks = mutableListOf<ContradictionMark>()

        val negationsA = tokenize(a).filterTo(HashSet()) { it in NEGATION_MARKERS }
        val negationsB = tokenize(b).filterTo(HashSet()) { it in NEGATION_MARKERS }
        if (negationsA.isEmpty() != negationsB.isEmpty()) {
            marks += ContradictionMark(MarkKind.NEGATION, negationsA, negationsB)
        }

        // Цифровая запись служит ключом сравнения, слова из текста — ответом
        // показу: "семь" и "7" спорят об одном, а выделить в тексте надо то,
        // что там написано.
        val numbersA = numbersWithSurface(a)
        val numbersB = numbersWithSurface(b)
        if (numbersA.isNotEmpty() && numbersB.isNotEmpty() && numbersA.keys != numbersB.keys) {
            marks += ContradictionMark(
                MarkKind.NUMBER,
                surfacesOf(numbersA, numbersA.keys - numbersB.keys),
                surfacesOf(numbersB, numbersB.keys - numbersA.keys),
            )
        }

        return marks
    }

    private fun surfacesOf(
        numbers: Map<String, Set<String>>,
        keys: Set<String>,
    ): Set<String> = keys.flatMapTo(HashSet()) { numbers.getValue(it) }

    /**
     * Первый противник кандидата в пуле — или null.
     *
     * ПЕРВЫЙ, А НЕ ЕДИНСТВЕННЫЙ: перебор останавливается на подошедшем, и
     * порядок пула задаёт запрос к базе, у которого своей сортировки нет.
     * Значит выбор конкретного противника из нескольких возможных
     * произволен, и опираться на него как на "тот самый" нельзя.
     *
     * Разбор пары вынесен в [contradicts]. Слова кандидата стеммируются
     * заново на каждой паре, а не один раз на весь пул: пул ограничен
     * горячими слоями одного тега, и цена этого — десятки коротких строк на
     * сохранение. Плата за то, что правило живёт в одном месте.
     */
    private fun findContradiction(candidate: Sticker, pool: List<Sticker>): Sticker? {
        for (existing in pool) {
            if (existing.id == candidate.id) continue
            if (contradicts(candidate.content, existing.content)) return existing
        }
        return null
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .toSet()

    // Used only for Jaccard overlap in findContradiction, so Russian case/number
    // endings ("кота" vs "котов") don't artificially suppress the overlap score
    // before negation/number comparison even runs.
    private fun stemmedTokenize(text: String): Set<String> =
        tokenize(text).map { stem(it) }.toSet()

    private fun stem(word: String): String {
        for (suffix in SUFFIXES) {
            if (word.length - suffix.length >= MIN_ROOT_LENGTH && word.endsWith(suffix)) {
                return word.substring(0, word.length - suffix.length)
            }
        }
        return word
    }

    /**
     * Числа из текста: цифровая запись как ключ, написанное в тексте как
     * значение. «семь» и «7» дают один и тот же ключ "7", а под ним лежит то
     * слово, которое в этом тексте стоит.
     *
     * ДВА УРОВНЯ, А НЕ ОДИН, потому что у них разные потребители. Сравнение
     * «то же число или другое» идёт по ключам: иначе «семь» и «7» считались бы
     * разными числами. Выделение в тексте идёт по значениям: искать там «7»,
     * когда написано «семь», не по чему.
     *
     * Числительные словами ищутся ЧЛЕНСТВОМ в разобранных токенах, а не
     * регулярным выражением с `\b`. Причина не стилистическая: в Java `\b`
     * определена через `\w`, а `\w` без флага UNICODE_CHARACTER_CLASS — это
     * [a-zA-Z_0-9]. Между пробелом и русской буквой границы слова поэтому не
     * возникает, и `\bсемь\b` не совпадает НИ С ЧЕМ, даже со строкой «семь».
     * Проверено на JVM 21. Здесь такой ловушки нет по построению: tokenize
     * режет по \p{L}\p{N}, которые юникодны сами по себе.
     *
     * Чего механизм не умеет, чтобы это не выяснялось заново:
     *  - только именительный падеж: «в пяти цветах» числом не считается,
     *    поскольку в карте лежит «пять». Прогон числительного через stem()
     *    закрыл бы часть падежей и молча не закрыл бы остальные («трёх» и
     *    «три» расходятся по букве ё), поэтому карта оставлена как есть;
     *  - составные числительные («двадцать пять») дают два отдельных числа;
     *  - цифры собираются ВСЕ подряд, включая случайные — в тексте ошибки
     *    компилятора сюда попадают адреса и длины. Для сравнения «то же число
     *    или другое» такой набор шумит; отсечь его нечем, пока нет разбора
     *    того, к чему число относится.
     *
     * Вызывающий, contradictionMarks, сравнивает эти ключи только после порога
     * схожести, так что шум из предыдущего пункта до сравнения обычно не
     * доходит.
     *
     * Под одним ключом может оказаться несколько написаний сразу: в тексте,
     * где стоит и «7», и «семь», выделены будут оба.
     */
    private fun numbersWithSurface(text: String): Map<String, Set<String>> {
        val found = mutableMapOf<String, MutableSet<String>>()
        for (match in Regex("\\d+").findAll(text)) {
            found.getOrPut(match.value) { mutableSetOf() } += match.value
        }
        val words = tokenize(text)
        for ((word, digits) in WORD_NUMBERS) {
            if (word in words) found.getOrPut(digits) { mutableSetOf() } += word
        }
        return found
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }
}
