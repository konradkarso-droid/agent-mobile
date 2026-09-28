package com.uroboros.memory.nav

import com.uroboros.memory.Sentences
import com.uroboros.util.ImperativeForm
import com.uroboros.util.TextFold

/**
 * О ком предложение — ПО ФОРМЕ: местоимения и окончания глаголов, без словаря.
 * Таблицу глаголов (см. [RetellTable]) определитель лица не использует — она
 * нужна только развороту смешанных записей; почему там таблица, а не
 * окончания, — в [Retelling].
 *
 * Лицо здесь — направление, а не человек: [Person.FIRST] — «тот, кто сказал»,
 * [Person.SECOND] — «тот, кому сказано». Кто это на самом деле, решают
 * координаты (см. [Coordinates]) от точки отправления.
 *
 * Запись делится на предложения через [Sentences.split]: куски — вид на
 * запись, сама запись остаётся целой. У записи получается набор ответов по
 * предложениям и их свёртка [RecordForm].
 *
 * Правила (все слова — после util.TextFold):
 *  - FIRST: местоимения [FIRST_PRONOUNS]; глагол 1-го лица единственного числа
 *    по окончанию — слово не короче [MIN_VERB_LENGTH] букв на -ю/-у (сюда же
 *    -аю/-яю/-ую) или возвратное -юсь/-усь («займусь», «надеюсь»), не стоящее
 *    сразу после предлога;
 *  - SECOND: местоимения [SECOND_PRONOUNS]; глагол на -ешь/-ишь (и «-ёшь»,
 *    приведённое к «-ешь»), слово не короче [MIN_SECOND_VERB_LENGTH] букв;
 *  - IMPERATIVE: первое слово — просьба ([firstWordIsRequest]). Это адресат, а не тема: «Назови цвета
 *    радуги» — просьба к собеседнику, а не рассказ о нём, поэтому SECOND не
 *    ставится;
 *  - WE_WITH_YOU: «наш…» или «мы/нас/нам/нами» вместе с SECOND в одном
 *    предложении («мы с тобой»). SECOND тогда поглощается;
 *  - UNCLEAR: голое «мы» — местоимение или глагол 1-го лица множественного
 *    числа на -ем/-им/-емся/-имся без SECOND («тренируемся», «сможем»): в
 *    живых записях это бывает владелец с клубом, а не с агентом. И прошедшее
 *    время мужского или женского рода (-л, -ла, -лся, -лась) в предложении
 *    без других лиц («спал», «уточнил»): у прошедшего времени лица нет;
 *  - NONE: ничего из перечисленного.
 *
 * Сомнение — в UNCLEAR: догадка без уверенности не выдаётся за ответ.
 *
 * ЧЕГО НЕ УМЕЕТ (по форме, а не по смыслу):
 *  - существительные и прилагательные на -у/-ю/-ую («Купил колодку», «новую»)
 *    дают ложное FIRST. Ошибка в сторону лишнего: запись владельца один ход
 *    не придёт на вопрос к агенту, вопрос получит адрес «владелец»;
 *  - прилагательные на -им/-ем («другим», «синем») дают ложное голое «мы»;
 *  - обобщённое «ты» («Всегда носи с собой полотенце») читается как «о
 *    собеседнике», хотя это о мире;
 *  - ирония и чужая речь («он сказал: я устал») — ошибка или UNCLEAR;
 *  - «надеюсь», «я думал» в записи об агенте делают её «об обоих»;
 *  - повелительное — только по первому слову предложения. Мягкий знак даёт
 *    ложное повелительное у первого слова-существительного или наречия
 *    («Теперь…», «Семь…») и не ловит повелительное на «-сь» («Брось»), —
 *    цена правила [looksSoftSignImperative].
 */
object PersonForm {

    enum class Person { FIRST, SECOND, WE_WITH_YOU, IMPERATIVE, NONE, UNCLEAR }

    /** Лица одного предложения. Пустым не бывает: нет ничего — [Person.NONE]. */
    data class SentenceForm(val sentence: String, val persons: Set<Person>)

    /** Лица записи по предложениям и свёртка. */
    data class RecordForm(val sentences: List<SentenceForm>) {
        private val all: Set<Person> = sentences.flatMapTo(HashSet()) { it.persons }

        /** Сказано о том, кто говорит (FIRST или «мы с тобой»). */
        val aboutSpeaker: Boolean get() = Person.FIRST in all || Person.WE_WITH_YOU in all

        /** Сказано о том, кому сказано (SECOND или «мы с тобой»). */
        val aboutAddressee: Boolean get() = Person.SECOND in all || Person.WE_WITH_YOU in all

        /** Хоть в одном предложении лицо не определить. */
        val unclear: Boolean get() = Person.UNCLEAR in all

        /** Есть просьба к собеседнику (повелительное). */
        val imperative: Boolean get() = Person.IMPERATIVE in all
    }

    /**
     * Местоимения, которые определитель читает как сигнал. Поиск те же слова
     * выбрасывает как шум (memory.STOP_WORDS в HourglassMemory): одни слова,
     * противоположная роль. Список лиц живёт здесь.
     */
    val FIRST_PRONOUNS = setOf(
        "я", "меня", "мне", "мной", "мною",
        "мой", "моя", "мое", "мои", "моего", "моей", "моему", "моим", "моими", "моих", "мою",
    )
    val SECOND_PRONOUNS = setOf(
        "ты", "тебя", "тебе", "тобой", "тобою",
        "твой", "твоя", "твое", "твои", "твоего", "твоей", "твоему", "твоим", "твоими", "твоих", "твою",
    )
    val WE_PRONOUNS = setOf("мы", "нас", "нам", "нами")
    val OUR_PRONOUNS = setOf(
        "наш", "наша", "наше", "наши", "нашего", "нашей", "нашему", "нашим", "нашими", "наших", "нашу",
    )

    /**
     * Предлоги темы: после них местоимение называет, о ком речь («обо мне»,
     * «про тебя», «о нас»). Правило целиком — в [topicPersons].
     */
    private val TOPIC_PREPOSITIONS = setOf("о", "об", "обо", "про")

    /**
     * О ком речь по обороту «предлог темы + местоимение»: «Что знаешь обо
     * мне?» — FIRST, «Что я говорил про тебя?» — SECOND, «о нас», «о наших
     * планах» — WE_WITH_YOU. Притяжательное сразу за предлогом («о моей
     * работе») считается так же: речь о вещи этого человека. Пустой набор —
     * оборота нет.
     *
     * Зачем отдельно от [of]. [of] собирает все лица предложения, и в вопросе
     * «Что знаешь обо мне?» глагол «знаешь» даёт SECOND наравне с «мне». Но
     * глагол здесь — рамка вопроса (кого спрашивают), а тему называет оборот с
     * предлогом. Это правило грамматики, объявленное, а не подобранное.
     *
     * Чего не умеет: оборот без предлога темы («Расскажи мне что-нибудь»,
     * «Как меня зовут?») сюда не попадает — там решает [of]; слово между
     * предлогом и местоимением («обо всём моём») рвёт оборот; «о себе» не
     * читается вовсе — возвратное указывает на подлежащее, и его лицо даёт [of].
     */
    fun topicPersons(text: String): Set<Person> {
        val words = TextFold.fold(text).split(NOT_LETTER).filter { it.isNotEmpty() }
        val out = HashSet<Person>()
        for (i in 1 until words.size) {
            if (words[i - 1] !in TOPIC_PREPOSITIONS) continue
            val w = words[i]
            when (w) {
                in FIRST_PRONOUNS -> out += Person.FIRST
                in SECOND_PRONOUNS -> out += Person.SECOND
                in WE_PRONOUNS, in OUR_PRONOUNS -> out += Person.WE_WITH_YOU
            }
        }
        return out
    }

    /** После предлога слово на -у/-ю — падеж существительного, а не глагол. */
    private val PREPOSITIONS = setOf(
        "в", "во", "на", "к", "ко", "по", "о", "об", "обо", "у", "с", "со", "за", "под", "над",
        "из", "от", "до", "для", "при", "про", "без", "через", "перед", "между",
    )

    /**
     * Служебные слова на -у/-ю и -ем/-им, которые окончание приняло бы за
     * глагол. Не словарь смысла — короткий список частых слов той же природы,
     * что STOP_WORDS.
     */
    private val NOT_VERBS = setOf(
        "почему", "потому", "поэтому", "сразу", "кому", "чему", "тому", "всему", "нему", "ему",
        "никому", "ничему", "внизу", "наверху", "вверху", "снизу", "сверху",
        "совсем", "затем", "зачем", "всем", "чем", "нем", "тем", "им",
    )

    /** Объявленные числа, не подобранные. */
    const val MIN_VERB_LENGTH = 4
    const val MIN_SECOND_VERB_LENGTH = 5
    const val MIN_WE_VERB_LENGTH = 5
    const val MIN_SOFT_SIGN_IMPERATIVE_LENGTH = 4

    private val NOT_LETTER = Regex("[^\\p{L}]+")

    fun of(text: String): RecordForm =
        RecordForm(Sentences.split(text).map { SentenceForm(it, ofSentence(it)) })

    fun ofSentence(sentence: String): Set<Person> {
        val words = TextFold.fold(sentence).split(NOT_LETTER).filter { it.isNotEmpty() }
        val out = HashSet<Person>()

        var first = false
        var second = false
        var bareWe = false
        var our = false
        var pastWithoutPerson = false
        for ((i, w) in words.withIndex()) {
            val afterPreposition = i > 0 && words[i - 1] in PREPOSITIONS
            when {
                w in FIRST_PRONOUNS -> first = true
                w in SECOND_PRONOUNS -> second = true
                w in WE_PRONOUNS -> bareWe = true
                w in OUR_PRONOUNS -> our = true
                w in NOT_VERBS -> Unit
                isFirstPersonVerb(w) && !afterPreposition -> first = true
                isSecondPersonVerb(w) -> second = true
                isWeVerb(w) -> bareWe = true
                isPersonalPast(w) -> pastWithoutPerson = true
            }
        }

        if (our || (bareWe && second)) {
            out += Person.WE_WITH_YOU
        } else {
            if (second) out += Person.SECOND
            if (bareWe) out += Person.UNCLEAR
        }
        if (first) out += Person.FIRST
        if (words.isNotEmpty() && isRequestWord(words.first())) out += Person.IMPERATIVE
        if (pastWithoutPerson && out.none { it == Person.FIRST || it == Person.SECOND || it == Person.WE_WITH_YOU }) {
            out += Person.UNCLEAR
        }
        if (out.isEmpty()) out += Person.NONE
        return out
    }

    /**
     * Первое слово предложения похоже на просьбу: повелительное по окончанию
     * (util.ImperativeForm) или на мягкий знак ([looksSoftSignImperative]).
     * Одно правило на определитель лица и разворот (см. [Retelling]).
     * Слово берётся после util.TextFold, дефис его рвёт («Когда-нибудь» → «когда»).
     */
    fun firstWordIsRequest(sentence: String): Boolean {
        val first = TextFold.fold(sentence).split(NOT_LETTER).firstOrNull { it.isNotEmpty() } ?: return false
        return isRequestWord(first)
    }

    private fun isRequestWord(w: String): Boolean =
        ImperativeForm.looksImperative(w) || looksSoftSignImperative(w)

    /**
     * Повелительное на мягкий знак («проверь», «ответь», «поставь») — правило
     * только этого определителя, первого слова предложения.
     *
     * Почему здесь, а не в util.ImperativeForm: там список общий со срочностью
     * запроса, и «-ь» поменял бы её. Здесь ошибка идёт в безопасную сторону:
     * ложное повелительное у предложения без лиц даёт адрес «не определён», то
     * есть отбор как до навигации — без зеркала, описания ночи и своей речи.
     * Пропущенное повелительное, наоборот, делает просьбу вопросом без лица, то
     * есть вопросом к агенту, и к просьбе приходит описание ночи.
     *
     * Исключения нужны не против любой ошибки, а против той, что отнимет у
     * вопроса к агенту его адрес: безличные вопросы, начатые с таких слов.
     *  - «-сь» — прошедшее на «-лось» («Снилось что-нибудь?», «Получилось?»):
     *    без исключения именно вопрос о сне лишился бы адреса «агент». Цена —
     *    «брось», «садись» не ловятся;
     *  - «-шь» — второе лицо («знаешь»), его и так ловит SECOND;
     *  - «-ть», кроме «-еть» — «есть», «опять» («Есть новости?»). «-еть»
     *    оставлено ради «ответь»; инфинитив на «-еть» первым словом
     *    («Смотреть…») даёт ложное повелительное, это безопасная сторона.
     */
    private fun looksSoftSignImperative(w: String): Boolean =
        w.length >= MIN_SOFT_SIGN_IMPERATIVE_LENGTH && w.endsWith("ь") &&
            !w.endsWith("шь") && !w.endsWith("сь") &&
            (!w.endsWith("ть") || w.endsWith("еть"))

    private fun isFirstPersonVerb(w: String): Boolean =
        w.length >= MIN_VERB_LENGTH && !w.endsWith("ому") && !w.endsWith("ему") &&
            (w.endsWith("у") || w.endsWith("ю") || w.endsWith("усь") || w.endsWith("юсь"))

    private fun isSecondPersonVerb(w: String): Boolean =
        w.length >= MIN_SECOND_VERB_LENGTH && (w.endsWith("ешь") || w.endsWith("ишь"))

    private fun isWeVerb(w: String): Boolean =
        w.length >= MIN_WE_VERB_LENGTH &&
            (w.endsWith("емся") || w.endsWith("имся") || w.endsWith("ем") || w.endsWith("им"))

    /** Прошедшее время мужского или женского рода: у «я/ты» оно такое же. */
    private fun isPersonalPast(w: String): Boolean =
        w.length >= MIN_VERB_LENGTH &&
            (PAST_ENDINGS.any { w.endsWith(it) })

    private val PAST_ENDINGS = listOf(
        "ал", "ял", "ил", "ел", "ыл", "ул",
        "ала", "яла", "ила", "ела", "ыла", "ула",
        "ался", "ялся", "ился", "елся", "улся",
        "алась", "ялась", "илась", "елась", "улась",
    )
}
