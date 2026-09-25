package com.uroboros.memory

/**
 * Круг дольменов: как места в ответе делятся между окнами отбора. Чистая
 * часть — без базы и без Android, чтобы правила закреплялись обычными
 * тестами. Поиск и прогрев живут в [HourglassMemory.getContextWithSummary].
 *
 * ОКНА. Каждое ищет в своих слоях и по своим словам:
 *  - вопрос — оранжевый, жёлтый, зелёный; по словам вопроса;
 *  - тема — те же слои; по словам последних реплик владельца ([themeWords]);
 *  - холод — синий, затем фиолетовый; по словам вопроса, а если их нет — по
 *    словам темы.
 *
 * ЗАЧЕМ ОКНА, А НЕ ОДИН ПОИСК. Одним поиском горячая память забирает все
 * места: она больше и свежее, и холодная запись до ответа не доходит никогда,
 * пока в вопросе нет слова «архив». А короткая реплика вроде «да, давай» не
 * находит ничего, хотя тема разговора ясна из прошлых ходов. Гарантированное
 * место у окна — это обещание, что его голос слышен, даже когда соседи
 * громче.
 *
 * КРАСНЫЙ СЛОЙ НЕ ИЩЕТСЯ НИ ОДНИМ ОКНОМ, и это не пропуск. Красный — «кто я»
 * агента, и по устройству он подаётся сборкой в раздел «О себе» стены (третья
 * часть, «нажитое о себе», см. KDoc llm.BuildSelfDescription), а не местами в
 * ответе. Цитатой памяти рядом с вопросом запись о себе читалась бы как
 * обычное воспоминание, и её место отнимало бы место у уместного. Прежний
 * канал принципов, клавший красный в каждый отбор, убран сознательно —
 * возвращать его сюда нельзя: путь красного к модели — стена. Сейчас слой
 * пуст, и прибор говорит об этом строкой «красный: N».
 *
 * ЧЕГО КРУГ НЕ УМЕЕТ:
 *  - окна смотрят только то, что принёс поиск лестницей. Запись, которую
 *    лестница не нашла, не видна ни одному окну;
 *  - тема — это слова, а не смысл: сменил владелец тему — окно ещё три хода
 *    ищет по старой.
 */
object DolmenCircle {

    // Числа мест — объявленные, не подобранные: подобраны для 5 мест и памяти
    // без красного; перепроверять по строке прибора «Круг:». Сумма равна пяти
    // намеренно — при пяти местах каждое окно получает своё.

    /** Гарантированных мест у окна вопроса. */
    const val QUESTION_SEATS = 3

    /** Гарантированных мест у окна темы. */
    const val THEME_SEATS = 1

    /** Гарантированных мест у окна холода. */
    const val COLD_SEATS = 1

    /**
     * Из скольких последних реплик владельца берётся тема. Объявленное число,
     * не подобранное: столько обычно длится разговор об одном (то же, что
     * срок двери сна).
     */
    const val THEME_TURNS = 3

    /**
     * Сколько разных слов темы должна задеть запись, чтобы пройти окно темы.
     * Объявленное число, не подобранное: одно общее слово из десятка — это
     * случайность, два — уже разговор о том же. Правило целиком — в
     * [scoreThemeCandidate].
     */
    const val THEME_MIN_WORDS = 2

    /** Места, розданные окнам, — каждое в порядке своего отбора. */
    data class Seating(
        val question: List<Sticker>,
        val theme: List<Sticker>,
        val cold: List<Sticker>,
    ) {
        /** Всё, что уходит в ответ: сперва вопрос, потом тема, потом холод. */
        val all: List<Sticker> get() = question + theme + cold
    }

    /**
     * Раздать [limit] мест трём окнам. Каждый список — найденное окном, уже
     * упорядоченное его отбором.
     *
     * Сперва каждое окно берёт до своих гарантированных мест. Незанятые места
     * отдаются по порядку: вопросу, потом теме, потом холоду — пока места не
     * кончатся или окнам нечего дать. Пустое окно своё место не держит: оно
     * уходит соседям, а не пропадает.
     *
     * Запись, найденная ранним окном, в позднем не считается второй раз —
     * даже если раннее окно само её не усадило. Так одна запись не занимает
     * два места, и тема не возвращает то, что вопрос уже взвесил.
     */
    fun seat(limit: Int, question: List<Sticker>, theme: List<Sticker>, cold: List<Sticker>): Seating {
        val q = question.distinctBy { it.id }
        val seen = q.mapTo(HashSet()) { it.id }
        val t = theme.filter { it.id !in seen }.distinctBy { it.id }
        t.mapTo(seen) { it.id }
        val c = cold.filter { it.id !in seen }.distinctBy { it.id }

        var left = limit.coerceAtLeast(0)
        fun take(want: Int): Int = minOf(want, left).also { left -= it }

        var qTaken = take(minOf(QUESTION_SEATS, q.size))
        var tTaken = take(minOf(THEME_SEATS, t.size))
        var cTaken = take(minOf(COLD_SEATS, c.size))
        qTaken += take(q.size - qTaken)
        tTaken += take(t.size - tTaken)
        cTaken += take(c.size - cTaken)

        return Seating(q.take(qTaken), t.take(tTaken), c.take(cTaken))
    }

    /**
     * Слова темы: значимые слова ([meaningfulWords]) из реплик владельца
     * последних [THEME_TURNS] ходов, минус слова текущего вопроса.
     *
     * Пустые реплики пропускаются — это ходы, где агент написал первым, и
     * владелец в них ничего не сказал. Берутся три последние НЕпустые.
     *
     * Слова вопроса вычитаются, потому что их уже ищет окно вопроса; и
     * сливать два набора в один запрос нельзя: доля вопроса упала бы, и отсев
     * по ней выбросил бы годное молча (см. [scoreThemeCandidate]).
     *
     * @param recentQuestions реплики владельца в порядке ленты, от старых к новым.
     */
    fun themeWords(recentQuestions: List<String>, questionWords: List<String>): List<String> {
        val asked = questionWords.toHashSet()
        return recentQuestions
            .filter { it.isNotBlank() }
            .takeLast(THEME_TURNS)
            .asReversed()
            .flatMap { meaningfulWords(it) }
            .distinct()
            .filter { it !in asked }
    }

    /** Чем кончилось одно окно — для прибора. */
    sealed class Window {
        /** Искать было не по чему: у окна нет слов. */
        object NoWords : Window()

        /** В слоях окна нет ни одной живой записи — искать не в чем. */
        object LayerEmpty : Window()

        /** Искал, но ни одна запись не прошла отбор окна. */
        object SearchedEmpty : Window()

        /** Прошло [found] записей, из них усажено [seated]. */
        data class Found(val found: Int, val seated: Int) : Window()
    }

    /**
     * Строка прибора «Круг:». Печатается всегда: молчащее окно должно
     * отличаться от сломанного без чтения кода. «Слой пуст», «нечем искать» и
     * «искал — пусто» лечатся разным — временем, словами, порогами.
     *
     * @param coldByTheme холод искал по словам темы, потому что у вопроса слов не было.
     * @param ribbonExcluded сколько различных реплик (лента и текущая) передано
     *        в исключение. Сколько записей срезал сам запрос, не считается —
     *        честно не посчитать. Ноль при непустой ленте значит «не передано»,
     *        то есть сломано; печатается при N > 0.
     */
    fun meter(
        question: Window,
        theme: Window,
        themeWords: List<String>,
        cold: Window,
        coldByTheme: Boolean,
        red: Int,
        ribbonExcluded: Int = 0,
    ): String {
        val themeName = if (themeWords.isEmpty()) "тема" else "тема (${themeWords.joinToString(", ")})"
        val coldName = if (coldByTheme && cold !is Window.LayerEmpty) "холод по словам темы" else "холод"
        val excludedPart = if (ribbonExcluded > 0) " · реплик ленты в исключении $ribbonExcluded" else ""
        return "Круг: вопрос — ${say(question)} · $themeName — ${say(theme)} · " +
            "$coldName — ${say(cold)} · красный: $red — в ответ не идут, идут в стену" +
            excludedPart
    }

    /** Строка круга, когда вопроса не было: отбор не собирался. */
    const val NOT_GATHERED = "Круг: не собирался — вопроса не было, показан срез памяти по рангу."

    /**
     * Что агент сделал с холодом после ответа, или null — холод ничего не дал.
     * Отдельно от строки вспоминания снов: слитые, они скрыли бы, чьё это
     * вспоминание.
     */
    fun coldUse(brought: Int, used: Int, warmed: Int): String? =
        if (brought == 0) null
        else "холод: использовано $used из $brought · согрето $warmed"

    private fun say(window: Window): String = when (window) {
        Window.NoWords -> "нечем искать (нет слов)"
        Window.LayerEmpty -> "слой пуст"
        Window.SearchedEmpty -> "искал — пусто"
        is Window.Found -> "нашёл ${window.found}, мест ${window.seated}"
    }
}

/**
 * Правило окна темы: проходит запись, задевшая не меньше чем
 * min([DolmenCircle.THEME_MIN_WORDS], число слов темы) разных слов темы, при
 * цене места не выше [MAX_CHARS_PER_MATCH]. Ровно пороговые значения
 * проходят, как и у [scoreCandidate].
 *
 * ПОЧЕМУ НЕ ДОЛЯ. Слов темы много — до восемнадцати, — и порог доли
 * [MIN_COVERAGE] отсеял бы всё: запись о рубанке не обязана поминать половину
 * всего, о чём говорили три хода. Поэтому доля считается (её видно в разборе
 * отбора), но на решение не влияет. По той же причине слова темы не сливаются
 * со словами вопроса в один запрос.
 *
 * Цена места берётся та же, что у вопроса: она про плату за место в запросе,
 * а не про то, откуда пришли слова, — крупный лог дорог в любом окне.
 */
internal fun scoreThemeCandidate(
    matched: Map<String, Int>,
    totalWords: Int,
    contentLength: Int
): CandidateScore =
    scoreCandidate(matched, totalWords, contentLength).copy(
        tooNarrow = matched.size < minOf(DolmenCircle.THEME_MIN_WORDS, totalWords)
    )
