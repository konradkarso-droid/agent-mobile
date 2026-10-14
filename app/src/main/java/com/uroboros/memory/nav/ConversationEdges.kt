package com.uroboros.memory.nav

import com.uroboros.memory.SentenceKind
import com.uroboros.util.TextFold

/**
 * Слова на краях разговоров — прибор для будущего словаря обычая из опыта.
 *
 * ЗАЧЕМ. Словарь обычая (SentenceKind, «ОБЫЧАЙ») объявлен руками, а слово не из
 * него («Сладких снов», «Бывай») делает приветствие утверждением, и такая
 * запись снова идёт в ответ. У обычая есть признак, не зависящий от списка:
 * приветствие стоит первой репликой разговора, прощание — последней.
 * Слово, которое почти всегда стоит на краю, — кандидат в словарь обычая.
 * Пока это только прибор: строка показывает кандидатов и тех из них, кого в
 * словаре нет. Словарь сам не растёт; учить его из опыта — когда прибор
 * покажет слово не из словаря.
 *
 * КАК СЧИТАЕТСЯ. Разговор — лента архива (реплики владельца по порядку), не
 * короче двух реплик: в ленте из одной реплики первая она же последняя, и край
 * от середины не отличить. Слово берётся начальной формой по словарю
 * (LemmaDictionary; все разборы), без словаря — как написано, приведённым
 * (TextFold). На каждую реплику слово считается один раз. Кандидат — слово,
 * встретившееся не меньше [MIN_TIMES] раз и не меньше чем в доле [EDGE_SHARE]
 * случаев в первой или последней реплике ленты.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - разговор — лента архива, а не эпизод по паузе: две встречи в одной
 *    ленте дают один край вместо двух;
 *  - открытая лента не считается — только закрытые;
 *  - край — не смысл: тема, с которой всегда начинают («рубанок»), тоже
 *    станет кандидатом, если с неё начинать почти всегда.
 *
 * ЧИСЛА [MIN_TIMES] и [EDGE_SHARE] объявлены, не подобраны; проверены на одной
 * выгрузке (88 лент): выбрали «привет» и «добрый» и ничего лишнего.
 * Перепроверять по этой же строке.
 *
 * Чистый объект: ни базы, ни Android.
 */
object ConversationEdges {

    /** Сколько раз слово должно встретиться, чтобы о его месте можно было судить. */
    const val MIN_TIMES = 3

    /** Какая доля встреч должна быть на краю разговора. */
    const val EDGE_SHARE = 0.8

    /** Кандидат: слово, сколько раз встретилось и сколько из них на краю; [inVocabulary] — есть ли в словаре обычая. */
    data class Edge(val word: String, val times: Int, val atEdge: Int, val inVocabulary: Boolean)

    private val WORD = Regex("[\\p{L}-]+")

    /**
     * Кандидаты по лентам [ribbons] (в каждой — реплики владельца по порядку),
     * самые частые первыми. [dictionary] — null: слова как написаны.
     */
    fun of(ribbons: List<List<String>>, dictionary: LemmaDictionary?): List<Edge> {
        fun forms(text: String): Set<String> = WORD.findAll(text).flatMapTo(HashSet()) { m ->
            val word = m.value.trim('-')
            val lemmas = dictionary?.lemmas(word).orEmpty()
            if (lemmas.isNotEmpty()) lemmas.asSequence() else sequenceOf(TextFold.fold(word))
        }.filterTo(HashSet()) { it.isNotEmpty() }
        val times = HashMap<String, Int>()
        val edge = HashMap<String, Int>()
        for (ribbon in ribbons) {
            val replies = ribbon.filter { it.isNotBlank() }
            if (replies.size < 2) continue
            replies.forEachIndexed { i, reply ->
                for (form in forms(reply)) {
                    times[form] = (times[form] ?: 0) + 1
                    if (i == 0 || i == replies.lastIndex) edge[form] = (edge[form] ?: 0) + 1
                }
            }
        }
        val vocabulary = (SentenceKind.SOCIAL_MARKS + SentenceKind.SOCIAL_COMPANIONS).flatMapTo(HashSet()) { word ->
            dictionary?.lemmas(word).orEmpty() + word
        }
        return times.entries
            .filter { (word, n) -> n >= MIN_TIMES && (edge[word] ?: 0) >= n * EDGE_SHARE }
            .map { (word, n) -> Edge(word, n, edge[word] ?: 0, word in vocabulary) }
            .sortedWith(compareByDescending<Edge> { it.times }.thenBy { it.word })
    }

    /** Строка «Подробно». */
    fun meterLine(edges: List<Edge>?, ribbons: Int): String {
        if (edges == null) return "Края разговоров: архив ленты не прочитался"
        if (edges.isEmpty()) return "Края разговоров: кандидатов нет (лент $ribbons)"
        val outside = edges.filter { !it.inVocabulary }
        return "Края разговоров: " + edges.joinToString(", ") { "${it.word} ${it.atEdge}/${it.times}" } +
            " · не в словаре обычая: " + (if (outside.isEmpty()) "нет" else outside.joinToString(", ") { it.word }) +
            " (лент $ribbons)"
    }
}
