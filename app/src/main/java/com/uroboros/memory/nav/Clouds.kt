package com.uroboros.memory.nav

import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.nav.Coordinates.Address
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Облако персоны — всё, что связано с ней в словах: значимые основы записей,
 * в «о ком» которых она есть. СЧИТАЕТСЯ, НЕ ХРАНИТСЯ: вес — частота, остывание —
 * возраст записи (полураспад [HALF_LIFE_MS]). Руками облако не заполняется:
 * значимость выводится из опыта.
 *
 * Смешанная запись («Работаю над твоей памятью») даёт вес обоим облакам.
 * Каждый элемент помнит, чьими словами набран вес.
 *
 * Облако агента — его записи, выводы, темы снов и своя речь (nav.OwnSpeech);
 * чужого «я» в нём нет по построению. «Мы» — пересечение облаков владельца и
 * агента.
 *
 * ВЕС — НЕ УТВЕРЖДЕНИЕ. В модель облако идёт только темой в рамке, которую
 * ставит код, и никогда фразой «кто что сделал». Сейчас такое место одно:
 * строка выбора имени (dream.SelfGap.line) — «обо мне чаще всего говорят
 * слова: …», формами слов ([Element.form]), не основами. Записи от облака не
 * меняются, в оценку кандидатов отбора оно не подмешивается.
 *
 * ЧИСТКА — ЧТО В ОБЛАКО НЕ ИДЁТ. Облако — то, что о персоне сказано, а не
 * всякое слово рядом с ней. Снимается три вида мусора, каждый считается
 * отдельно и показывается строкой раздела ([section]):
 *  - записи, что ничего не утверждают (RiskTrigger.assertsNothing): одни
 *    вопросы, просьбы или обычай. «Какая у тебя модель?» — вопрос об агенте,
 *    а не знание о нём; без чистки «модель» набирала вес с каждым вопросом;
 *  - своя речь агента не о себе ([keepsOwn]): ответ о мире («белки
 *    поддерживают температуру тела») — его слова, но не о нём;
 *  - слова-связки ([isLinkWord]) — местоимения, союзы, частицы, предлоги,
 *    междометия, предикативы и притяжательные «мой, твой, свой». Основа
 *    «теб» проходит по длине, а темой не бывает никогда.
 * Темы снов и выводы не чистятся: сны остаются в облаке агента, хоть и не
 * правда (отличать правду от сна — отдельная задача, не эта).
 *
 * ЧЕГО НЕ УМЕЕТ: облако из слов, а не из смысла — «код» и «программа» разные
 * элементы. Места («личка», «группа») облако пока не различает: до телеграма
 * всё — экран (см. Docking). Своя речь «о себе» — по грамматике
 * (PersonForm.aboutSpeaker): «Мне снилось море» — о себе, «Море было тёплым»
 * — нет. Вещь без лица у владельца («У рубанка ручка треснула») в облако
 * владельца не идёт: в ней нет ни «я», ни «мой». Слово-связка — по словарю
 * начальных форм; словарь не загружен — связки не снимаются, и строка
 * раздела это говорит; незнакомое словарю слово связкой не считается.
 */
object Clouds {

    /** Полураспад веса — 7 дней. Объявленное число, не подобранное. */
    const val HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000

    /** Сколько элементов в строке хода и в разделе. */
    const val LINE_TOP = 5
    const val SECTION_TOP = 20

    /** Один источник слов: кто сказал, о ком и когда. */
    data class Source(val text: String, val speaker: PersonKey?, val about: Set<PersonKey>, val at: Long)

    /**
     * Элемент облака: основа, вес и чьими словами он набран. [form] — самая
     * частая написанная форма основы (при равенстве — первая по алфавиту):
     * основу показывать модели нельзя, «радуг» она возьмёт словом.
     */
    data class Element(
        val stem: String,
        val weight: Double,
        val bySpeaker: Map<PersonKey?, Double>,
        val form: String = stem,
    )

    /**
     * Облако персоны; элементы — по весу, тяжёлые первыми. [linkWords] —
     * сколько разных основ снято как слова-связки; null — словаря не было,
     * связки не снимались.
     */
    data class Cloud(val elements: List<Element>, val linkWords: Int? = null) {
        val isEmpty: Boolean get() = elements.isEmpty()
    }

    /**
     * Источники облаков после чистки и сколько снято: [assertsNothing] —
     * записей, что ничего не утверждают, [ownNotAboutSelf] — предложений своей
     * речи не о себе (см. «ЧИСТКА» в описании объекта).
     */
    data class Gathered(val sources: List<Source>, val assertsNothing: Int, val ownNotAboutSelf: Int)

    /** Идёт ли запись памяти в облака: утверждает ли она что-нибудь. */
    fun keepsRecord(content: String): Boolean = !RiskTrigger.assertsNothing(content)

    /** Идёт ли предложение своей речи в облако агента: о себе ли оно. */
    fun keepsOwn(sentence: String): Boolean = PersonForm.of(sentence).aboutSpeaker

    /**
     * Части речи слов-связок: те же служебные, что у портрета
     * (Portrait.FUNCTION_WORDS), и предикативы («можно», «нельзя»). Портрет
     * ищет только существительные, и предикатив ему не мешает; облако берёт
     * любое слово.
     */
    val LINK_PARTS: Set<String> = Portrait.FUNCTION_WORDS + "PRED"

    /**
     * Слово-связка: хоть один разбор словаря — часть речи из [LINK_PARTS] или
     * притяжательное («мой, твой, свой», пометка Apro). Как у портрета: «по»
     * бывает и «ПО», программой, но темой облака не бывает. Незнакомое слово —
     * не связка.
     */
    fun isLinkWord(word: String, dictionary: LemmaDictionary): Boolean =
        dictionary.analyze(word).any { it.partOfSpeech in LINK_PARTS || "Apro" in it.tags }

    /** Вес одного упоминания возраста [ageMs]: 1 сейчас, 1/2 через полураспад. */
    fun decay(ageMs: Long): Double = 0.5.pow(ageMs.coerceAtLeast(0L).toDouble() / HALF_LIFE_MS)

    /**
     * Облако [person] из источников, где она в «о ком». [dictionary] — словарь
     * начальных форм для снятия слов-связок; null — не снимаются.
     */
    fun of(person: PersonKey, sources: List<Source>, now: Long, dictionary: LemmaDictionary? = null): Cloud {
        val weights = HashMap<String, Double>()
        val by = HashMap<String, HashMap<PersonKey?, Double>>()
        val forms = HashMap<String, HashMap<String, Int>>()
        val links = HashSet<String>()
        for (s in sources) {
            if (person !in s.about) continue
            val w = decay(now - s.at)
            for ((stem, written) in RiskTrigger.significantWords(s.text)) {
                // Связка — если связки все написанные формы основы в этом тексте.
                if (dictionary != null && written.all { isLinkWord(it, dictionary) }) {
                    links += stem
                    continue
                }
                weights[stem] = (weights[stem] ?: 0.0) + w
                val m = by.getOrPut(stem) { HashMap() }
                m[s.speaker] = (m[s.speaker] ?: 0.0) + w
                val f = forms.getOrPut(stem) { HashMap() }
                for (word in written) f[word] = (f[word] ?: 0) + 1
            }
        }
        return Cloud(
            weights.entries
                .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                .map { e ->
                    val form = forms[e.key].orEmpty().entries
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        .firstOrNull()?.key ?: e.key
                    Element(e.key, e.value, by[e.key].orEmpty(), form)
                },
            if (dictionary == null) null else links.size,
        )
    }

    /** «Мы» — элементы, общие двум облакам; вес — меньший из двух. */
    fun we(owner: Cloud, agent: Cloud): Cloud {
        val a = agent.elements.associateBy { it.stem }
        return Cloud(
            owner.elements.mapNotNull { o ->
                val other = a[o.stem] ?: return@mapNotNull null
                val by = HashMap<PersonKey?, Double>(o.bySpeaker)
                for ((k, v) in other.bySpeaker) by[k] = (by[k] ?: 0.0) + v
                Element(o.stem, minOf(o.weight, other.weight), by, o.form)
            }.sortedWith(compareByDescending<Element> { it.weight }.thenBy { it.stem })
        )
    }

    /**
     * Слова облака агента для строки выбора имени (dream.SelfGap.line): формы
     * [count] самых тяжёлых элементов, без [skipStemOf] — слова, которое строка
     * называет сама.
     */
    fun words(cloud: Cloud, count: Int, skipStemOf: String): List<String> {
        val skip = RiskTrigger.significantStems(skipStemOf)
        return cloud.elements.filter { it.stem !in skip }.take(count).map { it.form }
    }

    /** Источник из записи памяти: кто сказал — по `source`, о ком — по форме. */
    fun fromRecord(content: String, source: String, createdAt: Long): Source {
        val speaker = Coordinates.speakerOf(source)
        return Source(content, speaker, Coordinates.aboutOf(content, speaker).persons, createdAt)
    }

    /** Источник, заведомо об агенте и его словами: выводы, темы снов, своя речь. */
    fun ofAgent(text: String, at: Long): Source = Source(text, PersonKey.AGENT, setOf(PersonKey.AGENT), at)

    // ---- Показ ----

    private fun weight(w: Double): String = ((w * 10).roundToInt() / 10.0).toString()

    private fun short(e: Element): String = "${e.stem} ${weight(e.weight)}"

    private fun full(e: Element): String {
        val by = listOf(PersonKey.OWNER, PersonKey.AGENT, null)
            .mapNotNull { k -> e.bySpeaker[k]?.let { "${Coordinates.nameOf(k)} ${weight(it)}" } }
        return "${e.stem} ${weight(e.weight)} (${by.joinToString(", ")})"
    }

    /**
     * Строка хода «Облако адреса:». [owner]/[agent] null — не считалось
     * (сбой чтения); пустое облако — «облако пусто»: два разных показания.
     */
    fun addressLine(address: Address, owner: Cloud?, agent: Cloud?, failure: String? = null): String {
        val head = "Облако адреса: "
        if (address == Address.UNDEFINED) return head + "адрес не определён"
        if (failure != null || owner == null || agent == null) return head + "не считалось — ${failure ?: "нет данных"}"
        val (name, cloud) = when (address) {
            Address.AGENT -> "агент" to agent
            Address.OWNER -> "владелец" to owner
            else -> "мы" to we(owner, agent)
        }
        if (cloud.isEmpty) return "$head$name · облако пусто"
        return "$head$name · " + cloud.elements.take(LINE_TOP).joinToString(", ") { short(it) }
    }

    /**
     * Строка чистки: что снято и почему. Без неё пустое облако неотличимо от
     * сломанного, а пропавшее слово — от не сказанного.
     */
    fun cleaningLine(gathered: Gathered, owner: Cloud, agent: Cloud): String {
        val links = if (owner.linkWords == null || agent.linkWords == null) {
            "слова-связки не сняты — словарь не загружен"
        } else {
            "слов-связок: у владельца ${owner.linkWords}, у агента ${agent.linkWords}"
        }
        return "Снято: записей, что ничего не утверждают, ${gathered.assertsNothing} · " +
            "предложений своей речи не о себе ${gathered.ownNotAboutSelf} · $links"
    }

    /** Раздел «ОБЛАКА» в «Показать». */
    fun section(owner: Cloud, agent: Cloud, gathered: Gathered): String = buildString {
        append("ОБЛАКА\n")
        append("Считаются из записей при открытии раздела, не хранятся. Вес — частота с остыванием ")
        append("(полураспад 7 дней); в скобках — чьими словами набран. В модель не подаются.\n")
        append(cleaningLine(gathered, owner, agent)).append("\n")
        fun block(title: String, cloud: Cloud) {
            append("\n").append(title).append(": ")
            if (cloud.isEmpty) append("облако пусто")
            else cloud.elements.take(SECTION_TOP).forEach { append("\n• ").append(full(it)) }
        }
        block("Владелец", owner)
        block("Агент", agent)
        block("Мы (общее)", we(owner, agent))
    }
}
