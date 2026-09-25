package com.uroboros.memory.dream

import com.uroboros.memory.Sticker

/**
 * Пружина любопытства: насколько её сжали сны последних ночей и какой сон
 * сжал сильнее всех.
 *
 * ТОЛЬКО ПРУЖИНА. Она знает, насколько сжата, но не знает, куда выпускать:
 * выходы живут отдельно и читают это давление, а не живут внутри него.
 * Первый выход — «спросить владельца» ([CuriosityAsk]).
 *
 * АРИФМЕТИКА, А НЕ СУЖДЕНИЕ. Давление — сумма трёх слагаемых по снам окна:
 *  - подхвачено владельцем ([Dream.pickedUpCount], правило — [DreamPickup]),
 *    вес [PICKED_UP_WEIGHT];
 *  - вспомнено агентом ([Dream.recalledCount], правило — [AgentRecall]),
 *    вес [RECALLED_WEIGHT];
 *  - результат собственного действия агента, вес [OWN_WEIGHT]. Единственный
 *    источник — ответы владельца о спрошенном сне ([Dream.answeredCount]).
 * Слагаемые хранятся и показываются порознь: слитые в одно число, они не
 * сказали бы, чей сигнал сжал пружину, и веса потеряли бы смысл.
 *
 * РАЗРЯДКА. Спрошенный сон ([Dream.askedAt]) своими прежними счетами
 * «подхвачено» и «вспомнено» давление больше не даёт и лидером не бывает:
 * без этого выход спрашивал бы о том же сне на каждом ходе. От него идут
 * только ответы — в слагаемое «своё», а не в «подхвачено»: иначе агент,
 * спросив, сам раскачивал бы сигнал, который считается сигналом владельца.
 *
 * Сон с принятым выводом ([Conclusion], ключи — [ConclusionKey]) разряжен так
 * же: своих счетов давлению не даёт, лидером и в ряду не бывает, считается в
 * [Result.concluded]. Вывод закрывает связь — тянуть её дальше нечего.
 * Отброшенный вывод сон не разряжает. Спрошенный сон с выводом считается как
 * спрошенный, один раз.
 *
 * ОКНО — [WINDOW_NIGHTS] ночи по часам: сон считается, если его ночь началась
 * не раньше чем [WINDOW_MS] назад. Ночи здесь — сутки, а не проходы сна:
 * сны остывают со временем, даже если телефон неделю не спал.
 *
 * МОЛЧАЩИЙ СОН НЕ СЧИТАЕТСЯ вовсе, какие бы счета у него ни были, — правило
 * одно с показом и подачей ([Dream.silences]).
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - веса объявлены, а не измерены: они говорят только «подхват владельца
 *    сильнее вспоминания агента», настоящие числа — из замеров;
 *  - не остывает внутри окна: сон позавчерашней ночи весит столько же,
 *    сколько вчерашний, и выпадает из счёта целиком на границе окна;
 *  - разрядка только у спрошенного сна и сна с принятым выводом: сон,
 *    который выход не выбрал и вывод не закрыл, копит дальше, пока не
 *    выпадет из окна.
 */
object CuriosityPressure {

    /** Объявленное число, не подобранное: сны быстро остывают. */
    const val WINDOW_NIGHTS = 3

    const val WINDOW_MS = WINDOW_NIGHTS * 24L * 60 * 60 * 1000

    /**
     * Веса — черновые, объявленные: 3, 2, 1 задают только порядок «владелец
     * сильнее агента, агент сильнее своего действия». Настоящие — из замеров,
     * по нескольким ночам этого прибора.
     */
    const val PICKED_UP_WEIGHT = 3
    const val RECALLED_WEIGHT = 2
    const val OWN_WEIGHT = 1

    /** Сон для показа: вид и тексты звеньев по порядку. */
    data class Brief(val kind: String, val texts: List<String>)

    /**
     * Сон, давший наибольший вклад, и сам вклад. [records] — живые записи
     * его цепочки по порядку: по ним выход строит строку для модели.
     */
    data class Leader(
        val brief: Brief,
        val contribution: Int,
        val dream: Dream,
        val records: List<Sticker>,
    )

    /**
     * Итог: три слагаемых порознь (счета, не умноженные на вес) и лидер —
     * null, когда давление ноль.
     *
     * @property ranked все сны с вкладом больше нуля, в порядке вклада; лидер
     *   — первый в ряду. Ряд читают выводы ([ConclusionStep]).
     * @property concluded сколько снов окна разряжено принятым выводом.
     */
    data class Result(
        val pickedUp: Int,
        val recalled: Int,
        val own: Int,
        val leader: Leader?,
        val ranked: List<Leader> = emptyList(),
        val concluded: Int = 0,
    ) {
        val pressure: Int
            get() = PICKED_UP_WEIGHT * pickedUp + RECALLED_WEIGHT * recalled + OWN_WEIGHT * own
    }

    /**
     * Давление по снам [dreams] на момент [now].
     *
     * @param byId запись по номеру; null — записи нет, сон молчит.
     * @param concludedKeys ключи снов с принятым выводом (см. «РАЗРЯДКА»).
     *
     * Лидер при равном вкладе — сон более поздней ночи, затем по строке
     * номеров: выбор детерминирован, «лучшего» из равных здесь нет. Тем же
     * порядком стоит весь ряд [Result.ranked].
     */
    fun measure(
        dreams: List<Dream>,
        byId: (Long) -> Sticker?,
        now: Long,
        concludedKeys: Set<ConclusionKey> = emptySet(),
    ): Result {
        var pickedUp = 0
        var recalled = 0
        var own = 0
        var concluded = 0
        var leader: Pair<Dream, List<Sticker>>? = null
        var leaderContribution = 0
        val stirred = mutableListOf<Leader>()
        for (dream in dreams.distinctBy { it.nightAt to it.recordIds }) {
            if (dream.nightAt < now - WINDOW_MS) continue
            val records = dream.ids().map(byId)
            if (records.any { Dream.silences(it) }) continue
            // Спрошенный сон разряжен: см. «РАЗРЯДКА» выше.
            if (dream.askedAt != null) {
                own += dream.answeredCount
                continue
            }
            if (ConclusionKey.of(dream) in concludedKeys) {
                concluded++
                continue
            }
            pickedUp += dream.pickedUpCount
            recalled += dream.recalledCount
            val contribution = PICKED_UP_WEIGHT * dream.pickedUpCount + RECALLED_WEIGHT * dream.recalledCount
            if (contribution <= 0) continue
            stirred += Leader(Brief(dream.kind, records.map { it!!.content }), contribution, dream, records.map { it!! })
            val current = leader?.first
            val better = current == null || contribution > leaderContribution ||
                (contribution == leaderContribution &&
                    (dream.nightAt > current.nightAt ||
                        (dream.nightAt == current.nightAt && dream.recordIds < current.recordIds)))
            if (better) {
                leader = dream to records.map { it!! }
                leaderContribution = contribution
            }
        }
        return Result(
            pickedUp = pickedUp,
            recalled = recalled,
            own = own,
            leader = leader?.let { (dream, records) ->
                Leader(Brief(dream.kind, records.map { it.content }), leaderContribution, dream, records)
            },
            ranked = stirred.sortedWith(
                compareByDescending<Leader> { it.contribution }
                    .thenByDescending { it.dream.nightAt }
                    .thenBy { it.dream.recordIds }
            ),
            concluded = concluded,
        )
    }

    /**
     * Строки прибора. Первая печатается всегда, и при нуле: пружина, которая
     * видна только сжатой, неотличима от сломанной. Вторая — лидер, только
     * когда давление больше нуля.
     *
     * [pickedThisTurn] — сны, подхваченные репликой этого хода, [answeredThisTurn]
     * — спрошенные сны, о которых эта реплика ответила; стоят в первой строке,
     * чтобы владелец сверил, верно ли понято.
     */
    fun meter(
        result: Result,
        pickedThisTurn: List<Brief> = emptyList(),
        answeredThisTurn: List<Brief> = emptyList(),
    ): String = buildString {
        append("Любопытство: давление ").append(result.pressure)
        append(" — подхвачено ").append(result.pickedUp).append(" (×").append(PICKED_UP_WEIGHT).append(")")
        append(", вспомнено ").append(result.recalled).append(" (×").append(RECALLED_WEIGHT).append(")")
        append(", своё ").append(result.own).append(" (×").append(OWN_WEIGHT).append(")")
        // Печатается всегда, и при нуле: см. первую строку KDoc.
        append(" · разряжено выводом: ").append(result.concluded)
        append(" · за ").append(WINDOW_NIGHTS).append(" ночи")
        if (pickedThisTurn.isNotEmpty()) {
            append(if (pickedThisTurn.size == 1) " · в этом ходе подхвачен сон " else " · в этом ходе подхвачены сны ")
            append(pickedThisTurn.joinToString("; ") { "«${DreamView.brief(it.kind, it.texts)}»" })
        }
        if (answeredThisTurn.isNotEmpty()) {
            append(" · в этом ходе ответ о спрошенном сне ")
            append(answeredThisTurn.joinToString("; ") { "«${DreamView.brief(it.kind, it.texts)}»" })
        }
        val leader = result.leader
        if (result.pressure > 0 && leader != null) {
            append("\n  сильнее всех сжал (вклад ").append(leader.contribution).append("), ")
            append(DreamView.column(leader.brief.kind, leader.brief.texts, indent = "    "))
        }
    }
}
