package com.uroboros.initiative

import com.uroboros.llm.ConversationJournal
import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sentences

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
 *  - первое слово вопросительное или есть частица «ли» — у владельца;
 *  - первое слово — просьба («расскажи», «объясни»…) — у владельца;
 *  - вся реплика из поддакиваний, не длиннее [PROMPT_MAX_WORDS] слов — у агента;
 *  - прошлый ответ агента кончался вопросом и реплика делит с этим вопросом
 *    значимое слово — ответ, ход у агента; не делит — владелец заговорил о
 *    своём («отбито»), ход у него;
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
 *  - вопрос без «?», без вопросительного слова в начале и без «ли» читается
 *    как утверждение;
 *  - ответ на вопрос агента, не повторивший ни одного его значимого слова
 *    («Как день?» — «Устал»), читается как своё — ход у владельца;
 *  - «что-то устал» начинается с вопросительного слова и читается как вопрос;
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

    /** Сколько знаков реплики показывать в строке прибора. Объявленное число. */
    const val FRAGMENT_CHARS = 30

    /** Слова поддакивания: короткое согласие, «ничего особенного», спасибо. */
    val PROMPT_WORDS = setOf(
        "да", "нет", "ага", "угу", "ок", "окей", "ясно", "понятно", "хорошо",
        "ладно", "нормально", "ничего", "особенного", "конечно", "верно",
        "точно", "так", "ну", "спасибо", "пожалуй", "согласен", "согласна",
    )

    /** Вопросительные слова — в начале реплики. */
    val QUESTION_WORDS = setOf(
        "что", "как", "почему", "зачем", "когда", "где", "куда", "откуда",
        "кто", "чей", "чья", "чье", "сколько", "какой", "какая", "какое",
        "какие", "каким", "разве", "неужели",
    )

    /** Просьбы — в начале реплики. */
    val REQUEST_WORDS = setOf(
        "расскажи", "скажи", "объясни", "покажи", "напомни", "давай",
        "сделай", "помоги", "найди", "подскажи", "посмотри", "проверь",
        "напиши", "придумай",
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
        val words = words(question)
        val first = words.firstOrNull()
        if (first in QUESTION_WORDS || "ли" in words) return Holder.OWNER to "вопрос без «?»"
        if (first in REQUEST_WORDS) return Holder.OWNER to "просьба"
        if (words.isNotEmpty() && words.size <= PROMPT_MAX_WORDS && words.all { it in PROMPT_WORDS }) {
            return Holder.AGENT to "поддакивание"
        }
        val asked = previousAgent?.let(::questionStems).orEmpty()
        if (asked.isNotEmpty()) {
            return if (RiskTrigger.significantStems(question).any { it in asked }) {
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

    /** Значимые основы вопросов из ответа агента; пусто — вопросов не было. */
    private fun questionStems(answer: String): Set<String> =
        Sentences.split(answer)
            .filter { it.trimEnd().endsWith('?') }
            .flatMapTo(HashSet()) { RiskTrigger.significantStems(it) }

    private val NOT_LETTER = Regex("[^\\p{L}]+")

    private fun words(text: String): List<String> =
        text.lowercase().replace('ё', 'е').split(NOT_LETTER).filter { it.isNotEmpty() }

    private fun fragment(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= FRAGMENT_CHARS) flat else flat.take(FRAGMENT_CHARS).trimEnd() + "…"
    }
}
