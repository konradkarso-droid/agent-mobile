package com.uroboros.gate

/**
 * Падежи имени собеседника для развёртки ([GateUnfold]): «Борис» → «Бориса»,
 * «Борису», «Борисом», «о Борисе».
 *
 * Правило по последней букве — объявленное, не подобранное:
 *  - согласная — как «Борис», «Антон», «агент»; после ж/ш/ч/щ/ц творительный на -ем;
 *  - «-й» — как «Андрей»; «-ий» — как «Дмитрий» (предложный на -ии);
 *  - «-ь» — как «Игорь», мужской род;
 *  - «-а», «-я», «-ия» — как «Маша», «Илья», «Мария»;
 *  - другое («Тони», «Кэп» латиницей) — не склоняется.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - беглую гласную: «Павел» даст «Павела», «Лев» — «Лева»;
 *  - женские имена на мягкий знак: «Любовь» склонится как мужское;
 *  - фамилии и отчества;
 *  - имена из нескольких слов — склоняется только первое ([phrase]).
 *
 * Словарь начальных форм (memory.nav.LemmaDictionary) падежей не хранит, поэтому
 * здесь правило. Все, кому нужен падеж имени, берут его отсюда: появится падеж
 * в словаре — меняется только этот объект.
 */
object NameCases {

    enum class Case { NOM, GEN, DAT, ACC, INS, PREP }

    enum class Gender { MASC, FEMN, UNKNOWN }

    class Forms internal constructor(private val forms: Map<Case, String>) {
        fun of(c: Case): String = forms.getValue(c)
    }

    private const val CONSONANTS = "бвгджзклмнпрстфхцчшщ"

    fun of(name: String): Forms = Forms(decline(name.trim()))

    /** Первое слово склоняется, хвост («Бориса» в «агент Бориса») — нет. */
    fun phrase(head: String, tail: String): Forms =
        Forms(decline(head.trim()).mapValues { (_, v) -> "$v $tail" })

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

    /** Мужские имена на -а/-я. Список объявленный, не полный. */
    private val MASC_A = setOf(
        "никита", "илья", "кузьма", "фома", "лука", "савва", "данила", "гаврила", "миша", "дима",
        "вова", "паша", "леша", "петя", "ваня", "коля", "толя", "федя", "гоша", "гриша", "сережа",
        "костя", "митя", "юра",
    )

    /** Имена обоих родов. */
    private val BOTH = setOf("саша", "женя", "валя", "шура", "слава")

    /**
     * Род имени — только для местоимений «он/она» в рамке «передай Антону, что он…»
     * (см. [GateUnfold]). Не знаю — [Gender.UNKNOWN], и местоимения не трогаются.
     */
    fun genderOf(name: String): Gender {
        val low = name.trim().lowercase().replace('ё', 'е')
        if (low.isEmpty()) return Gender.UNKNOWN
        return when {
            low in BOTH -> Gender.UNKNOWN
            low in MASC_A -> Gender.MASC
            low.last() == 'а' || low.last() == 'я' -> Gender.FEMN
            low.last() in CONSONANTS || low.last() == 'й' || low.last() == 'ь' -> Gender.MASC
            else -> Gender.UNKNOWN
        }
    }
}
