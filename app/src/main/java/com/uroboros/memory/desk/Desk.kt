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
 * ПОКА ТОЛЬКО ПРИБОР. Модели отсюда не идёт ничего: строка «Доска:» в
 * «Подробно» и раздел «ДОСКА» в «Показать». Сначала видно, что доска собирает
 * и как часто пустеет, потом — подача.
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
 *    о чём.
 *
 * ПУСТОЙ ВИД НАЗЫВАЕТ ПРИЧИНУ. Доска, пустая потому, что нечего, иначе
 * неотличима от доски, которая сломана.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - обещаний не собирает — вид назван в приборе как несобираемый;
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

        return Projection(items, reasons)
    }

    fun window(p: Projection): List<Item> = p.items.take(WINDOW)

    const val PROMISES = "обещания — не собираются"
    const val NOT_FED = "модели не подаётся"

    /** Строка прибора. Печатается всегда; пустой вид — с причиной. */
    fun meter(p: Projection): String {
        val kinds = Kind.values().joinToString(" · ") { kind ->
            val n = p.items.count { it.kind == kind }
            if (n > 0) "${kind.label} $n" else "${kind.label} 0 — ${p.emptyReasons[kind] ?: "нет"}"
        }
        return "Доска: пунктов ${p.items.size} ($kinds · $PROMISES) · $NOT_FED"
    }

    /** Раздел «ДОСКА» в «Показать». */
    fun section(p: Projection): String = buildString {
        append("ДОСКА\n")
        append("Что у агента сейчас «на потом». Собирается заново при каждом показе из того, что уже хранится; ")
        append("модели пока не подаётся — только здесь.")
        val window = window(p)
        if (window.isEmpty()) {
            append("\n\nПусто.")
        } else {
            append("\n\nВ окно пошло бы (до $WINDOW):")
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
        append("\n").append(PROMISES)
    }
}
