package com.uroboros.initiative

import com.uroboros.llm.ConversationJournal
import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.SentenceKind
import com.uroboros.memory.Sentences
import com.uroboros.util.TextFold

/**
 * Инициатива в разговоре: у кого ход после реплики владельца — у него или у
 * агента. Чистая логика, без Android и без состояния: считается по ленте при
 * каждой отрисовке, как эхо (см. llm.EchoCheck), поэтому переживает перезапуск
 * вместе с лентой и второго места, где может разойтись с ней, не имеет.
 *
 * ПОКА ТОЛЬКО ПРИБОР. Строка «Инициатива:» в «Подробно»; модели отсюда не идёт
 * ничего, и «писать первым» (InitiativeDecision) это не читает. Сначала видно,
 * как часто и на чём определение ошибается, потом — подача.
 *
 * ПРАВИЛА — ПО ТИПУ РЕПЛИКИ, как у Whittaker и Stenton (1988), применённых
 * Walker и Whittaker (1990): вопрос и просьба оставляют ход у спросившего;
 * поддакивание отдаёт его собеседнику; утверждение оставляет его у говорящего,
 * если это не ответ на вопрос — ответ возвращает ход спросившему. По порядку
 * проверки ([classify]):
 *  - ход начат агентом (пустой `question`) — у агента;
 *  - в реплике «?» — у владельца (вопрос);
 *  - хоть одно предложение — вопрос или просьба по memory.SentenceKind,
 *    точно или сомнительно, — у владельца: сомнение здесь решается в сторону
 *    владельца (см. ниже), и сомнительная просьба подписана так в приборе;
 *  - вся реплика из поддакиваний, не длиннее [PROMPT_MAX_WORDS] слов — у агента;
 *  - прошлый ответ агента кончался вопросом и реплика делит с этим вопросом
 *    значимое слово — ответ, ход у агента; не делит — владелец заговорил о
 *    своём («отбито»), ход у него. Что считается общим словом — у
 *    [SHARED_PREFIX_MIN];
 *  - иначе — утверждение, ход у владельца.
 *
 * СОМНИТЕЛЬНОЕ — ВЛАДЕЛЬЦУ. Ошибка «ход у агента, а владелец спрашивал» даст,
 * когда будет подача, агента, который давит своим поверх чужого вопроса; ошибка
 * в другую сторону — пассивного агента, каким он был и так. Поэтому агенту ход
 * отходит только по уверенному признаку, а всё, что не опознано, остаётся у
 * владельца.
 *
 * ХОД — НЕ СОГЛАСИЕ. Поддакивание отдаёт агенту ход в РАЗГОВОРЕ и ничего не
 * разрешает делать. Действие (прогон, запуск) начинается только с явного
 * согласия на названные агентом цель и план — отдельным актом, одно согласие
 * на одно предложение. Здесь согласие не распознаётся вовсе.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - определяет форму, а не смысл: «ну да, конечно» с насмешкой — поддакивание;
 *  - вид предложения — по SentenceKind, со всеми его промахами (там же);
 *  - ответ на вопрос агента, не повторивший ни одного его значимого слова
 *    («Как день?» — «Устал»), читается как своё — ход у владельца;
 *  - общее слово ищется по началу основы (см. [SHARED_PREFIX_MIN]), и
 *    совпадение бывает случайным: «стол» узнаётся в «столица». Тогда ход
 *    отходит агенту по ложному признаку — это обратная сторона того, что
 *    «спал» узнаётся в «спалось»;
 *  - реплика из одних знаков (смайлик) — утверждение;
 *  - списки слов ниже — объявленные, не подобранные; перепроверять по строке
 *    «Инициатива:» на живых репликах.
 */
object InitiativeHolder {

    enum class Holder { OWNER, AGENT }

    /**
     * Сколько слов может быть в поддакивании. Объявленное число: «ну да,
     * хорошо» — три; длиннее — уже своё высказывание.
     */
    const val PROMPT_MAX_WORDS = 3

    /**
     * С какой длины основа, стоящая началом другой основы, считается тем же
     * словом. Объявленное число, не подобранное.
     *
     * ЗАЧЕМ. Основы режутся по окончаниям, и возвратное «-ось», «-ся» не
     * срезается: «спалось» даёт основу «спалос», «спал» — «спал». При точном
     * совпадении ответ «Спал неплохо» на «Как спалось?» читался как своё.
     * Стеммер при этом не трогается: по нему же работают правило противоречия
     * и отбор записей, и их пороги измерены на нынешней резке. Поэтому
     * послабление живёт только здесь.
     *
     * Четыре — самая короткая основа из живого случая («спал»). Короче
     * совпадения случайны слишком часто: «спа» — начало и «спасибо».
     */
    const val SHARED_PREFIX_MIN = 4

    /** Сколько знаков реплики показывать в строке прибора. Объявленное число. */
    const val FRAGMENT_CHARS = 30

    /** Слова поддакивания: короткое согласие, «ничего особенного», спасибо. */
    val PROMPT_WORDS = setOf(
        "да", "нет", "ага", "угу", "ок", "окей", "ясно", "понятно", "хорошо",
        "ладно", "нормально", "ничего", "особенного", "конечно", "верно",
        "точно", "так", "ну", "спасибо", "пожалуй", "согласен", "согласна",
    )

    /**
     * У кого ход после последнего хода ленты.
     *
     * @param reason почему, словами для прибора.
     * @param fragment начало реплики владельца; null — ход начат агентом.
     * @param agentStreak сколько последних ходов подряд ход у агента.
     */
    data class Reading(
        val holder: Holder,
        val reason: String,
        val fragment: String?,
        val agentStreak: Int,
    )

    /** Чтение по ленте; null — ходов нет. */
    fun read(history: List<ConversationJournal.Turn>): Reading? {
        if (history.isEmpty()) return null
        val verdicts = history.mapIndexed { i, turn ->
            classify(history.getOrNull(i - 1)?.agentContent, turn.question)
        }
        val streak = verdicts.takeLastWhile { it.first == Holder.AGENT }.size
        val (holder, reason) = verdicts.last()
        val question = history.last().question
        return Reading(holder, reason, if (question.isBlank()) null else fragment(question), streak)
    }

    /**
     * У кого ход после реплики владельца [question], если перед ней агент
     * ответил [previousAgent] (null — ответа не было). Порядок — в KDoc
     * объекта.
     */
    fun classify(previousAgent: String?, question: String): Pair<Holder, String> {
        if (question.isBlank()) return Holder.AGENT to "агент заговорил первым"
        if ('?' in question) return Holder.OWNER to "вопрос"
        val readings = Sentences.split(question).map { SentenceKind.of(it) }
        if (readings.any { it.kind == SentenceKind.Kind.QUESTION }) return Holder.OWNER to "вопрос без «?»"
        readings.firstOrNull { it.kind == SentenceKind.Kind.REQUEST }?.let {
            return Holder.OWNER to if (it.isSureRequest) "просьба" else "просьба, сомнительно: ${it.why}"
        }
        val words = words(question)
        if (words.isNotEmpty() && words.size <= PROMPT_MAX_WORDS && words.all { it in PROMPT_WORDS }) {
            return Holder.AGENT to "поддакивание"
        }
        val asked = previousAgent?.let(::questionStems).orEmpty()
        if (asked.isNotEmpty()) {
            return if (sharesWord(RiskTrigger.significantStems(question), asked)) {
                Holder.AGENT to "ответ на вопрос агента"
            } else {
                Holder.OWNER to "своё после вопроса агента — отбито"
            }
        }
        return Holder.OWNER to "утверждение"
    }

    /**
     * Строка прибора. Печатается всегда, и счёт подряд — тоже при нуле:
     * молчащий прибор неотличим от сломанного.
     */
    fun meter(reading: Reading?): String {
        if (reading == null) return "Инициатива: разговора нет — определять не по чему"
        val who = if (reading.holder == Holder.AGENT) "у агента" else "у владельца"
        val quote = reading.fragment?.let { " («$it»)" } ?: ""
        return "Инициатива: $who — ${reading.reason}$quote · у агента ходов подряд: ${reading.agentStreak}"
    }

    /** Есть ли у реплики общее слово с вопросом агента; правило — у [SHARED_PREFIX_MIN]. */
    private fun sharesWord(reply: Set<String>, asked: Set<String>): Boolean =
        reply.any { r ->
            asked.any { a ->
                r == a || (minOf(r.length, a.length) >= SHARED_PREFIX_MIN && (r.startsWith(a) || a.startsWith(r)))
            }
        }

    /** Значимые основы вопросов из ответа агента; пусто — вопросов не было. */
    private fun questionStems(answer: String): Set<String> =
        Sentences.split(answer)
            .filter { it.trimEnd().endsWith('?') }
            .flatMapTo(HashSet()) { RiskTrigger.significantStems(it) }

    private val NOT_LETTER = Regex("[^\\p{L}]+")

    private fun words(text: String): List<String> =
        TextFold.fold(text).split(NOT_LETTER).filter { it.isNotEmpty() }

    private fun fragment(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= FRAGMENT_CHARS) flat else flat.take(FRAGMENT_CHARS).trimEnd() + "…"
    }
}
