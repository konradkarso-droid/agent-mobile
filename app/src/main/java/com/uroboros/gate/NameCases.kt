package com.uroboros.gate

import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.util.TextFold

/**
 * Падежи и род имени собеседника для развёртки ([GateUnfold]): «Борис» →
 * «Бориса», «Борису», «Борисом», «о Борисе».
 *
 * СЛОВАРЬ ПЕРВЫМ. Если имя есть в словаре начальных форм как имя
 * ([LemmaDictionary.nameForms]), формы берутся оттуда: «Павел» → «Павла»,
 * «Любовь» → «Любови», «Алсу» не склоняется. Написание сказанного имени —
 * заглавная, «ё» — сохраняется в общем начале («Артём» → «Артёма»): словарь
 * хранит «е». В творительном из двух форм — короткая («Машей», не «Машею»;
 * объявленное, не подобранное).
 *
 * Правило по букве — для имени, которого в словаре нет (латиница, ники, редкие
 * имена), и для двоякого: две лексемы-имени склоняются по-разному («Мишель» —
 * мужское склоняется, женское нет), и словарь не решает, какое из них. Откуда
 * взялись формы — [Forms.source], для строки прибора.
 *
 * Правило по последней букве — объявленное, не подобранное:
 *  - согласная — как «Борис», «Антон», «агент»; после ж/ш/ч/щ/ц творительный на -ем;
 *  - «-й» — как «Андрей»; «-ий» — как «Дмитрий» (предложный на -ии);
 *  - «-ь» — как «Игорь», мужской род;
 *  - «-а», «-я», «-ия» — как «Маша», «Илья», «Мария»;
 *  - другое («Тони», «Кэп» латиницей) — не склоняется.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - правилом по букве — беглую гласную («Павел» дал бы «Павела») и женские
 *    имена на мягкий знак; словарь их знает, правило нужно только для того,
 *    чего в словаре нет;
 *  - звательную форму («Лёш»), множественное, фамилии и отчества;
 *  - имена из нескольких слов — склоняется только первое ([phrase]), и только
 *    правилом по букве.
 *
 * Все, кому нужен падеж или род имени, берут его отсюда.
 */
object NameCases {

    enum class Case { NOM, GEN, DAT, ACC, INS, PREP }

    enum class Gender { MASC, FEMN, UNKNOWN }

    /** Откуда формы: из словаря; по букве — имени нет в словаре; по букве — в словаре оно двоякое. */
    enum class Source(val label: String) {
        DICTIONARY("по словарю"),
        LETTER_UNKNOWN("по букве — нет в словаре"),
        LETTER_AMBIGUOUS("по букве — в словаре склоняется двояко"),
    }

    class Forms internal constructor(private val forms: Map<Case, String>, val source: Source) {
        fun of(c: Case): String = forms.getValue(c)
    }

    private const val CONSONANTS = "бвгджзклмнпрстфхцчшщ"

    private val GRAMMEME = mapOf(
        Case.NOM to "nomn", Case.GEN to "gent", Case.DAT to "datv",
        Case.ACC to "accs", Case.INS to "ablt", Case.PREP to "loct",
    )

    /** [dictionary] — null: только правило по букве. */
    fun of(name: String, dictionary: LemmaDictionary? = null): Forms {
        val w = name.trim()
        val lexemes = dictionary?.nameForms(w).orEmpty().mapNotNull { formsOf(w, it) }
        return when {
            lexemes.isEmpty() -> Forms(decline(w), Source.LETTER_UNKNOWN)
            lexemes.toSet().size > 1 -> Forms(decline(w), Source.LETTER_AMBIGUOUS)
            else -> Forms(lexemes[0], Source.DICTIONARY)
        }
    }

    /** Все шесть падежей одной лексемы в написании [w]; падежа не хватает — лексема не годится. */
    private fun formsOf(w: String, lexeme: LemmaDictionary.NameLexeme): Map<Case, String>? {
        val out = LinkedHashMap<Case, String>()
        for (c in Case.values()) {
            if (c == Case.NOM) { out[c] = w; continue }
            val form = lexeme.forms[GRAMMEME.getValue(c)]?.minWithOrNull(compareBy({ it.length }, { it }))
                ?: return null
            out[c] = spelled(w, form)
        }
        return out
    }

    /** Общее со сказанным именем начало пишется как сказано: «Артём» + «артема» → «Артёма». */
    private fun spelled(w: String, form: String): String {
        val f = TextFold.fold(w)
        var k = 0
        while (k < minOf(f.length, form.length) && f[k] == form[k]) k++
        return w.substring(0, k) + form.substring(k)
    }

    /** Первое слово склоняется, хвост («Бориса» в «агент Бориса») — нет. Только правилом по букве. */
    fun phrase(head: String, tail: String): Forms =
        Forms(decline(head.trim()).mapValues { (_, v) -> "$v $tail" }, Source.LETTER_UNKNOWN)

    private fun decline(w: String): Map<Case, String> {
        val low = w.lowercase()
        if (low.isEmpty()) return Case.values().associateWith { w }
        val last = low.last()
        fun m(stem: String, g: String, d: String, a: String, i: String, p: String) = mapOf(
            Case.NOM to w, Case.GEN to stem + g, Case.DAT to stem + d,
            Case.ACC to stem + a, Case.INS to stem + i, Case.PREP to stem + p,
        )
        val stem = w.dropLast(1)
        val prev = if (low.length > 1) low[low.length - 2] else ' '
        return when {
            low.endsWith("ия") -> m(stem, "и", "и", "ю", "ей", "и")
            last == 'я' -> m(stem, "и", "е", "ю", "ей", "е")
            last == 'а' -> m(
                stem, if (prev in "гкхжшчщ") "и" else "ы", "е", "у",
                if (prev in "жшчщц") "ей" else "ой", "е",
            )
            low.endsWith("ий") -> m(stem, "я", "ю", "я", "ем", "и")
            last == 'й' || last == 'ь' -> m(stem, "я", "ю", "я", "ем", "е")
            last in CONSONANTS -> m(w, "а", "у", "а", if (last in "жшчщц") "ем" else "ом", "е")
            else -> Case.values().associateWith { w }
        }
    }

    /** Мужские имена на -а/-я — для имён, которых нет в словаре. Список объявленный, не полный. */
    private val MASC_A = setOf(
        "никита", "илья", "кузьма", "фома", "лука", "савва", "данила", "гаврила", "миша", "дима",
        "вова", "паша", "леша", "петя", "ваня", "коля", "толя", "федя", "гоша", "гриша", "сережа",
        "костя", "митя", "юра",
    )

    /** Имена обоих родов. */
    private val BOTH = setOf("саша", "женя", "валя", "шура", "слава")

    /**
     * Род имени — только для местоимений «он/она» в рамке «передай Антону, что он…»
     * (см. [GateUnfold]). Не знаю — [Gender.UNKNOWN], и местоимения не трогаются:
     * лишняя замена дала бы неверный смысл, пропущенная — только «он» как сказано.
     *
     * Это род ИМЕНИ, а не пол человека. «Саша», «Женя», «Паша» по словарю общего
     * рода — не знаю. Две лексемы с разным родом («Мишель») — не знаю. Имени нет в
     * словаре — правило по букве и списки ниже. Что человек сказал о себе сам,
     * сюда не входит и, когда появится, должно идти отдельно и сильнее.
     */
    fun genderOf(name: String, dictionary: LemmaDictionary? = null): Gender {
        val low = name.trim().lowercase().replace('ё', 'е')
        if (low.isEmpty()) return Gender.UNKNOWN
        val genders = dictionary?.nameForms(low).orEmpty().map { it.gender }.toSet()
        if (genders.isNotEmpty()) return when (genders.singleOrNull()) {
            "masc" -> Gender.MASC
            "femn" -> Gender.FEMN
            else -> Gender.UNKNOWN
        }
        return when {
            low in BOTH -> Gender.UNKNOWN
            low in MASC_A -> Gender.MASC
            low.last() == 'а' || low.last() == 'я' -> Gender.FEMN
            low.last() in CONSONANTS || low.last() == 'й' || low.last() == 'ь' -> Gender.MASC
            else -> Gender.UNKNOWN
        }
    }
}
