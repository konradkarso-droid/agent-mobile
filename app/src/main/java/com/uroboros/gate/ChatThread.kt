package com.uroboros.gate

import com.uroboros.memory.nav.Episodes
import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.memory.nav.WindowMap
import com.uroboros.util.TextFold

/**
 * Нить разговора в чате телеграма — выжимка того, чего модель в окне
 * (ChatFeed.window) не видит: что ушло за начало окна и что в группе говорят не
 * агенту. Собирает её код из ленты чата, без модели. Чистая логика.
 *
 * ЗАЧЕМ. Окно чата — хвост ленты в пару тысяч знаков. Ушедшее за его начало для
 * модели не существует, общее группы (кольцо GENERAL) она не читает вовсе, и
 * агент теряет, с кем и о чём идёт разговор.
 *
 * НЕ ХРАНИТСЯ, А ПЕРЕСЧИТЫВАЕТСЯ из ленты чатов на каждом ходе. Лента лежит на
 * диске целиком, окно — лишь её хвост; поэтому нить переживает любой сдвиг
 * окна. Те же строки дают тот же текст слово в слово: голова нити не рвёт
 * совпадение начала запроса (ChatFeed, «СТРОКИ СИСТЕМЫ»), а отдельная копия
 * со временем разошлась бы с лентой. Лента стёрта — нити тоже нет, и это верно.
 *
 * ДВЕ ЧАСТИ — по тому, как часто меняются:
 *  - ГОЛОВА ([head]) — читаемые строки (к агенту, про агента, сомнительные и
 *    свои ответы) перед началом окна, в том же разговоре, что первая строка
 *    окна: кто писал, сколько раз отвечал агент, о чём. Зависит только от
 *    строк перед началом окна, поэтому меняется лишь со скачком окна;
 *  - ХВОСТ ([tail]) — общее группы после последнего ответа агента, а если он в
 *    этом разговоре не отвечал — с начала разговора: сколько, кто, о чём. Свой
 *    на каждом ходе.
 *
 * РАЗГОВОР — эпизод по правилу Episodes (тишина дольше часа начинает новый),
 * как у карты окна экрана.
 *
 * «О ЧЁМ» — слова-темы по правилу карты окна (WindowMap.topicWords:
 * существительные в начальных формах), только из слов людей: слова агента в
 * тему не идут по той же причине, что у карты. Вес — в скольких ходах слово
 * звучало. При равенстве у головы выше то, что прозвучало раньше: конец
 * ушедшего окно чаще продолжает само, а начало разговора не видно ничем,
 * кроме головы; у хвоста — то, что позже: фон — это сказанное только что. Ход у
 * головы — реплики людей между двумя ответами агента, у хвоста — каждая
 * строка (ответов агента там нет). Не по давности, как у карты: голова — о
 * том, что ушло из окна, и тема всего ушедшего важнее его последнего хода — по
 * давности тему, звучавшую весь разговор, вытеснили бы слова одного последнего
 * вопроса. Имена тех, кто пишет, и имена агента темой не считаются: «Вика,
 * поехали» — обращение, а не разговор о вике.
 * Модели слова подаются как слова («звучали чаще»), а не пересказом: это
 * догадка кода по словам, и так она и названа.
 *
 * В ПАМЯТЬ НЕ ИДЁТ. Нить — догадка кода, а не сведения: записью памяти она не
 * становится и судье не подаётся.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - темы — по словам, не по смыслу: смены темы не видит, местоимение темы не несёт;
 *  - интонации нет: грубость, нажим, восклицание сюда не доходят;
 *  - общее группы есть, только если бот его получает: при включённой в
 *    @BotFather приватности в группе хвоста не бывает никогда;
 *  - голова видит не дальше [HEAD_READ] читаемых строк перед окном;
 *  - загрузился словарь между ходами — слова головы один раз станут другими.
 */
object ChatThread {

    /** Объявленные числа, не подобранные: сколько слов-тем и имён подавать модели. */
    const val HEAD_WORDS = 5
    const val TAIL_WORDS = 4
    const val VOICES = 3

    /** Сколько читаемых строк перед окном смотрит голова. Объявленное число, не подобранное. */
    const val HEAD_READ = 200

    /** Кто писал: первые [VOICES] имён по числу строк и сколько ещё имён осталось за ними. */
    data class Voices(val names: List<String>, val more: Int)

    /**
     * Голова: [since] — время первой строки этого разговора перед окном;
     * [agentAnswers] — сколько раз там отвечал агент; [words] — слова-темы.
     */
    data class Head(val since: Long, val voices: Voices, val agentAnswers: Int, val words: List<String>)

    /**
     * Хвост: [lines] — строк общего; [sinceMine] — считано после ответа агента
     * (иначе — с начала разговора).
     */
    data class Tail(val lines: Int, val voices: Voices, val words: List<String>, val sinceMine: Boolean)

    /**
     * Голова нити. [before] — читаемые строки перед началом окна, по порядку;
     * [windowStartAt] — время первой строки окна. null — в этом разговоре
     * перед окном строк людей нет.
     */
    fun head(before: List<ChatFeed.Line>, windowStartAt: Long, dictionary: LemmaDictionary?, agentNames: List<String> = emptyList()): Head? {
        val episode = sameEpisode(before, windowStartAt)
        val people = episode.filter { !it.isAgent }
        if (people.isEmpty()) return null
        val turns = ArrayList<String>()
        val turn = StringBuilder()
        for (line in episode) {
            if (line.isAgent) {
                if (turn.isNotEmpty()) turns += turn.toString()
                turn.setLength(0)
            } else {
                if (turn.isNotEmpty()) turn.append('\n')
                turn.append(line.text)
            }
        }
        if (turn.isNotEmpty()) turns += turn.toString()
        val words = words(turns, notTopics(people, agentNames), dictionary, HEAD_WORDS, laterFirst = false)
        return Head(episode.first().at, voices(people), episode.count { it.isAgent }, words)
    }

    /**
     * Хвост нити. [lines] — строки ленты чата до текущей включительно, по
     * порядку, всех колец. null — общего в этом разговоре после последнего
     * ответа агента нет.
     */
    fun tail(lines: List<ChatFeed.Line>, dictionary: LemmaDictionary?, agentNames: List<String> = emptyList()): Tail? {
        if (lines.size < 2) return null
        val episode = sameEpisode(lines.dropLast(1), lines.last().at)
        val lastMine = episode.indexOfLast { it.isAgent }
        val general = episode.drop(lastMine + 1).filter { !it.isAgent && !it.modelReads }
        if (general.isEmpty()) return null
        val words = words(general.map { it.text }, notTopics(general, agentNames), dictionary, TAIL_WORDS, laterFirst = true)
        return Tail(general.size, voices(general), words, sinceMine = lastMine >= 0)
    }

    /** Голова словами модели, от первого лица. [clock] — время строки «ЧЧ:ММ». */
    fun headText(h: Head, clock: (Long) -> String): String {
        val answered = if (h.agentAnswers > 0) "; я ответил ${h.agentAnswers} ${times(h.agentAnswers)}" else ""
        return "Раньше в этом разговоре, выше реплик, что я вижу (с ${clock(h.since)}): писали ${names(h.voices)}$answered." +
            wordsClause(h.words)
    }

    /** Хвост словами модели, от первого лица. */
    fun tailText(t: Tail): String {
        val since = if (t.sinceMine) "после моего последнего ответа" else "в этом разговоре"
        return "В группе $since ещё ${t.lines} ${messages(t.lines)} не мне: писали ${names(t.voices)}." + wordsClause(t.words)
    }

    /**
     * Строка экрана врат: окно и что из нити ушло модели. [moved] — сдвинулось
     * ли начало окна с прошлого ответа в этом чате; null — прошлого ответа с
     * запуска службы не было. Печатается всегда: молчащая неотличима от сломанной.
     */
    fun meterLine(
        chatName: String,
        window: ChatFeed.Window,
        moved: Boolean?,
        head: Head?,
        tail: Tail?,
        dictionary: Boolean,
        clock: (Long) -> String,
        budgetChars: Int = ChatFeed.BUDGET_CHARS,
    ): String {
        val place = when (moved) {
            null -> "встало"
            true -> "сдвинулось скачком"
            false -> "на месте"
        }
        val before = head?.let { "с ${clock(it.since)}, ${names(it.voices)}" + it.words.joinToString(", ").let { w -> if (w.isEmpty()) "" else "; $w" } }
            ?: "нет — в этом разговоре всё в окне"
        val background = tail?.let { "${it.lines} не мне" + it.words.joinToString(", ").let { w -> if (w.isEmpty()) "" else "; $w" } } ?: "нет"
        val dict = if (dictionary) "" else " · словаря нет — слова как написаны"
        return "Нить ($chatName): окно ${window.chars} из $budgetChars зн., $place · раньше: $before · фон группы: $background$dict"
    }

    // --- Внутреннее ---

    /** Строки [lines] того же разговора, что и момент [at] (правило Episodes). */
    private fun sameEpisode(lines: List<ChatFeed.Line>, at: Long): List<ChatFeed.Line> {
        if (lines.isEmpty()) return emptyList()
        val numbers = Episodes.number(lines.map { Episodes.Point(it.at) } + Episodes.Point(at))
        val current = numbers.last()
        return lines.filterIndexed { i, _ -> numbers[i] == current }
    }

    private fun voices(lines: List<ChatFeed.Line>): Voices {
        val counts = LinkedHashMap<String, Int>()
        for (l in lines) counts[l.signature] = (counts[l.signature] ?: 0) + 1
        // При равном счёте — кто заговорил раньше: порядок вставки сохраняется сортировкой.
        val sorted = counts.entries.sortedByDescending { it.value }.map { it.key }
        return Voices(sorted.take(VOICES), (sorted.size - VOICES).coerceAtLeast(0))
    }

    /**
     * Слова-темы ходов [turns] по весу из шапки («О ЧЁМ»), без слов из [skip].
     * [laterFirst] — при равном весе выше прозвучавшее позже, иначе — раньше.
     */
    private fun words(turns: List<String>, skip: Set<String>, dictionary: LemmaDictionary?, limit: Int, laterFirst: Boolean): List<String> {
        // Ключ — набор начальных форм; значение — в скольких ходах и в каком последнем.
        val seen = LinkedHashMap<String, IntArray>()
        for ((i, text) in turns.withIndex()) {
            for (unit in WindowMap.topicWords(text, dictionary)) {
                if (unit.any { it in skip }) continue
                val key = unit.sorted().joinToString("/")
                val v = seen.getOrPut(key) { intArrayOf(0, 0) }
                v[0]++
                v[1] = i
            }
        }
        return seen.entries
            // Порядок вставки — порядок первого появления: сортировка его сохраняет.
            .sortedWith(compareByDescending<Map.Entry<String, IntArray>> { it.value[0] }.thenByDescending { if (laterFirst) it.value[1] else 0 })
            .take(limit)
            .map { it.key }
    }

    /** Имена пишущих и агента, приведённые (TextFold), — темой не считаются. */
    private fun notTopics(lines: List<ChatFeed.Line>, agentNames: List<String>): Set<String> =
        (lines.map { it.signature } + agentNames)
            .flatMap { TextFold.fold(it).split(NOT_LETTER) }
            .filter { it.isNotEmpty() }
            .toSet()

    private val NOT_LETTER = Regex("[^\\p{L}]+")

    private fun wordsClause(words: List<String>): String =
        if (words.isEmpty()) "" else " Слова, что звучали чаще: ${words.joinToString(", ")}."

    private fun names(v: Voices): String {
        val all = v.names
        return when {
            v.more > 0 -> all.joinToString(", ") + " и ещё ${v.more}"
            all.size <= 1 -> all.joinToString("")
            else -> all.dropLast(1).joinToString(", ") + " и " + all.last()
        }
    }

    private fun times(n: Int): String = plural(n, "раз", "раза", "раз")

    private fun messages(n: Int): String = plural(n, "сообщение", "сообщения", "сообщений")

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val tens = n % 100
        val last = n % 10
        return when {
            tens in 11..14 -> many
            last == 1 -> one
            last in 2..4 -> few
            else -> many
        }
    }
}
