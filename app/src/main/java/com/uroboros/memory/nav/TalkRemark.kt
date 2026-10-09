package com.uroboros.memory.nav

import com.uroboros.util.TextFold

/**
 * Замечание о самом разговоре — «Я спросил кто я, а не что я говорил», «это
 * говорил я», «Я имею в виду — вообще, не в этом разговоре». Часть
 * спектрометра: решается по сумме признаков предложения — разбора (лицо, глагол
 * или нет) и слов со словосочетаниями, как остальной разбор. Чистая логика.
 *
 * ЗАЧЕМ. В портрет собеседника ([Portrait]) идут его утверждения с «я». В
 * замечании о разговоре «я» тоже есть, и без этой проверки пересказ подаёт его
 * модели как факт о человеке: «ты спросил, кто ты, а не что ты говорил».
 * Портрет — единственный потребитель: в общем отборе такая запись — обычная
 * запись по словам вопроса, и вреда там нет.
 *
 * ПРИЗНАКИ И ВЕСА ([features]); предложение — замечание при сумме от
 * [THRESHOLD]:
 *  - «я» + глагол речи (корни [SPEECH_ROOTS]: спросить, говорить, ответить,
 *    повторить, уточнить, «иметь в виду»…) — 2: говорящий говорит о своём
 *    действии в разговоре;
 *  - глагол речи без «я» — 1;
 *  - существительное о речи (разговор, вопрос, ответ, реплика) — 1;
 *  - «в виду» — 1;
 *  - противопоставление «…, а не …» / «не …, а …» — 1: поправка;
 *  - отсылка «это / этом / так» в коротком предложении — 1: указывает на
 *    сказанное раньше;
 *  - отклик «понял / помню / ясно» в коротком предложении — 1;
 *  - начало-отклик («да», «нет», «ок», «ну», «ладно», «хорошо») в коротком —
 *    1;
 *  - нет глагола и есть «про / о / об» в коротком («Я про сны») — 1: уточнение
 *    без своего содержания.
 * Глагол — по таблице глаголов (PersonForm.verbs), прошедшему времени или
 * инфинитиву; таблица не загружена — по окончаниям.
 *
 * Признаки и веса — версия 0: объявлены, не подобраны, и заморожены. Правка —
 * новой версией и новым замером на слепой разметке владельца, иначе это
 * подгонка под те же данные.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - смысла не видит: короткий отклик о себе («Да, я помню это») снимается как
 *    замечание — на слепой разметке владелец счёл его словом о себе;
 *  - замечание без слов о речи и без признаков отклика («Я про сны» — один
 *    признак из двух нужных) проходит в портрет;
 *  - факт о себе со словом о речи и «я» («Я веду разговорный клуб») снимается
 *    ложно;
 *  - опечатку в корне («имкю в виду») не узнаёт — остаются другие признаки.
 */
object TalkRemark {

    /** С какой суммы весов предложение — замечание. Объявленное число. */
    const val THRESHOLD = 2

    /** Сколько слов — «короткое» предложение для признаков отклика. Объявленное число. */
    const val SHORT_WORDS = 5

    /** Сколько слов — «короткое» для отсылки «это». Объявленное число. */
    const val SHORT_WORDS_REFERENCE = 6

    /** Начала слов о самой речи: глаголы и существительные. */
    val SPEECH_ROOTS = listOf(
        "спрос", "спраш", "переспр", "говор", "сказ", "ответ", "отвеч", "вопрос",
        "реплик", "разговор", "повтор", "перефраз", "уточн", "имел", "имею",
    )

    /** Корни, слово с которыми без глагольной формы — существительное о речи. */
    private val SPEECH_NOUN_ROOTS = listOf("разговор", "вопрос", "реплик", "ответ")

    /** «Иметь в виду» — глагол, хотя окончания у него не глагольные. */
    private val HAVE_FORMS = setOf("имею", "имел", "имела")

    private val REFERENCE_WORDS = setOf("это", "этом", "так")

    private val UNDERSTOOD = setOf("понял", "поняла", "понимаю", "помню", "запомнил", "ясно", "понятно")

    private val REACTION_STARTS = setOf("да", "нет", "ок", "ага", "угу", "ну", "ладно", "хорошо")

    private val TOPIC_PREPOSITIONS = setOf("про", "о", "об")

    private val PAST = Regex("(л|ла|ли|ло|лся|лась)$")
    private val INFINITIVE = Regex("(ть|ться|ти|чь)$")
    private val NOT_LETTER = Regex("[^\\p{L}]+")
    private val IN_VIEW = Regex("(^|[^\\p{L}])в ?виду([^\\p{L}]|$)")
    private val BUT_NOT = Regex("(^|[^\\p{L}])а\\s+не([^\\p{L}]|$)")
    private val NOT_BUT = Regex("(^|[^\\p{L}])не[^\\p{L}].*,\\s*а([^\\p{L}]|$)")

    /** Признак и его вес, для прибора и проверки. */
    data class Feature(val name: String, val weight: Int)

    /** Замечание ли предложение о самом разговоре. */
    fun isRemark(sentence: String): Boolean = features(sentence).sumOf { it.weight } >= THRESHOLD

    /** Сработавшие признаки предложения (описание объекта). */
    fun features(sentence: String): List<Feature> {
        val text = TextFold.fold(sentence)
        val words = text.split(NOT_LETTER).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Feature>()

        val speech = words.filter { w -> SPEECH_ROOTS.any { w.startsWith(it) } }
        if (speech.isNotEmpty()) {
            val verbal = speech.any { it in HAVE_FORMS || isVerb(it) }
            val first = words.any { it in PersonForm.FIRST_PRONOUNS }
            when {
                verbal && first -> out += Feature("я + глагол речи", 2)
                verbal -> out += Feature("глагол речи", 1)
            }
            if (speech.any { w -> !isVerb(w) && SPEECH_NOUN_ROOTS.any { w.startsWith(it) } }) {
                out += Feature("слово о речи", 1)
            }
        }
        if (IN_VIEW.containsMatchIn(text)) out += Feature("«в виду»", 1)
        if (BUT_NOT.containsMatchIn(text) || NOT_BUT.containsMatchIn(text)) out += Feature("«а не»", 1)
        if (words.any { it in REFERENCE_WORDS } && words.size <= SHORT_WORDS_REFERENCE) out += Feature("отсылка «это»", 1)
        if (words.any { it in UNDERSTOOD } && words.size <= SHORT_WORDS) out += Feature("отклик «понял/помню»", 1)
        if (words.first() in REACTION_STARTS && words.size <= SHORT_WORDS) out += Feature("начало-отклик", 1)
        if (words.size <= SHORT_WORDS && words.none(::isVerb) && words.any { it in TOPIC_PREPOSITIONS }) {
            out += Feature("уточнение «про …» без глагола", 1)
        }
        return out
    }

    private fun isVerb(w: String): Boolean =
        PersonForm.verbs?.knowsVerb(w) == true ||
            (w.length > 3 && PAST.containsMatchIn(w)) ||
            INFINITIVE.containsMatchIn(w)
}
