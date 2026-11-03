package com.uroboros.memory.nav

import com.uroboros.memory.Sentences
import com.uroboros.util.TextFold

/**
 * Якоря вопроса: имена и предметные слова, по которым окно якорей ищет записи
 * раньше окна вопроса (memory.HourglassMemory.getContextWithSummary). Чистый
 * объект: слова на входе, якоря на выходе.
 *
 * ЗАЧЕМ. Окно вопроса берёт запись, только если в ней есть половина значимых
 * слов вопроса. На длинной реплике о Клоде и Praxis нужные записи совпадали
 * двумя словами из шести — «Клода» и «Praxis» — и отсеивались как узкие, а
 * проходили обрывки, повторявшие слова-связки («разговоры», «вспомнить»).
 * Имя и предмет держат тему разговора сильнее остальных слов, поэтому запись
 * с якорем проходит без доли слов вопроса, а остальные слова только решают
 * порядок среди таких записей (вес — по редкости слова в памяти).
 *
 * В ХОЛОДЕ ТОЖЕ. Якоря ищутся и в горячих слоях (запись встаёт первой в окно
 * вопроса), и в холодных (первой в окно холода) — всегда, без особых слов в
 * вопросе. Давнее чаще всего спрашивают именем или предметом («что мы
 * говорили с Praxis»), а окно холода по словам вопроса на такой реплике
 * упирается в ту же долю слов. Мест холоду якорь не прибавляет: он решает,
 * кто сядет на место холода, а не сколько их.
 *
 * ВИДЫ ЯКОРЯ:
 *  - имя — слово, у которого все разборы словаря с пометкой имени, фамилии,
 *    отчества или места («Клод», «Москве»), а если пометка есть не у всех
 *    («Вера» — и имя, и вера) — только с заглавной не в начале предложения;
 *    незнакомое словарю слово с заглавной не в начале предложения
 *    («Пракс»); латинское слово («Praxis»);
 *  - предмет — слово, у которого все разборы словаря — существительные, кроме
 *    слов разговора о самом разговоре ([GENERIC_NOUNS]: «разговор», «память»,
 *    «вопрос»…). Слово, которое бывает и другой частью речи («стекло» — и
 *    глагол «стечь», «после» — и предлог), предметом не считается: порядка
 *    разборов по частоте у словаря нет, и угадать, что имелось в виду, нечем.
 *
 * ПРИСТАВКА ПОИСКА ([Anchor.prefix]). Для знакомого словарю слова — основа
 * лексемы, общее начало всех её форм (LemmaDictionary.stemOf: «рубанком» и
 * «рубанок» → «рубан»): так находятся все формы, и беглая гласная не теряет
 * запись. Для незнакомого — слово без падежного окончания («Пракса» →
 * «пракс»). Короче [MIN_PREFIX] букв приставка не становится, короткая основа
 * ищется словом как написано («кота», «Сашу», «сны»).
 *
 * СОВПАДЕНИЕ ([hits]). Приставка должна стоять в начале слова записи, а
 * короткое слово (короче [MIN_PREFIX]) — совпасть со словом записи целиком:
 * иначе «сны» находились бы в «ясны», «кот» — в «который».
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - без словаря (не загружен) — только латинские слова: ни предметов, ни имён
 *    кириллицей; окно вопроса тогда работает как прежде;
 *  - имя в начале предложения, незнакомое словарю, — не якорь («Клод сказал…»
 *    в начале реплики): по заглавной его не отличить от любого первого слова;
 *  - запись с другой формой короткого слова не находит: «сны» не найдут «сон»;
 *  - смысла не знает: «код» и «программа» — разные якоря, синонимы не находятся;
 *  - существительное с омонимом другой части речи («печь», «стали») теряет;
 *  - чисел не берёт.
 */
object Anchors {

    enum class Kind { NAME, THING }

    /** Якорь: слово как в вопросе (приведённое), приставка поиска и вид. */
    data class Anchor(val word: String, val prefix: String, val kind: Kind)

    /** Вес вида в порядке записей. Объявленные числа, не подобранные: имя важнее предмета. */
    fun weight(kind: Kind): Int = when (kind) {
        Kind.NAME -> 3
        Kind.THING -> 1
    }

    /**
     * Существительные разговора о самом разговоре: есть почти в любой реплике
     * владельца агенту и тему не держат. Начальные формы; объявленный список,
     * пополняется, когда слово покажет себя мусором в строке круга.
     */
    val GENERIC_NOUNS: Set<String> = setOf(
        "разговор", "память", "вопрос", "слово", "ответ", "раз", "день", "время", "человек", "дело",
        "агент", "запись", "тема", "мысль", "вещь", "сторона", "случай", "часть", "пример", "проблема",
        "попытка", "смысл", "способ", "ход", "сообщение", "текст", "ощущение", "затруднение", "просьба",
    )

    /** Падежные окончания для слова, незнакомого словарю. Те же, что у порчи слов (llm.WordDamage). */
    private val CASE_ENDINGS = listOf(
        "ами", "ями", "ом", "ем", "ой", "ей", "ою", "ов", "ев", "ам", "ям", "ах", "ях",
        "а", "я", "у", "ю", "е", "ы", "и",
    )

    /** Объявленные длины, не подобранные: короче приставка совпадает с чем угодно, короче слово — не якорь. */
    private const val MIN_PREFIX = 4
    private const val MIN_NAME = 3
    private const val MIN_THING = 3

    private val TOKEN = Regex("[\\p{L}][\\p{L}-]*")
    private val LATIN = Regex("[A-Za-z][A-Za-z-]*")
    private val NAME_TAGS = setOf("Name", "Surn", "Patr", "Geox")

    /** Якоря реплики [query], по порядку и без повторов; [dictionary] null — словарь не загружен. */
    fun of(query: String, dictionary: LemmaDictionary?): List<Anchor> {
        val out = LinkedHashMap<String, Anchor>()
        for (sentence in Sentences.split(query)) {
            for ((i, m) in TOKEN.findAll(sentence).withIndex()) {
                val raw = m.value
                val word = TextFold.fold(raw)
                if (word in out) continue
                anchorOf(raw, word, firstInSentence = i == 0, dictionary)?.let { out[word] = it }
            }
        }
        return out.values.toList()
    }

    private fun anchorOf(raw: String, word: String, firstInSentence: Boolean, dictionary: LemmaDictionary?): Anchor? {
        if (LATIN.matches(raw)) {
            return if (raw.length >= MIN_NAME) Anchor(word, word, Kind.NAME) else null
        }
        dictionary ?: return null
        val capital = raw.first().isUpperCase() && !firstInSentence
        val analyses = dictionary.analyze(word)
        if (analyses.isEmpty()) {
            return if (capital && word.length >= MIN_NAME) Anchor(word, unknownPrefix(word), Kind.NAME) else null
        }
        val named = analyses.filter { a -> a.tags.any { it in NAME_TAGS } }
        val onlyName = named.size == analyses.size
        if (named.isNotEmpty() && (onlyName || capital) && word.length >= MIN_NAME) {
            return Anchor(word, knownPrefix(word, named.first().lemma, dictionary), Kind.NAME)
        }
        if (word.length < MIN_THING || analyses.any { it.partOfSpeech != "NOUN" }) return null
        val noun = analyses.first()
        if (analyses.any { it.lemma in GENERIC_NOUNS }) return null
        return Anchor(word, knownPrefix(word, noun.lemma, dictionary), Kind.THING)
    }

    /** Основа лексемы; короче [MIN_PREFIX] или не нашлась — слово целиком. */
    private fun knownPrefix(word: String, lemma: String, dictionary: LemmaDictionary): String {
        val stem = dictionary.stemOf(word, lemma)
        return if (stem != null && stem.length >= MIN_PREFIX && word.startsWith(stem)) stem else word
    }

    /** Слово без падежного окончания, если остаётся не меньше [MIN_PREFIX] букв. */
    private fun unknownPrefix(word: String): String {
        for (e in CASE_ENDINGS) {
            if (word.endsWith(e) && word.length - e.length >= MIN_PREFIX) return word.dropLast(e.length)
        }
        return word
    }

    /** Есть ли якорь [anchor] в тексте [folded] (вид util.TextFold) — правило совпадения выше. */
    fun hits(anchor: Anchor, folded: String): Boolean {
        val p = anchor.prefix
        var from = 0
        while (true) {
            val at = folded.indexOf(p, from)
            if (at < 0) return false
            val end = at + p.length
            val atStart = at == 0 || !folded[at - 1].isLetter()
            val whole = p.length >= MIN_PREFIX || end == folded.length || !folded[end].isLetter()
            if (atStart && whole) return true
            from = at + 1
        }
    }

    /** Сколько записей с якорем нашлось в холоде и сколько из них заняли места окна холода. */
    data class ColdPart(val found: Int, val seated: Int)

    /**
     * Часть строки «Круг:»: якоря словами, сколько записей нашли и сколько
     * мест заняли — в горячих слоях и отдельно в холоде. Горячие места — из
     * мест окна вопроса, холодные — из мест окна холода: записи якорей садятся
     * туда первыми.
     *
     * Холод — отдельным числом, а не в общей сумме: слитое число спрятало бы,
     * что холод с якорем вытесняет из окна холода запись по словам вопроса, и
     * что якоря в холоде не нашли ничего. [cold] null — в холоде нет ни одной
     * записи, и это сказано словами, а не нулём.
     */
    fun meterPart(anchors: List<Anchor>, found: Int, seated: Int, dictionaryLoaded: Boolean, cold: ColdPart? = null): String =
        if (anchors.isEmpty()) if (dictionaryLoaded) "якоря: нет" else "якоря: нет (словарь не загружен — ищу только латиницу)"
        else "якоря (${anchors.joinToString(", ") { it.word + if (it.kind == Kind.NAME) " — имя" else "" }}) — " +
            "нашли $found, мест $seated · " +
            (if (cold == null) "в холоде: слой пуст" else "в холоде нашли ${cold.found}, мест ${cold.seated}")
}
