package com.uroboros.memory.desk

/**
 * Доска агента: что у него сейчас лежит «на потом» — то, о чём стоит сказать
 * собеседнику, когда будет к месту. Чистая логика: ни базы, ни Android.
 *
 * ДОСКА НЕ ХРАНИТ, А ПРОЕЦИРУЕТ. Пункт не записывается и не вычёркивается:
 * при каждом показе доска собирается заново из того, что уже лежит в базе
 * (итог последней ночи, выводы, решение любопытства). Поэтому у пункта нет
 * своего «закрыть»: он уходит, когда меняется то, из чего он собран, — новая
 * ночь сменяет последнюю, вопрос о сне спрошен. Отсутствие пункта значит
 * «сейчас нечего», а не «сделано».
 *
 * МОДЕЛИ ИДУТ ТОЛЬКО ОБЕЩАНИЯ — строками взгляда на приборы ([forAgent], llm.Glance):
 * когда агент сам глядит, на один ход, в ленту не ложатся. Остальные виды —
 * только прибор: строка «Доска:» в «Подробно» и раздел «ДОСКА» в «Показать».
 * Сначала видно, что доска собирает и как часто пустеет, потом — подача.
 *
 * ВИДЫ ([Kind]):
 *  - «не так» — шаг ночи прошёл и ничего не принял: темы снов или выводы.
 *    Строка модели — факт от первого лица, с моментом сна словами
 *    (ageForModel). Одного факта модели мало: без причины сказать она его
 *    теряет, поэтому при подаче рядом должна стоять причина — это решается
 *    вместе с подачей, не здесь;
 *  - «связи снов» — принятые выводы последней ночи, кроме молчащих (правило
 *    молчания одно с показом выводов, ConclusionView.Item.silent);
 *  - «вопрос» — любопытство готово спросить о сне. Строку модели здесь не
 *    строим: она живёт у CuriosityAsk, одна на оба пути; доска показывает,
 *    о чём;
 *  - «обещания» — что агент обещал за срок, со словами собеседника, на
 *    которые обещано, — и на экране, и в телеграме; телеграмные — с адресом:
 *    кому и где (Promises.To). Как они находятся и чего поиск не умеет — у
 *    [Promises] и gate.ChatPromises.
 *
 * ПУСТОЙ ВИД НАЗЫВАЕТ ПРИЧИНУ. Доска, пустая потому, что нечего, иначе
 * неотличима от доски, которая сломана.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - смотрит только на последнюю ночь: «не так» позапрошлой ночи исчезает с
 *    новой ночью, даже если та тоже ничего не приняла по другой причине;
 *  - окно ([WINDOW]) — первые пункты по порядку видов, а не выбор по
 *    ситуации: выбор по адресу реплики — дело подачи;
 *  - «ни одна тема не принята» не отличает «модель назвала не то» от «нечего
 *    было называть» — причины отказов лежат в отчёте ночи, не в базе.
 */
object Desk {

    enum class Kind(val label: String) {
        WRONG("не так"),
        LINK("связи снов"),
        QUESTION("вопрос"),
        PROMISE("обещания"),
    }

    /**
     * Пункт доски.
     *
     * @param forModel строка модели от первого лица; null — строку строит не
     *   доска (вопрос о сне, см. CuriosityAsk).
     * @param forOwner то же словами для владельца, для раздела «ДОСКА».
     */
    data class Item(val kind: Kind, val forModel: String?, val forOwner: String)

    /** Последняя ночь: начало прохода и колонка тем как есть (DreamNight.dreamTopics). */
    data class Night(val at: Long, val dreamTopics: String?)

    /**
     * Выводы последней ночи.
     *
     * @param tried сколько выводов пробовалось в эту ночь — принятых и отброшенных.
     * @param accepted тексты принятых, кроме молчащих.
     * @param acceptedSilent сколько принятых молчит.
     */
    data class NightConclusions(val tried: Int, val accepted: List<String>, val acceptedSilent: Int)

    sealed class Question {
        /** Любопытство готово спросить; [what] — о чём, как у прибора «Первым:». */
        data class Ask(val what: String) : Question()

        /** Не спросит; [reason] — почему, словами. */
        data class Refuse(val reason: String) : Question()
    }

    data class Inputs(
        /** null — ни одной ночи не было. */
        val night: Night?,
        /** Выводы ночи [night]; null — ночи нет. */
        val conclusions: NightConclusions?,
        val question: Question,
        /** Когда ночь была — словами для модели (ProvenanceLabels.ageForModel). */
        val ageForModel: (Long) -> String,
        /** Когда ночь была — для владельца (DreamView.moment). */
        val moment: (Long) -> String,
        /** Обещания агента ([Promises.find]). */
        val promises: Promises.Found,
        /** День обещания для модели: «30 октября». */
        val dayForModel: (Long) -> String,
    )

    /** Собранная доска: пункты по порядку видов и причина у каждого пустого вида. */
    data class Projection(val items: List<Item>, val emptyReasons: Map<Kind, String>)

    /**
     * Сколько пунктов пошло бы модели за раз. Объявленное число, не
     * подобранное: малой модели больше двух-трёх строк не удержать.
     */
    const val WINDOW = 3

    const val NO_NIGHTS = "ночей не было"

    fun project(i: Inputs): Projection {
        val items = ArrayList<Item>()
        val reasons = LinkedHashMap<Kind, String>()
        val night = i.night
        val con = i.conclusions

        // «Не так»: шаг ночи прошёл и ничего не принял.
        if (night == null) {
            reasons[Kind.WRONG] = NO_NIGHTS
        } else {
            val age = i.ageForModel(night.at)
            val at = i.moment(night.at)
            val quiet = ArrayList<String>()
            when {
                night.dreamTopics == null -> quiet += "шага тем не было"
                night.dreamTopics.isBlank() -> items += Item(
                    Kind.WRONG,
                    "Когда я спал $age, я не нашёл ни одной темы для своих снов.",
                    "ночь $at: ни одна тема снов не принята",
                )
                else -> quiet += "темы приняты"
            }
            when {
                con == null || con.tried == 0 -> quiet += "выводов не было"
                con.accepted.isEmpty() && con.acceptedSilent == 0 -> items += Item(
                    Kind.WRONG,
                    "Когда я спал $age, ни одна моя мысль о снах не прошла проверку.",
                    "ночь $at: ни один вывод не принят из ${con.tried}",
                )
                else -> quiet += "выводы приняты"
            }
            if (items.none { it.kind == Kind.WRONG }) reasons[Kind.WRONG] = "в последнюю ночь " + quiet.joinToString(", ")
        }

        // Связи снов: принятые выводы последней ночи.
        when {
            night == null -> reasons[Kind.LINK] = NO_NIGHTS
            con == null || con.tried == 0 -> reasons[Kind.LINK] = "выводов в последнюю ночь не было"
            con.accepted.isEmpty() && con.acceptedSilent > 0 ->
                reasons[Kind.LINK] = "принятые молчат — звено на проверке или удалено"
            con.accepted.isEmpty() -> reasons[Kind.LINK] = "в последнюю ночь ни один вывод не принят"
            else -> {
                val age = i.ageForModel(night.at)
                con.accepted.mapTo(items) { text ->
                    Item(Kind.LINK, "Когда я спал $age, я подумал, что $text.", "ночь ${i.moment(night.at)}: «$text»")
                }
            }
        }

        // Вопрос о сне.
        when (val q = i.question) {
            is Question.Ask -> items += Item(Kind.QUESTION, null, "спросить о сне: ${q.what}")
            is Question.Refuse -> reasons[Kind.QUESTION] = "любопытство — ${q.reason}"
        }

        // Обещания.
        when (val f = i.promises) {
            is Promises.Found.Silent -> reasons[Kind.PROMISE] = f.reason
            is Promises.Found.Ok -> {
                f.promises.mapTo(items) { promiseItem(it, i) }
                val notes = listOfNotNull(
                    f.undated.takeIf { it > 0 }?.let { "без времени хода $it" },
                    f.pressureSkipped.takeIf { it > 0 }?.let { "под нажимом не взято $it" },
                ) + f.unread
                if (f.promises.isEmpty()) reasons[Kind.PROMISE] = (listOf(NO_PROMISES) + notes).joinToString(", ")
            }
        }

        return Projection(items, reasons)
    }

    private fun promiseItem(p: Promises.Promise, i: Inputs): Item {
        val to = p.to
        val who = when {
            to == null -> if (p.together) "Обещали вместе" else "Обещал"
            p.together -> "Обещали вместе ${to.withWhom} в телеграме (${to.where})"
            else -> "Обещал ${to.dative} в телеграме (${to.where})"
        }
        val answer = p.asked?.let { " — в ответ на «$it»" }.orEmpty()
        val owner = buildString {
            append(i.moment(p.at)).append(" · ")
            to?.let { append("телеграм, ").append(it.where).append(", ").append(if (p.together) "вместе ${it.withWhom}" else it.dative).append(" · ") }
            if (p.together && to == null) append("вместе · ")
            p.asked?.let { append("на «").append(it).append("» → ") }
            append("«").append(p.sentence).append("» · до ").append(i.moment(p.at + Promises.TERM_MS))
        }
        return Item(Kind.PROMISE, "$who ${i.dayForModel(p.at)}: «${p.sentence}»$answer", owner)
    }

    fun window(p: Projection): List<Item> = p.items.take(WINDOW)

    const val NO_PROMISES = "за ${Promises.DAYS} дн. обещаний нет"
    const val NOT_FED = "модели — только обещания при взгляде на приборы"

    /**
     * Строки обещаний для взгляда агента на приборы (llm.Glance): до [WINDOW]
     * новых первыми и сколько осталось за окном. Пустота сказана словами:
     * «нет обещаний» и «не видны» для модели — разное.
     */
    fun forAgent(p: Projection): List<String> {
        val all = p.items.filter { it.kind == Kind.PROMISE }
        if (all.isEmpty()) {
            val why = p.emptyReasons[Kind.PROMISE]
            return listOf(
                if (why == null || why.startsWith(NO_PROMISES)) "Обещаний за ${Promises.DAYS} дн. не нашлось."
                else "Обещания мне сейчас не видны: $why.",
            )
        }
        val shown = all.take(WINDOW).mapNotNull { it.forModel }
        val rest = all.size - WINDOW
        return if (rest > 0) shown + "Ещё обещаний за ${Promises.DAYS} дн.: $rest." else shown
    }

    /** Строка прибора. Печатается всегда; пустой вид — с причиной. */
    fun meter(p: Projection): String {
        val kinds = Kind.values().joinToString(" · ") { kind ->
            val n = p.items.count { it.kind == kind }
            if (n > 0) "${kind.label} $n" else "${kind.label} 0 — ${p.emptyReasons[kind] ?: "нет"}"
        }
        return "Доска: пунктов ${p.items.size} ($kinds) · $NOT_FED"
    }

    /** Раздел «ДОСКА» в «Показать». */
    fun section(p: Projection): String = buildString {
        append("ДОСКА\n")
        append("Что у агента сейчас «на потом». Собирается заново при каждом показе из того, что уже хранится. ")
        append("Модели идут только обещания — когда агент сам глядит на приборы; остальное — только здесь.")
        append("\n\nАгент видит при взгляде на приборы:")
        for (line in forAgent(p)) append("\n").append(line)
        val window = window(p)
        if (window.isEmpty()) {
            append("\n\nПусто.")
        } else {
            append("\n\nЦеликом доска модели не подаётся; пошло бы (до $WINDOW):")
            for (item in window) {
                append("\n").append(item.forModel ?: "[${item.kind.label}] ${item.forOwner}")
            }
            append("\n\nВсе пункты:")
            for (item in p.items) append("\n[").append(item.kind.label).append("] ").append(item.forOwner)
        }
        if (p.emptyReasons.isNotEmpty()) {
            append("\n\nПустые виды:")
            for ((kind, why) in p.emptyReasons) append("\n").append(kind.label).append(": ").append(why)
        }
    }
}
