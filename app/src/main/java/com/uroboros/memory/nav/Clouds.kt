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
 * ВЕС — НЕ УТВЕРЖДЕНИЕ. В модель облако этой постройкой не идёт вовсе: это
 * прибор и основа следующих шагов. Если когда-нибудь пойдёт — только темой в
 * рамке, которую ставит код («мы с собеседником часто говорили о …»), никогда
 * фразой «кто что сделал». Записи от облака не меняются, в оценку кандидатов
 * отбора оно не подмешивается.
 *
 * ЧЕГО НЕ УМЕЕТ: облако из слов, а не из смысла — «код» и «программа» разные
 * элементы. Места («личка», «группа») облако пока не различает: до телеграма
 * всё — экран (см. Docking).
 */
object Clouds {

    /** Полураспад веса — 7 дней. Объявленное число, не подобранное. */
    const val HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000

    /** Сколько элементов в строке хода и в разделе. */
    const val LINE_TOP = 5
    const val SECTION_TOP = 20

    /** Один источник слов: кто сказал, о ком и когда. */
    data class Source(val text: String, val speaker: PersonKey?, val about: Set<PersonKey>, val at: Long)

    /** Элемент облака: основа, вес и чьими словами он набран. */
    data class Element(val stem: String, val weight: Double, val bySpeaker: Map<PersonKey?, Double>)

    /** Облако персоны; элементы — по весу, тяжёлые первыми. */
    data class Cloud(val elements: List<Element>) {
        val isEmpty: Boolean get() = elements.isEmpty()
    }

    /** Вес одного упоминания возраста [ageMs]: 1 сейчас, 1/2 через полураспад. */
    fun decay(ageMs: Long): Double = 0.5.pow(ageMs.coerceAtLeast(0L).toDouble() / HALF_LIFE_MS)

    /** Облако [person] из источников, где она в «о ком». */
    fun of(person: PersonKey, sources: List<Source>, now: Long): Cloud {
        val weights = HashMap<String, Double>()
        val by = HashMap<String, HashMap<PersonKey?, Double>>()
        for (s in sources) {
            if (person !in s.about) continue
            val w = decay(now - s.at)
            for (stem in RiskTrigger.significantStems(s.text)) {
                weights[stem] = (weights[stem] ?: 0.0) + w
                val m = by.getOrPut(stem) { HashMap() }
                m[s.speaker] = (m[s.speaker] ?: 0.0) + w
            }
        }
        return Cloud(
            weights.entries
                .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                .map { Element(it.key, it.value, by[it.key].orEmpty()) }
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
                Element(o.stem, minOf(o.weight, other.weight), by)
            }.sortedWith(compareByDescending<Element> { it.weight }.thenBy { it.stem })
        )
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

    /** Раздел «ОБЛАКА» в «Показать». */
    fun section(owner: Cloud, agent: Cloud): String = buildString {
        append("ОБЛАКА\n")
        append("Считаются из записей при открытии раздела, не хранятся. Вес — частота с остыванием ")
        append("(полураспад 7 дней); в скобках — чьими словами набран. В модель не подаются.\n")
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
