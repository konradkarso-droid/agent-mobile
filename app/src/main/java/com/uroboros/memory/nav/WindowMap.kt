package com.uroboros.memory.nav

import com.uroboros.memory.MIN_WORD_LENGTH
import com.uroboros.memory.STOP_WORDS
import com.uroboros.memory.SentenceKind
import com.uroboros.memory.Sentences
import com.uroboros.util.TextFold
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Карта окна — о чём идёт разговор: слова-темы реплик владельца всего
 * нынешнего разговора, сжатые словарём до начальных форм, с весом по
 * давности. Чистая логика: ни базы, ни Android. Считается из ленты на каждом
 * ходе, не хранится — лента и так лежит на диске.
 *
 * ЗАЧЕМ. Следующая реплика должна опираться на разговор целиком, а не на одну
 * предыдущую. Короткое «А белки?» своих слов для поиска почти не несёт, а тема
 * ясна из прошлых ходов. Окно темы круга дольменов и любопытство
 * (memory.DolmenCircle.themeWords) ищут по карте ([searchWords]): определение
 * темы разговора одно на весь код.
 *
 * РАЗГОВОР — эпизод по правилу [Episodes]: тишина дольше часа начинает новый.
 * Не кнопка и не вся лента: в чате другого собеседника кнопки нет, а тема
 * вчерашнего разговора сегодняшний не держит.
 *
 * ТЕМА — СЛОВА ВЛАДЕЛЬЦА. Слова агента в карту не идут: модель вставляет
 * чужие языки и выдуманные слова, и тема, набранная из них, искала бы
 * выдумку. Сказанное агентом нужно для другого — сверки повторов, — и это
 * отдельная работа.
 *
 * ЧТО ТАКОЕ СЛОВО-ТЕМА. Существительное по словарю — правило темы портрета
 * ([Portrait.nounLemmas]): глаголы просьб («расскажи», «перечисли») — не
 * предмет разговора. Двузначное слово держится всеми формами одной единицей
 * («белка/белок»): выбрать между ними словарь не может. Слово с разбором
 * «наречие» или полной формы прилагательного темой не бывает: «потом» — не
 * «пот», «голубой» — не существительное; цена — «мастерская» и «столовая»
 * тоже не тема. Краткое прилагательное слово не снимает: «пол» — ещё и
 * «полый», а это почти всегда «пол». Незнакомое словарю
 * слово от [MIN_WORD_LENGTH] букв остаётся как написано: имя, опечатка,
 * термин несут смысл. Предложения-обычаи («Привет.») не смотрятся. Без
 * словаря — значимые слова как написаны, как у окна темы.
 *
 * КАК РЕПЛИКА СВЯЗАНА С КАРТОЙ ([Footing]) — по словам, и только:
 *  - у реплики нет своих слов-тем — тема только на карте;
 *  - свои слова есть, и хоть одно на карте — общее слово с темой;
 *  - на карте нет ни одного слова — связывать не с чем;
 *  - свои слова есть, и ни одного на карте — общих слов нет.
 *
 * «ОБЩИХ СЛОВ НЕТ» — НЕ СМЕНА ТЕМЫ. Чаще реплика продолжает разговор по
 * смыслу («Грубишь», «Только что сказал»), отвечает на слова агента («Про
 * черепаху правильно уточнил») или задаёт соседний вопрос («Кто я?» после
 * «Кто ты?»). Решать по этому признаку, что старая тема кончилась, нельзя:
 * лишнее место темы в ответе отсеет отбор, а потерянную тему не вернёт
 * никто. Смену темы словами не поймать — нужен смысл.
 *
 * ЧЕГО НЕ УМЕЕТ: тема из слов, а не из смысла — «струг» и «рубанок» разные;
 * местоимение («расскажи о нём») на тему не указывает; смены темы не видит
 * (см. выше); у первого хода разговора карты нет.
 */
object WindowMap {

    /**
     * Через сколько ходов вес слова падает вдвое. Объявленное число, не
     * подобранное: тема живёт несколько ходов, но уступает свежей. Перепроверять
     * по строке «Карта окна:».
     */
    const val HALF_LIFE_TURNS = 2.0

    /** Сколько слов карты показывать в строке. */
    const val LINE_TOP = 6

    /** Ход ленты: реплика владельца и когда сказана (null — время не известно). */
    data class Turn(val question: String, val at: Long?)

    /**
     * Слово-тема: начальные формы одного написанного слова, вес и [form] —
     * самая частая написанная форма (приведённая, TextFold). Ищется в памяти
     * форма, а не начальная: поиск идёт по написанному (лестница
     * HourglassMemory.ladder укорачивает слово, «белки» найдёт и «белка»).
     */
    data class Word(val lemmas: Set<String>, val weight: Double, val form: String = lemmas.first()) {
        val label: String get() = lemmas.sorted().joinToString("/")
    }

    enum class Footing {
        /** Прошлых ходов в разговоре нет — карты нет. */
        NO_MAP,

        /** У реплики нет своих слов-тем — тема только на карте. */
        MAP,

        /** Своё слово реплики есть на карте — общее слово с темой. */
        CONTINUES,

        /** Свои слова есть, на карте ни одного. Не смена темы — см. описание объекта. */
        NO_COMMON,

        /** Ходы были, но слов-тем в них нет — связывать не с чем. */
        EMPTY_MAP,
    }

    data class Result(
        val words: List<Word>,
        val turns: Int,
        val footing: Footing,
        val own: List<Set<String>>,
        val dictionary: Boolean,
    )

    /**
     * Слова-темы текста — по единице на написанное слово, в порядке появления,
     * без повторов.
     */
    fun topicWords(text: String, dictionary: LemmaDictionary?): List<Set<String>> =
        written(text, dictionary).map { it.first }

    /** Слова-темы текста вместе с тем, как каждое написано. */
    private fun written(text: String, dictionary: LemmaDictionary?): List<Pair<Set<String>, String>> {
        val out = LinkedHashMap<String, Pair<Set<String>, String>>()
        for (sentence in Sentences.split(text)) {
            if (SentenceKind.of(sentence).kind == SentenceKind.Kind.SOCIAL) continue
            for (raw in WORD_BREAK.split(TextFold.fold(sentence))) {
                val word = raw.trim('-')
                if (word.isEmpty() || word.any { it.isDigit() }) continue
                val unit = unitOf(word, dictionary) ?: continue
                out.putIfAbsent(unit.sorted().joinToString("/"), unit to word)
            }
        }
        return out.values.toList()
    }

    private fun unitOf(word: String, dictionary: LemmaDictionary?): Set<String>? {
        if (dictionary == null || !dictionary.knows(word)) {
            return if (word.length >= MIN_WORD_LENGTH && word !in STOP_WORDS) setOf(word) else null
        }
        // Наречие или прилагательное с разбором «существительное» («потом» —
        // и «пот», «голубой» и «красное» — и существительные) почти всегда
        // наречие или прилагательное; темой оно сделало бы «А что было
        // потом?» сменой темы на «пот».
        if (dictionary.analyze(word).any { it.partOfSpeech in NOT_TOPIC }) return null
        return Portrait.nounLemmas(word, dictionary).takeIf { it.isNotEmpty() }
    }

    /**
     * Карта разговора и как с ней связана реплика [reply].
     *
     * @param ribbon ходы ленты до этой реплики, от старых к новым.
     * @param now когда сказана реплика — для границы разговора.
     */
    fun of(ribbon: List<Turn>, reply: String, now: Long, dictionary: LemmaDictionary?): Result {
        val episode = episodeOf(ribbon, now)
        // Ключ — набор начальных форм; порядок вставки — порядок первого
        // появления, он и решает при равном весе.
        val weights = LinkedHashMap<String, Pair<Set<String>, Double>>()
        val forms = HashMap<String, HashMap<String, Int>>()
        for ((index, turn) in episode.withIndex()) {
            val ago = episode.size - 1 - index
            val w = 0.5.pow(ago / HALF_LIFE_TURNS)
            for ((unit, word) in written(turn.question, dictionary)) {
                val key = unit.sorted().joinToString("/")
                val prev = weights[key]?.second ?: 0.0
                weights[key] = unit to prev + w
                val f = forms.getOrPut(key) { HashMap() }
                f[word] = (f[word] ?: 0) + 1
            }
        }
        val words = weights.entries
            .map { (key, value) ->
                val form = forms[key].orEmpty().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .first().key
                Word(value.first, value.second, form)
            }
            .sortedByDescending { it.weight }
        val own = topicWords(reply, dictionary)
        val onMap = words.flatMapTo(HashSet()) { it.lemmas }
        val footing = when {
            episode.isEmpty() -> Footing.NO_MAP
            words.isEmpty() -> Footing.EMPTY_MAP
            own.isEmpty() -> Footing.MAP
            own.any { unit -> unit.any { it in onMap } } -> Footing.CONTINUES
            else -> Footing.NO_COMMON
        }
        return Result(words, episode.size, footing, own, dictionary != null)
    }

    /**
     * Слова окна темы для поиска в памяти: написанные формы самых тяжёлых
     * слов карты, без тех, что есть в самой реплике, — их ищет окно вопроса,
     * а слитые в один запрос два набора уронили бы долю вопроса (см.
     * memory.DolmenCircle.themeWords). Не больше [limit].
     */
    fun searchWords(result: Result, limit: Int): List<String> {
        val own = result.own.flatMapTo(HashSet()) { it }
        return result.words.filter { w -> w.lemmas.none { it in own } }.take(limit).map { it.form }
    }

    /**
     * Ходы ленты, что в одном разговоре с репликой, сказанной в [now]; ходы
     * без времени разговор не делят. Пустые реплики (агент писал первым)
     * пропускаются.
     */
    fun episodeOf(ribbon: List<Turn>, now: Long): List<Turn> {
        if (ribbon.isEmpty()) return emptyList()
        val numbers = Episodes.number(ribbon.map { Episodes.Point(it.at) } + Episodes.Point(now))
        val current = numbers.last()
        return ribbon.filterIndexed { i, turn -> numbers[i] == current && turn.question.isNotBlank() }
    }

    /** Строка «Карта окна:». Печатается всегда: молчащая неотличима от сломанной. */
    fun meterLine(result: Result?, millis: Long?, failure: String? = null): String {
        val head = "Карта окна: "
        if (result == null) return head + "не считалась — ${failure ?: "нет данных"}"
        val tail = " · " + (if (result.dictionary) "словарь есть" else "словаря нет — слова как написаны") +
            (millis?.let { " · $it мс" } ?: "")
        if (result.footing == Footing.NO_MAP) return head + "первый ход разговора — карты нет" + tail
        val topic = if (result.words.isEmpty()) "слов-тем нет" else
            result.words.take(LINE_TOP).joinToString(", ") { "${it.label} ${weight(it.weight)}" }
        val own = result.own.joinToString(", ") { it.sorted().joinToString("/") }
        val footing = when (result.footing) {
            Footing.MAP -> "у реплики своих слов нет — тема только на карте"
            Footing.CONTINUES -> "общее слово с темой ($own)"
            Footing.NO_COMMON -> "общих слов с картой нет ($own)"
            Footing.EMPTY_MAP -> "на карте слов нет"
            Footing.NO_MAP -> ""
        }
        return "${head}ходов ${result.turns} · тема: $topic · $footing$tail"
    }

    private fun weight(w: Double): String = ((w * 10).roundToInt() / 10.0).toString()

    private val WORD_BREAK = Regex("[^\\p{L}\\p{N}-]+")

    /** Части речи, разбор которыми снимает слово с темы (см. описание объекта). */
    private val NOT_TOPIC = setOf("ADVB", "ADJF")
}
