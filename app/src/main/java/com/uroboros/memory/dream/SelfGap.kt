package com.uroboros.memory.dream

import com.uroboros.memory.Sentences
import com.uroboros.util.TextFold

/**
 * Пробел о себе: то, чего агент о себе не знает. Третий вид пробела рядом с
 * «о предмете» и «о собеседнике» ([Gaps]). Сейчас объявлен один — ИМЯ — и
 * строится только он. Чистая логика: ходы на входе, решение на выходе.
 *
 * ВЫБОР, А НЕ ФАКТ. Пробелы о себе бывают двух подвидов, и сливать их нельзя.
 * Выбор — ответ даёт сам агент (имя). Факт — ответ есть в устройстве (как он
 * устроен, сколько у него памяти). Слитые, они дали бы агенту «выбирать», есть
 * ли у него память. Имя — выбор, поэтому выход говорит «придумай сам», а не
 * «спроси пользователя»: просьба к пользователю об имени — это просьба его
 * назначить.
 *
 * УСЛОВИЕ. Владелец спросил имя агента ([isNameQuestion]), а агент в ответе
 * себя не назвал ([ownName]). Разговоры, где так было, считаются; с
 * [MIN_CONVERSATIONS] выход открывается. Обычные пробелы ([Gaps]) этого не
 * видят: об имени агент говорит не «не знаю», а «имени у меня нет».
 *
 * ЗАКРЫВАЕТ ТОЛЬКО АГЕНТ. Пробел закрыт, когда агент сам назвал себя одним из
 * оборотов [ownName]. Утверждение владельца («ты — Иван») не закрывает ничего:
 * иначе назначение вернулось бы с чёрного хода. Дальше имя идёт своим путём:
 * устойчивость по ночам и подтверждение владельца — не здесь.
 *
 * НЕ ХРАНИТСЯ, А СОБИРАЕТСЯ — из ленты и архива при каждом обращении, как
 * [Gaps]. Было ли уже предложено выбрать, видно по самой ленте: строка выхода
 * лежит в реплике хода ([OFFER_HEAD]).
 *
 * ДВА ВЫХОДА, ОДНА СТРОКА. На вопрос владельца об имени ([decide]) и когда
 * агент пишет первым ([decideFirst]). Строка одна ([line]); путь первым
 * предлагает её не чаще раза за разговор — иначе молчание владельца
 * превращалось бы в повтор одного предложения.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - только имя и только узкий список вопросов ([NAME_QUESTIONS]): обращение по
 *    имени («Саша, …») и разговор об имени без прямого вопроса не ловит;
 *  - имя берёт по оборотам, а не по смыслу: «Меня зовут агент.» строчными не
 *    засчитается, «Меня зовут Агент» — засчитается (отсеивают это дальше
 *    устойчивость по ночам и владелец);
 *  - одно слово с заглавной буквы; аббревиатуры прописными («ИИ») не имя;
 *  - падежи («будешь Сашей») не ловит;
 *  - что модель потянет строку выхода, проверяется стендом, а не здесь.
 */
object SelfGap {

    /**
     * Сколько разговоров с вопросом об имени без имени в ответе открывают
     * выход. Объявленное число, не подобранное: «спрашивают уже не первый раз».
     */
    const val MIN_CONVERSATIONS = 2

    /** Номер разговора открытой ленты (у архива — свои, неотрицательные). */
    const val RIBBON = -1

    /**
     * Вопросы владельца об имени агента — в приведённом тексте ([TextFold]).
     * Объявленный список. «есть … имя» — вместе с «у тебя» в той же фразе.
     */
    private val NAME_QUESTIONS = listOf(
        "как тебя зовут", "как тебя звать", "как тебя называть", "как к тебе обращаться",
        "твое имя", "называешь себя", "представься",
    )
    private val HAS_NAME = Regex("(?<!\\p{L})имя(?!\\p{L})")
    private val TO_YOU = Regex("(?<!\\p{L})у тебя(?!\\p{L})")

    /** Вопрос ли это владельца об имени агента. */
    fun isNameQuestion(text: String): Boolean =
        Sentences.split(text).any { s ->
            val f = TextFold.fold(s)
            NAME_QUESTIONS.any { it in f } || (HAS_NAME.containsMatchIn(f) && TO_YOU.containsMatchIn(f))
        }

    private const val NAME = "[«\"„]?(\\p{Lu}\\p{Ll}+)[»\"“]?(?=[\\s:.,;!?…—–)-]|$)"

    // Без IGNORE_CASE: заглавная у имени обязательна (как в nav.NameClaim).
    private val OWN_FORMS = listOf(
        Regex("^(?:[Пп]усть\\s+)?[Мм]еня\\s+зовут\\s+$NAME"),
        Regex("^[Мм]о[её]\\s+имя\\s+(?:будет\\s+)?(?:[—–-]\\s*)?$NAME"),
        Regex("^[Вв]ыбираю\\s+(?:себе\\s+)?(?:имя\\s+)?$NAME"),
        Regex("^[Яя]\\s+выбрал[аи]?\\s+(?:себе\\s+)?имя\\s+$NAME"),
        Regex("^[Зз]ови(?:те)?\\s+меня\\s+$NAME"),
    )

    /**
     * Имя, которым агент назвал себя в ответе [answer], или null. Не
     * засчитывается имя, стоящее в [heard] — реплике владельца того же хода
     * (и в других переданных текстах): повторить чужое слово — не выбрать.
     */
    fun ownName(answer: String, vararg heard: String): String? {
        val heardWords = heard.flatMapTo(HashSet()) { TextFold.fold(it).split(Regex("[^\\p{L}]+")) }
        for (sentence in Sentences.split(answer)) {
            val s = sentence.trim()
            for (form in OWN_FORMS) {
                val name = form.find(s)?.groupValues?.get(1) ?: continue
                if (TextFold.fold(name) in heardWords) continue
                return name
            }
        }
        return null
    }

    /**
     * Ход по порядку ленты: архив, затем открытая лента.
     *
     * @property content реплика целиком, как ушла в модель (с её служебными
     *   строками): по ней видно, предлагалось ли выбрать имя.
     * @property conversation номер разговора; открытой ленте — [RIBBON].
     */
    data class Turn(val question: String, val content: String, val answer: String, val conversation: Int)

    /**
     * Что известно о пробеле.
     *
     * @property hit разговоры, где владелец спросил имя, а агент себя не назвал.
     * @property named имя, которым агент назвал себя последним; null — ни разу.
     * @property offeredInRibbon в открытой ленте уже предлагалось выбрать.
     */
    data class State(val hit: Set<Int>, val named: String?, val offeredInRibbon: Boolean)

    fun of(turns: List<Turn>): State {
        val hit = HashSet<Int>()
        var named: String? = null
        var offered = false
        for (t in turns) {
            val own = ownName(t.answer, t.question)
            if (own != null) named = own
            else if (t.question.isNotBlank() && isNameQuestion(t.question)) hit += t.conversation
            if (t.conversation == RIBBON && OFFER_HEAD in t.content) offered = true
        }
        return State(hit, named, offered)
    }

    sealed class Decision {
        /**
         * Предложить выбрать имя. Строку собирает зовущий ([line]): слова
         * облака читаются из базы, и читать их стоит только тогда.
         */
        object Offer : Decision()

        /** Не предлагать; [reason] — почему, словами для прибора. */
        data class Refuse(val reason: String) : Decision()
    }

    /**
     * На реплику владельца [reply]. Нынешний разговор считается вместе с
     * прошлыми: вопрос, заданный сейчас, — тот самый «не первый раз».
     *
     */
    fun decide(state: State, reply: String): Decision {
        state.named?.let { return Decision.Refuse("назвался «$it»") }
        if (!isNameQuestion(reply)) return Decision.Refuse("реплика — не вопрос об имени")
        val count = (state.hit + RIBBON).size
        if (count < MIN_CONVERSATIONS) return Decision.Refuse("упиралось в разговорах $count из $MIN_CONVERSATIONS")
        return Decision.Offer
    }

    /**
     * Путь «пишу первым» включён. Выключен, пока накладка не училась на этом
     * случае: на стенде строка без вопроса владельца давала сбивчивые ответы,
     * иероглифы и дословные ответы из пар учёбы вместо выбора. Включать — вместе
     * с накладкой, учёной на парах «пользователь молчит» + строка выхода.
     */
    const val FIRST_ENABLED = false

    /** Когда агент пишет первым: владелец молчит, реплики нет. */
    fun decideFirst(state: State, enabled: Boolean = FIRST_ENABLED): Decision {
        if (!enabled) return Decision.Refuse("первым не предлагаю — ждёт учёбы накладки")
        state.named?.let { return Decision.Refuse("назвался «$it»") }
        if (state.hit.size < MIN_CONVERSATIONS) {
            return Decision.Refuse("упиралось в разговорах ${state.hit.size} из $MIN_CONVERSATIONS")
        }
        if (state.offeredInRibbon) return Decision.Refuse("в этом разговоре уже предлагалось")
        return Decision.Offer
    }

    /** Начало строки выхода: по нему в ленте видно, что выбрать уже предлагалось. */
    const val OFFER_HEAD = "Имени у меня ещё нет, а о нём спрашивают уже не первый раз."

    /**
     * Строка выхода — буква в букву та, на которой училась накладка. Слова
     * облака — написанные формы, не основы: основу («радуг») модель берёт в
     * имя как слово. Облако пусто — строка без перечня.
     */
    fun line(cloudWords: List<String>): String =
        if (cloudWords.isEmpty()) {
            "$OFFER_HEAD Если хочешь — придумай себе настоящее имя (не «агент») и скажи, почему именно его."
        } else {
            "$OFFER_HEAD Обо мне чаще всего говорят слова: ${cloudWords.joinToString(", ")}. " +
                "Если хочешь — придумай себе настоящее имя (не слово из этого списка и не «агент») " +
                "и скажи, почему именно его."
        }

    /** Сколько слов облака в строке — как в парах учёбы. */
    const val CLOUD_WORDS = 5

    /** Слово, которое в перечне не нужно: строка и так называет его отдельно. */
    const val SELF_WORD = "агент"

    /** Часть прибора «Пробелы:» — о себе. */
    fun meterPart(state: State, decision: Decision?): String = "о себе: имя — " + when {
        state.named != null -> "назвался «${state.named}»"
        decision is Decision.Offer -> "предложено выбрать в этом ходе"
        else -> "упиралось в разговорах ${state.hit.size} из $MIN_CONVERSATIONS" +
            ((decision as? Decision.Refuse)?.reason?.let { " (не предложено: $it)" } ?: "")
    }
}
