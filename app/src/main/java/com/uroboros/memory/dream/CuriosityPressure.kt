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
 * [Result.concluded]. Вывод закрывает связь — спрашивать о ней и пробовать её
 * снова нечего; и сон другой ночи с теми же записями тоже разряжен (см.
 * [ConclusionKey]). Отброшенный вывод сон не разряжает. Спрошенный сон с
 * выводом считается как спрошенный, один раз.
 *
 * НО ДО РАССКАЗА ВЫВОД ЗАРЯЖЕН. Пока о выводе не рассказано, его набор записей
 * стоит в [Result.toTell] — для выхода «рассказать»
 * ([CuriosityAsk.decideTell]). Давлению и ряду он по-прежнему ничего не
 * даёт: рассказ — другой выход, а не повод спрашивать. Рассказан — значит
 * помечен так же, как спрошенный ([Dream.askedAt]), и метка у любого сна с
 * теми же записями закрывает рассказ для всех: вывод один на набор записей.
 *
 * НЕСКАЗАННОЕ — ОБРАТНАЯ СТОРОНА ПРУЖИНЫ. Давление выше — это то, что знает
 * владелец и не знает агент: владелец возвращается к записям сна. Несказанный
 * вывод — наоборот: агент знает, владелец нет. Поэтому у него свой счёт
 * ([Teller.piled]) только из сигналов агента: сколько раз агент сам вспоминал
 * сон ([Teller.recalled], вес [RECALLED_WEIGHT] — тот же сигнал, что в давлении)
 * и сколько ночей вывод пролежал несказанным ([Teller.nights], вес
 * [UNTOLD_NIGHT_WEIGHT]). Подхваты владельца в этот счёт не идут: слитые, они
 * рассказывали бы не в тему то, вокруг чего владелец и так ходит, а
 * вывод, к которому возвращался один агент, молчал бы. Подхваты остаются в
 * [Leader.contribution] — там они решают, к месту ли рассказ в теме сна.
 *
 * НЕСКАЗАННОЕ НЕ ОСТЫВАЕТ. Сны остывают, а разрыв между тем, что знает агент
 * и не знает владелец, пока агент молчит, держится. Поэтому окно
 * [WINDOW_NIGHTS] к выводам не применяется: вывод ждёт рассказа бессрочно, и
 * всплывает, когда разговор зайдёт в его тему, или когда накопится. Иначе
 * промолчать значило бы потерять навсегда.
 *
 * «Владелец не знает» здесь значит «не рассказано в разговоре»: что владелец
 * видел вывод на экране, отсюда не видно.
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
 *    выпадет из окна;
 *  - набор записей, о котором уже спрашивали до вывода, рассказа не получит:
 *    метка «спрошен» и метка «рассказан» — одна и та же;
 *  - у несказанного «вспоминал сам» — за всю жизнь снов с этими записями:
 *    счёт только растёт и не знает, было вспоминание до вывода или после;
 *  - ночь несказанного — любой проход сна (кнопка и сон сам — две ночи);
 *  - веса несказанного объявлены, а не измерены (см. [UNTOLD_NIGHT_WEIGHT]).
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

    /**
     * Вес ночи, которую вывод пролежал несказанным (см. «НЕСКАЗАННОЕ»).
     * Объявленное число, не подобранное: ночь весит меньше вспоминания агентом —
     * пролежать легче, чем вернуться самому. Перепроверять по строке
     * «Спросить: … рассказать: … накопилось», когда выводов станет больше.
     */
    const val UNTOLD_NIGHT_WEIGHT = 1

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
     * Принятый, ещё не рассказанный вывод [text] — см. «НО ДО РАССКАЗА» и
     * «НЕСКАЗАННОЕ». [leader] — сон самой поздней ночи с этими записями, его
     * [Leader.contribution] — подхваты и вспоминания всех снов с этими записями.
     *
     * @property nights сколько ночей прошло после ночи вывода.
     * @property recalled сколько раз агент вспоминал сны с этими записями.
     */
    data class Teller(val leader: Leader, val text: String, val nights: Int = 0, val recalled: Int = 0) {
        /** Сколько накопилось несказанного — только сигналы агента. */
        val piled: Int
            get() = RECALLED_WEIGHT * recalled + UNTOLD_NIGHT_WEIGHT * nights
    }

    /**
     * Итог: три слагаемых порознь (счета, не умноженные на вес) и лидер —
     * null, когда давление ноль.
     *
     * @property ranked все сны с вкладом больше нуля, в порядке вклада; лидер
     *   — первый в ряду. Ряд читают выводы ([ConclusionStep]).
     * @property concluded сколько снов окна разряжено принятым выводом.
     * @property toTell несказанные выводы любой давности, по одному на набор
     *   записей, первым — с наибольшим [Teller.piled].
     */
    data class Result(
        val pickedUp: Int,
        val recalled: Int,
        val own: Int,
        val leader: Leader?,
        val ranked: List<Leader> = emptyList(),
        val concluded: Int = 0,
        val toTell: List<Teller> = emptyList(),
    ) {
        val pressure: Int
            get() = PICKED_UP_WEIGHT * pickedUp + RECALLED_WEIGHT * recalled + OWN_WEIGHT * own
    }

    /**
     * Давление по снам [dreams] на момент [now].
     *
     * @param byId запись по номеру; null — записи нет, сон молчит.
     * @param concludedKeys ключи снов с принятым выводом (см. «РАЗРЯДКА»).
     * @param concludedTexts текст принятого вывода по набору записей
     *   ([ConclusionKey.dreamRecordIds]); без текста рассказывать нечего.
     * @param nightsAfterConclusion сколько ночей прошло после ночи вывода, по
     *   набору записей ([Teller.nights]).
     *
     * [dreams] — сны окна, а для несказанных выводов — все сны с их записями,
     * любой ночи, в том числе спрошенные: по ним видно, рассказан ли вывод.
     * Окно к ним не применяется (см. «НЕСКАЗАННОЕ НЕ ОСТЫВАЕТ»).
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
        concludedTexts: Map<String, String> = emptyMap(),
        nightsAfterConclusion: (String) -> Int = { 0 },
    ): Result {
        var pickedUp = 0
        var recalled = 0
        var own = 0
        var concluded = 0
        val concludedRecords = ConclusionKey.sameRecords(concludedKeys)
        var leader: Pair<Dream, List<Sticker>>? = null
        var leaderContribution = 0
        val stirred = mutableListOf<Leader>()
        val distinct = dreams.distinctBy { it.nightAt to it.recordIds }
        for (dream in distinct) {
            if (dream.nightAt < now - WINDOW_MS) continue
            val records = dream.ids().map(byId)
            if (records.any { Dream.silences(it) }) continue
            // Спрошенный сон разряжен: см. «РАЗРЯДКА» выше.
            if (dream.askedAt != null) {
                own += dream.answeredCount
                continue
            }
            if (dream.recordIds in concludedRecords) {
                // Сны с выводом приходят и нетронутыми (ради несказанного);
                // разряженным считается только тот, кому было что разряжать.
                if (dream.pickedUpCount > 0 || dream.recalledCount > 0) concluded++
                continue
            }
            pickedUp += dream.pickedUpCount
            recalled += dream.recalledCount
            val contribution = weightOf(dream)
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
            toTell = untold(distinct, byId, concludedTexts, nightsAfterConclusion),
        )
    }

    /**
     * Несказанные выводы (см. «НЕСКАЗАННОЕ»): по одному на набор записей с
     * текстом вывода, если ни один сон с этими записями не спрошен и не
     * рассказан и записи живы. Без окна и без требования вклада: несказанное
     * ждёт своего часа, а к месту ли оно — решает выход.
     */
    private fun untold(
        dreams: List<Dream>,
        byId: (Long) -> Sticker?,
        texts: Map<String, String>,
        nightsAfter: (String) -> Int,
    ): List<Teller> {
        val tellers = mutableListOf<Teller>()
        for ((recordIds, same) in dreams.groupBy { it.recordIds }) {
            val text = texts[recordIds] ?: continue
            if (same.any { it.askedAt != null }) continue
            val latest = same.maxByOrNull { it.nightAt } ?: continue
            val records = latest.ids().map(byId)
            if (records.any { Dream.silences(it) }) continue
            val live = records.map { it!! }
            val worth = same.sumOf { weightOf(it) }
            tellers += Teller(
                Leader(Brief(latest.kind, live.map { it.content }), worth, latest, live),
                text,
                nights = nightsAfter(recordIds),
                recalled = same.sumOf { it.recalledCount },
            )
        }
        return tellers.sortedWith(
            compareByDescending<Teller> { it.piled }
                .thenByDescending { it.leader.dream.nightAt }
                .thenBy { it.leader.dream.recordIds }
        )
    }

    /** Вклад сна: его подхваты и вспоминания с весами. Ответы о спрошенном сне сюда не входят (см. «РАЗРЯДКА»). */
    private fun weightOf(dream: Dream): Int =
        PICKED_UP_WEIGHT * dream.pickedUpCount + RECALLED_WEIGHT * dream.recalledCount

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
