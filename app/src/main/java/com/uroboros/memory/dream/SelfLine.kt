package com.uroboros.memory.dream

import com.uroboros.memory.RiskTrigger

/**
 * Строка «о себе», которую агент предлагает ночью: рамка, решение, что делать
 * этой ночью, разбор ответа модели в тему и проверка темы. Всё правило лежит
 * здесь и закрепляется тестами; ни базы, ни Android — по образцу
 * [UnpromptedLeader]. Модель зовёт и пишет в базу [SelfLineStep], строку в
 * очереди и прибор читает [UnpromptedLeaderGauge].
 *
 * ОТКУДА СТРОКА. Основание — только запись-кандидат [UnpromptedLeader], то есть
 * касания владельца без подсказки. Модель называет лишь тему этой записи;
 * фразу собирает код из [FRAME] и темы. Модель не пишет о себе ничего сама: всё,
 * что она может, — назвать несколько слов, и эти слова обязаны стоять в самой
 * записи ([checkTopic]).
 *
 * ПУТЬ СТРОКИ. Рождается красной ([com.uroboros.memory.Prism.IDENTITY_TAG]) и
 * сразу на проверке: пока человек не принял, её не видит ни отбор, ни стена.
 * После «принять» встаёт в стену (LlmEngine.wallFor) и становится подсказкой:
 * её тема больше не засчитывается в касания без подсказки (правило —
 * у com.uroboros.memory.promptedStems).
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - проверка темы — совпадение основ слов, а не смысла: тема из слов записи
 *    может пересказывать её криво, и это увидит только человек в очереди;
 *  - основы темы у готовой строки ([topicStems]) — основы строки минус основы
 *    рамки: слово темы, совпавшее основой со словом рамки («время»,
 *    «собеседник»), из основ темы выпадет;
 *  - «чаще всего говорили» — утверждение о касаниях, то есть о совпадении
 *    слов вопросов с записью, а не о том, что владельцу важно (см.
 *    [UnpromptedLeader], «ЧЕГО НЕ УМЕЕТ»).
 */
object SelfLine {

    /**
     * Рамка строки. Единственное место её текста: сборка ([compose]), основы
     * темы ([topicStems]) и все прочие берут её отсюда.
     */
    const val FRAME = "Долгое время мы с собеседником чаще всего говорили"

    /**
     * Сколько принятых строк «о себе» может стоять в стене. Объявленное
     * владельцем число, не подобранное: стена идёт в каждый запрос, и каждая
     * строка стоит токенов на каждом ходу.
     */
    const val WALL_CEILING = 3

    /** Самая длинная тема в словах. Объявленное число, не подобранное. */
    const val MAX_TOPIC_WORDS = 6

    /** Самая длинная тема в знаках. Объявленное число, не подобранное. */
    const val MAX_TOPIC_CHARS = 60

    /** Гласные, перед которыми предлог «о» становится «об». */
    private val VOWELS = setOf('а', 'о', 'у', 'э', 'и', 'ы')

    /** Строка целиком: рамка, «о» или «об», тема, точка. */
    fun compose(topic: String): String {
        val first = topic.firstOrNull()?.lowercaseChar()
        val preposition = if (first != null && first in VOWELS) "об" else "о"
        return "$FRAME $preposition $topic."
    }

    /**
     * Основы темы у готовой строки: основы строки минус основы [FRAME]. Нужны
     * правилу подсказки — брать основы всей строки нельзя: слова рамки
     * сделали бы подсказанным почти любое касание.
     */
    fun topicStems(line: String): Set<String> =
        RiskTrigger.significantStems(line) - RiskTrigger.significantStems(FRAME)

    // ---- Решение, что делать этой ночью ----

    /** Строка по основанию-лидеру, которую человек уже решил. */
    enum class Settled {
        ACCEPTED,
        REJECTED,
    }

    /**
     * Что известно этой ночью.
     *
     * @property standing первое место по касаниям, см. [UnpromptedLeader.standing].
     * @property lastNights лидеры последних ночей, от новых к старым, см.
     *   [UnpromptedLeader.isCandidate].
     * @property pendingId номер строки «о себе», ждущей решения; null — такой нет.
     * @property acceptedCount сколько строк «о себе» принято (не на проверке и
     *   не отвергнуто).
     * @property leaderSettled была ли у лидера уже своя строка, решённая
     *   человеком; null — не было.
     */
    data class Inputs(
        val standing: UnpromptedLeader.Standing,
        val lastNights: List<Long?>,
        val pendingId: Long?,
        val acceptedCount: Int,
        val leaderSettled: Settled?,
    )

    /** Один из трёх исходов ночи. У молчания всегда есть причина словами. */
    sealed class Decision {
        /** Назвать тему [baseId] и, если проверка прошла, сохранить строку. */
        data class Propose(val baseId: Long) : Decision()

        /**
         * Лидер есть, кандидатом ещё не стал: назвать тему и проверить, но
         * ничего не сохранять — итог только в отчёт ночи. Проба показывает
         * заранее, что сделает модель с этой записью, когда та дорастёт.
         */
        data class Probe(val baseId: Long) : Decision()

        data class Silent(val reason: String) : Decision()
    }

    /**
     * Решение ночи. Порядок проверок: есть ли лидер, кандидат ли он, и только
     * для кандидата — ждёт ли решения другая строка, полна ли стена, была ли
     * у лидера своя строка. Проба ничего не сохраняет, поэтому ни очередь, ни
     * потолок ей не помеха.
     */
    fun decide(inputs: Inputs): Decision {
        val leaderId = inputs.standing.leaderId ?: return Decision.Silent("лидера нет")
        if (!UnpromptedLeader.isCandidate(leaderId, inputs.lastNights)) return Decision.Probe(leaderId)
        inputs.pendingId?.let { return Decision.Silent("строка №$it ждёт решения") }
        if (inputs.acceptedCount >= WALL_CEILING) {
            return Decision.Silent("в стене уже ${inputs.acceptedCount} из $WALL_CEILING")
        }
        return when (inputs.leaderSettled) {
            Settled.ACCEPTED -> Decision.Silent("по №$leaderId строка уже была принята")
            Settled.REJECTED -> Decision.Silent("по №$leaderId строка уже была отвергнута")
            null -> Decision.Propose(leaderId)
        }
    }

    // ---- Разбор ответа модели и проверка темы ----

    sealed class Parsed {
        data class Topic(val text: String) : Parsed()
        data class Refused(val reason: String) : Parsed()
    }

    /** Кавычки, которые снимаются с краёв темы. */
    private const val QUOTES = "\"'«»„“”‘’`"

    /**
     * Ответ модели — в тему. Берётся первая непустая строка; снимаются
     * кавычки, точка в конце и ведущие «о »/«об », если модель их всё же
     * написала. Отказ — пустая тема, длиннее [MAX_TOPIC_WORDS] слов или
     * [MAX_TOPIC_CHARS] знаков.
     */
    fun parseTopic(answer: String): Parsed {
        var topic = answer.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        topic = topic.trim { it.isWhitespace() || it in QUOTES }
            .trimEnd('.', ' ')
            .trim { it.isWhitespace() || it in QUOTES }
        val lead = Regex("^(об|о)\\s+", RegexOption.IGNORE_CASE).find(topic)
        if (lead != null) topic = topic.substring(lead.range.last + 1).trim()
        if (topic.isEmpty()) return Parsed.Refused("модель не назвала тему")
        val words = topic.split(Regex("\\s+")).size
        if (words > MAX_TOPIC_WORDS) {
            return Parsed.Refused("тема длиннее $MAX_TOPIC_WORDS слов: «$topic»")
        }
        if (topic.length > MAX_TOPIC_CHARS) {
            return Parsed.Refused("тема длиннее $MAX_TOPIC_CHARS знаков: «$topic»")
        }
        return Parsed.Topic(topic)
    }

    /**
     * Взята ли тема из самой записи: основы темы непусты и целиком входят в
     * основы текста записи-основания. null — прошла; иначе причина словами.
     * Лишние основы называются: по ним видно, что модель досочинила.
     */
    fun checkTopic(topic: String, baseId: Long, baseText: String): String? {
        val own = RiskTrigger.significantStems(topic)
        if (own.isEmpty()) return "в теме «$topic» нет значимых слов"
        val extra = own - RiskTrigger.significantStems(baseText)
        if (extra.isEmpty()) return null
        return "основ ${extra.sorted().joinToString(", ") { "«$it»" }} нет в №$baseId"
    }

    // ---- Слова для отчёта ночи и экрана ----

    /** Начало строки итога — по нему итог узнаётся в отчёте ночи. */
    const val OUTCOME_HEAD = "Строка о себе: "

    fun silentOutcome(reason: String): String = "${OUTCOME_HEAD}молчу — $reason"

    fun notOfferedOutcome(reason: String): String =
        "Строку о себе не предлагаю: " + reason.replaceFirstChar { it.lowercaseChar() }

    fun proposedOutcome(lineId: Long, baseId: Long, topic: String, similarTo: String?): String =
        "${OUTCOME_HEAD}предложена №$lineId по №$baseId «$topic» — ждёт решения в очереди" +
            (similarTo?.let { " · похожа на «${preview(it)}»" } ?: "")

    fun droppedOutcome(reason: String): String = "${OUTCOME_HEAD}отброшено — $reason"

    fun probeOutcome(baseId: Long, topic: String?, failure: String?): String =
        if (failure == null) "Проба пересказа по №$baseId: прошла «$topic»"
        else "Проба пересказа по №$baseId: не прошла — $failure"

    /**
     * Строка у записи в очереди, которую предложил агент. Числа читаются при
     * показе, а не сняты в момент предложения, — отсюда «сейчас».
     *
     * @param baseText текст основания; null — основание не найдено.
     * @param touches касаний без подсказки у основания сейчас.
     * @param nightsLed в скольких из последних ночей основание было лидером.
     */
    fun originLine(baseId: Long, baseText: String?, touches: Int, nightsLed: Int): String {
        if (baseText == null) return "предложил агент · №$baseId не найдена"
        return "предложил агент · опирается на №$baseId «${preview(baseText)}» · " +
            "касаний без подсказки сейчас $touches · " +
            "лидером ночей $nightsLed из ${UnpromptedLeader.NIGHTS_WINDOW}"
    }

    /**
     * Хвост прибора «Нажитое о себе:» (к строке [UnpromptedLeader.meter]).
     * «в стене K из 3» печатается всегда — этот ноль значим; остальное только
     * когда есть.
     *
     * @param pending строка на проверке и её основание (основание может быть null).
     * @param lastOutcome итог шага последней ночи, где шаг был.
     */
    fun meterTail(pending: Pair<Long, Long?>?, accepted: Int, lastOutcome: String?): String =
        buildString {
            pending?.let { (id, base) ->
                append(" · на проверке: №").append(id)
                base?.let { append(" по №").append(it) }
            }
            append(" · в стене ").append(accepted).append(" из ").append(WALL_CEILING)
            lastOutcome?.let {
                append(" · последняя ночь: ").append(cut(it.removePrefix(OUTCOME_HEAD), OUTCOME_CHARS))
            }
        }

    /**
     * Сколько знаков итога ночи показывать в приборе. Объявленное число, не
     * подобранное: чтобы строка прибора оставалась строкой.
     */
    const val OUTCOME_CHARS = 120

    private fun preview(text: String): String = cut(text, UnpromptedLeader.PREVIEW_CHARS)

    private fun cut(text: String, chars: Int): String {
        val flat = text.replace("\n", " ")
        return if (flat.length > chars) flat.take(chars) + "…" else flat
    }
}
