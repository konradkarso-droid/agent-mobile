package com.uroboros.memory.dream

import com.uroboros.memory.MIN_WORD_LENGTH
import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.STOP_WORDS
import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker

/**
 * Дешёвый сон: какие записи этой ночью свяжутся между собой.
 *
 * Чистая арифметика над записями, без модели, без базы и без часов — поэтому
 * проверяется обычным тестом, а стоит миллисекунды. Сон ничего не меняет в
 * записях: его выход — список связей, которые кладутся рядом (см. [Dream]).
 *
 * ИЗ ЧЕГО СНИТСЯ. Два признака, и оба про то, чего поиск по вопросу не видит:
 *  - [Kind.BRIDGE] — у двух записей нет ни одного общего слова, но есть третья,
 *    с которой общие слова есть у обеих. «Алеет солнце на закате» и «Меркурий
 *    светит красным на восходе» сходятся через «Меркурий светит белым на
 *    закате». Цепочка хранится из трёх номеров: край, мост, край;
 *    Мостом может быть только короткая запись, см. [MAX_BRIDGE_WORDS];
 *  - [Kind.TIME] — записи сделаны в пределах [TIME_WINDOW_MS] друг от друга и
 *    общих слов не имеют. Время связывает соседей по разговору, а не по смыслу,
 *    и именно поэтому это сон, а не вывод.
 * Записи с общими словами между собой не снятся: их и так сводит поиск.
 *
 * СПЛЕТЕНИЕ. Внутри одной ночи простые связи сплетаются в сюжеты
 * ([Kind.PLOT]): цепочки из нескольких записей, где каждые две соседние связаны
 * одним из признаков выше. Сначала простые связи, потом сюжеты короче, потом
 * длиннее, до [MAX_PLOT_RECORDS]. Запись в сюжете встречается один раз, круги не
 * строятся, один и тот же набор записей — один сюжет.
 *
 * Сюжет обязан сплетать РАЗНЫЕ признаки — хотя бы одну связь по времени и одну
 * через мост. Цепочка из связей одного признака пересказывает то, что уже
 * приснилось простыми снами: пачка фраз, набранных подряд, связана по времени
 * вся со всей, и её перестановки — не сюжеты. А «ставка 7.5 — закат — Меркурий»
 * соединяет то, что было рядом по времени, с тем, что сходится по словам, и
 * такого сна среди простых нет.
 *
 * КТО НЕ СНИТСЯ, и почему каждый:
 *  - запись на проверке — сон обходил бы карантин;
 *  - запись из одних вопросов — вопрос ничего не утверждает, и тем же
 *    признаком его снимает судья (RiskTrigger.isOnlyQuestions);
 *  - отчёт агента о своей работе. В отчётах куски кода и десятки слов, и через
 *    них как через мост связывается всё со всем: на живой памяти это дало сотни
 *    снов за ночь вместо единиц.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - слова сравниваются по первым [WORD_PREFIX] буквам. Это грубо: «станок» и
 *    «станция» сойдутся. Для сна это приемлемо — сон и не обязан быть точным;
 *  - варианты одного сна не схлопываются: «закат — Меркурий на восходе» и
 *    «закат — Меркурий у полудня» — два сна, хотя по смыслу один;
 *  - потолок [MAX_DREAMS_PER_NIGHT] режет по порядку «проще — раньше», то есть
 *    при густой памяти до длинных сюжетов дело не дойдёт. Что потолок сработал,
 *    сказано в [Night.ceilingHit];
 *  - мосты ищутся перебором пар с пересечением соседей: при тысячах записей это
 *    уже заметная работа, при сотнях — нет.
 */
object DreamWeaver {

    enum class Kind { BRIDGE, TIME, PLOT }

    /** Один сон: номера записей в порядке цепочки и чем он приснился. */
    data class Woven(val recordIds: List<Long>, val kind: Kind)

    /**
     * Итог ночи вместе с тем, кого сон не видел.
     *
     * Числа пропущенных нужны на экране: ноль снов при сотне записей, из которых
     * девяносто — вопросы, и ноль снов, потому что проход сломан, должны
     * выглядеть по-разному.
     */
    data class Night(
        val dreams: List<Woven>,
        /** Сколько записей участвовало. */
        val dreamers: Int,
        val skippedHidden: Int,
        val skippedQuestions: Int,
        val skippedAgentReports: Int,
        /** Потолок снов за ночь сработал — часть сюжетов не построена. */
        val ceilingHit: Boolean,
    )

    fun weave(records: List<Sticker>): Night {
        var hidden = 0
        var questions = 0
        var reports = 0
        val dreamers = mutableListOf<Sticker>()
        for (record in records.sortedBy { it.id }) {
            when {
                record.reviewPending -> hidden++
                RiskTrigger.isOnlyQuestions(record.content) -> questions++
                record.source == SourceKind.AGENT_INFERRED.name -> reports++
                else -> dreamers += record
            }
        }

        val words = dreamers.associate { it.id to wordsOf(it.content) }
        val byId = dreamers.associateBy { it.id }
        // Кто с кем делит хоть одно слово. Через это считаются и отказ «общие
        // слова есть — не сон», и мосты.
        val sharing = HashMap<Long, MutableSet<Long>>()
        val byWord = HashMap<String, MutableList<Long>>()
        for (record in dreamers) {
            for (word in words.getValue(record.id)) byWord.getOrPut(word) { mutableListOf() } += record.id
        }
        for (ids in byWord.values) {
            for (a in ids) for (b in ids) if (a != b) sharing.getOrPut(a) { HashSet() } += b
        }
        fun share(a: Long, b: Long) = sharing[a]?.contains(b) == true

        val dreams = mutableListOf<Woven>()
        // Связи для сплетения: с кем связана запись и каким признаком. Признак
        // нужен сюжету — см. weavePlots.
        val links = HashMap<Long, MutableMap<Long, MutableSet<Kind>>>()
        fun link(a: Long, b: Long, kind: Kind) {
            links.getOrPut(a) { HashMap() }.getOrPut(b) { HashSet() } += kind
            links.getOrPut(b) { HashMap() }.getOrPut(a) { HashSet() } += kind
        }

        val ids = dreamers.map { it.id }
        for (i in ids.indices) {
            for (k in i + 1 until ids.size) {
                val a = ids[i]
                val b = ids[k]
                if (share(a, b)) continue
                val bridge = (sharing[a] ?: emptySet<Long>())
                    .intersect(sharing[b] ?: emptySet())
                    .filter { words.getValue(it).size <= MAX_BRIDGE_WORDS }
                    .minOrNull()
                if (bridge != null) {
                    dreams += Woven(listOf(a, bridge, b), Kind.BRIDGE)
                    link(a, b, Kind.BRIDGE)
                }
                val gap = kotlin.math.abs(byId.getValue(a).createdAt - byId.getValue(b).createdAt)
                if (gap <= TIME_WINDOW_MS) {
                    dreams += Woven(listOf(a, b), Kind.TIME)
                    link(a, b, Kind.TIME)
                }
            }
        }

        var ceilingHit = dreams.size > MAX_DREAMS_PER_NIGHT
        if (!ceilingHit) {
            ceilingHit = !weavePlots(ids, links, dreams)
        }
        return Night(
            dreams = dreams.take(MAX_DREAMS_PER_NIGHT),
            dreamers = dreamers.size,
            skippedHidden = hidden,
            skippedQuestions = questions,
            skippedAgentReports = reports,
            ceilingHit = ceilingHit,
        )
    }

    /**
     * Дописать сюжеты от коротких к длинным. Ложь — упёрлись в потолок.
     *
     * Перебор останавливается на потолке, а не строит всё и режет потом: в
     * густой памяти число цепочек растёт быстрее числа записей, и строить то,
     * что заведомо не войдёт, значило бы платить за ночь больше, чем она даёт.
     */
    private fun weavePlots(
        ids: List<Long>,
        links: Map<Long, Map<Long, Set<Kind>>>,
        dreams: MutableList<Woven>,
    ): Boolean {
        // Один набор записей — один сюжет, в каком бы порядке его ни обошли:
        // в группе, где все связаны со всеми, иначе каждая перестановка стала
        // бы отдельным сном.
        val seen = HashSet<Set<Long>>()
        for (length in 3..MAX_PLOT_RECORDS) {
            fun walk(path: List<Long>, kinds: Set<Kind>): Boolean {
                if (path.size == length) {
                    if (kinds.size < 2) return true
                    if (seen.add(path.toSet())) {
                        if (dreams.size >= MAX_DREAMS_PER_NIGHT) return false
                        val canonical = if (path.first() < path.last()) path else path.reversed()
                        dreams += Woven(canonical, Kind.PLOT)
                    }
                    return true
                }
                val neighbours = links[path.last()] ?: emptyMap()
                for (next in neighbours.keys.sorted()) {
                    if (next in path) continue
                    for (kind in neighbours.getValue(next)) {
                        if (!walk(path + next, kinds + kind)) return false
                    }
                }
                return true
            }
            for (start in ids) {
                if (!walk(listOf(start), emptySet())) return false
            }
        }
        return true
    }

    /**
     * Значимые слова записи — тем же правилом, что у поиска (MIN_WORD_LENGTH,
     * STOP_WORDS), и укороченные до [WORD_PREFIX] букв вместо разбора окончаний.
     */
    internal fun wordsOf(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= MIN_WORD_LENGTH && it !in STOP_WORDS }
            .map { it.take(WORD_PREFIX) }
            .toSet()

    /**
     * Сколько первых букв слова сравнивается. Пять — та же ступень, что у
     * лестницы поиска (HourglassMemory.ladder): «рубанка» и «рубанок»,
     * «деревянная» и «деревянной» сходятся, а четыре буквы уже сводили бы
     * случайное. Перепроверяется утренним экраном: сны, связанные мусорным
     * словом, видны там сразу.
     */
    const val WORD_PREFIX = 5

    /**
     * Насколько близко по времени записи снятся вместе. Десять минут — один
     * заход разговора: на живой памяти так связались пачки фраз, набранных
     * подряд. Область — нынешний способ пользоваться агентом короткими
     * заходами; перепроверяется утренним экраном.
     */
    const val TIME_WINDOW_MS = 10 * 60 * 1000L

    /**
     * Сколько значимых слов может быть у записи, чтобы она служила мостом.
     *
     * Длинный текст делит хоть одно слово почти с любой записью, и через него
     * связывается всё со всем: одно подробное описание мастерской на живой
     * памяти стало мостом для трёх десятков снов из шестидесяти, вытеснив
     * остальные потолком. Короткая запись связывает двух других одной мыслью,
     * длинная — разными своими частями, и это уже не мост, а совпадение.
     *
     * Сама длинная запись при этом снится как обычно — концом цепочки и по
     * времени. Число — граница, а не подбор: обычная фраза памяти укладывается
     * в него с запасом. Перепроверяется утренним экраном: если мостом снова
     * становится одна запись на десятки снов, граница стоит не там.
     */
    const val MAX_BRIDGE_WORDS = 8

    /**
     * Самый длинный сюжет, в записях. Дальше сюжет, всплыв в ответе, тащил бы
     * за одно место слишком много чужого текста. Объявленная граница, не подбор.
     */
    const val MAX_PLOT_RECORDS = 4

    /**
     * Потолок снов за ночь. Стартовое число, не вывод. Область — память в
     * десятки записей, где простых связей десятки и сюжетов того же порядка.
     * Сработал ли он — [Night.ceilingHit]; если срабатывает каждую ночь, число
     * пора пересмотреть.
     */
    const val MAX_DREAMS_PER_NIGHT = 60
}
