package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.Sticker
import com.uroboros.memory.StickerDao

/**
 * Ассоциация к ответу: какие записи принести модели через сны последней ночи.
 *
 * СОН ПРИНОСИТ ЗАПИСИ, А НЕ СЕБЯ. Отобранный сон отдаёт к ответу свои записи,
 * которых нет среди записей ответа ([Offer.brought]); к модели они приходят
 * обычными записями, со своей подписью источника, как любая найденная (см.
 * ProvenanceLabels.recordForModel). Строк сна в разговоре нет: рассказанный
 * сон владелец подхватывал, подхват давил на любопытство, любопытство
 * спрашивало о том же сне — петля, и слово «снилось» модель начинала
 * переносить на обычные воспоминания. Сон остаётся ночной работой, днём
 * действует только его результат — связь записей.
 *
 * СОН ВСПЛЫВАЕТ ЧЕРЕЗ ЗАПИСИ ОТВЕТА, А НЕ ЧЕРЕЗ ПОИСК. Отбирается только сон,
 * в котором есть хотя бы одна запись, уже отобранная к вопросу. Принесённое
 * приходит ассоциацией к найденному, а не отдельным источником: иначе оно
 * спорило бы с отбором за внимание модели.
 *
 * ПРИНОСИТСЯ ТОЛЬКО ТО, ЧТО ДОБАВЛЯЕТ. Сон, все звенья которого уже стоят в
 * ответе, нового не несёт и не отбирается. Из отобранных каждый следующий
 * обязан принести запись, которой не принесли предыдущие: пачка фраз,
 * набранных подряд, снится «все со всеми», и без этого правила её пересказы
 * заняли бы всю квоту.
 *
 * ПОРЯДОК. Короче цепочка — раньше, при равной длине — по виду и строке
 * номеров (строкой, не числом: «8,10» идёт раньше «8,9»; для отбора это всё
 * равно, важна только неизменность).
 * Это «проще — раньше», как у плетения, и детерминированно: одна и та же
 * память даёт один и тот же отбор. Выбора «лучшего» сна здесь нет, и признака
 * для него тоже нет.
 *
 * МОЛЧАНИЕ. Сон со скрытым, отвергнутым или удалённым звеном не отбирается
 * целиком — то же правило, что у показа, см. [Dream.silences]. Такие сны
 * считаются отдельно, чтобы прибор отличал их от «сна не было».
 *
 * НИКОГО НЕ ГРЕЕТ. Записи читаются через [StickerDao.getAll], без отметки
 * обращения: запись, пришедшая в запрос по ассоциации, не считается
 * найденной, и слой её не меняется. Иначе отобранный сон грел бы свои записи,
 * те чаще попадали бы в отбор, а с ними чаще всплывали бы те же сны —
 * самонакачка.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - только последняя ночь. Более старые сны к ответу не отбираются — это
 *    пока и есть их остывание (продолжение вспомненного сна — у [DreamRiver]);
 *  - модель не знает, что запись пришла ассоциацией, а не поиском: подпись у
 *    неё та же, что у найденной. Чем связаны запись ответа и принесённая,
 *    модели не сказано;
 *  - одна короткая запись служит мостом многим снам, и ассоциация тянет её
 *    чаще других. Правила против этого нет; прибор показывает, какая запись
 *    чаще всех в снах ночи ([hub]);
 *  - вся память читается целиком на каждый ответ. При сотнях записей это
 *    ничто, при десятках тысяч придётся менять.
 */
class DreamRecall(
    private val dreams: DreamDao,
    private val served: DreamServedDao,
    private val stickers: StickerDao,
) {

    /** Вход с экрана; отдельный конструктор выше — для подделок в тестах. */
    constructor(context: Context) : this(
        MemoryDatabase.getInstance(context).dreamDao(),
        MemoryDatabase.getInstance(context).dreamServedDao(),
        MemoryDatabase.getInstance(context).stickerDao(),
    )

    /**
     * Отбор к ответу, в котором стоят записи [answerIds]. Базу не меняет.
     * Самая частая запись ночи ([Offer.hub]) считается при любом исходе, где
     * ночь есть: прибор показывает её и тогда, когда к ответу ничего не
     * подошло.
     */
    suspend fun offer(answerIds: Set<Long>): Offer {
        val night = dreams.lastNight() ?: return Offer(nightAt = null, silence = Silence.NO_NIGHT)
        val rows = dreams.ofNight(night.nightAt)
        val byId = stickers.getAll().associateBy { it.id }
        val hub = hub(rows, byId)
        if (answerIds.isEmpty()) return Offer(nightAt = night.nightAt, silence = Silence.NO_RECORDS, hub = hub)
        return pick(night.nightAt, rows, byId, answerIds, QUOTA).copy(hub = hub)
    }

    /**
     * Отметить поданными. Звать только для снов, чьё принесённое
     * действительно ушло в движок (см. [Dream.servedCount]).
     */
    suspend fun markServed(picked: List<Picked>, at: Long) {
        for (p in picked) served.markServed(p.dream.nightAt, p.dream.recordIds, at)
    }

    /**
     * Сон, прошедший отбор: живые записи его цепочки по порядку и [brought] —
     * те из них, что принёс к ответу именно он (нет среди записей ответа и не
     * принесены снами, отобранными раньше).
     */
    data class Picked(val dream: Dream, val records: List<Sticker>, val brought: List<Sticker> = emptyList())

    /** Почему не принесено ничего. */
    enum class Silence {
        /** Ночь не проходила ни разу. */
        NO_NIGHT,

        /** К ответу не нашлось записей — сну не за что зацепиться. */
        NO_RECORDS,

        /** Ночь есть, записи есть, но ни один сон не подошёл. */
        NOTHING_FITS,
    }

    /**
     * Запись, которая чаще всех встречается в снах ночи, и в скольких снах.
     * Прибор, не механизм: отбор его не читает.
     */
    data class Hub(val record: Sticker, val dreams: Int)

    /**
     * Итог отбора. [fitting] — сколько снов подошло до квоты и до правила «каждый
     * следующий приносит новое»; [silenced] — сколько касались записей ответа,
     * но молчат из-за скрытого звена; [hub] — см. [Hub], null — в ночи нет ни
     * одного говорящего сна.
     */
    data class Offer(
        val nightAt: Long?,
        val picked: List<Picked> = emptyList(),
        val fitting: Int = 0,
        val silenced: Int = 0,
        val silence: Silence? = null,
        val hub: Hub? = null,
    ) {
        /**
         * Записи, принесённые к ответу: записи отобранных снов, которых нет
         * среди записей ответа, без повторов, в порядке отбора.
         */
        val brought: List<Sticker> get() = picked.flatMap { it.brought }
    }

    companion object {

        /**
         * Сколько снов отбирается к одному ответу. Стартовое число, объявленное,
         * а не подобранное; верно, пока прибор не показывает, что «подходило»
         * стабильно больше отобранного. Перепроверяется строкой прибора [meter].
         */
        const val QUOTA = 2

        /** Отбор без базы — ради проверки обычным тестом. */
        fun pick(
            nightAt: Long,
            rows: List<Dream>,
            byId: Map<Long, Sticker>,
            answerIds: Set<Long>,
            quota: Int,
        ): Offer {
            var silenced = 0
            val fitting = mutableListOf<Pair<Dream, List<Long>>>()
            for (row in rows) {
                val ids = row.ids()
                if (ids.none { it in answerIds }) continue
                if (ids.any { Dream.silences(byId[it]) }) {
                    silenced++
                    continue
                }
                if (ids.all { it in answerIds }) continue
                fitting += row to ids
            }
            fitting.sortWith(
                compareBy<Pair<Dream, List<Long>>>({ it.second.size }, { it.first.kind }, { it.first.recordIds })
            )

            val brought = mutableSetOf<Long>()
            val picked = mutableListOf<Picked>()
            for ((row, ids) in fitting) {
                if (picked.size >= quota) break
                val fresh = ids.filter { it !in answerIds && it !in brought }.distinct()
                if (fresh.isEmpty()) continue
                brought += fresh
                picked += Picked(row, ids.map { byId.getValue(it) }, fresh.map { byId.getValue(it) })
            }
            return Offer(
                nightAt = nightAt,
                picked = picked,
                fitting = fitting.size,
                silenced = silenced,
                silence = if (picked.isEmpty()) Silence.NOTHING_FITS else null,
            )
        }

        /**
         * Какая живая запись встречается в наибольшем числе снов ночи [rows] и в
         * скольких. Молчащий сон (см. [Dream.silences]) не считается вовсе:
         * к ответу он не отбирается, и тянуть свои записи не может. При равенстве
         * — меньший номер, чтобы одна и та же ночь давала одну и ту же строку.
         * null — говорящих снов в ночи нет.
         */
        fun hub(rows: List<Dream>, byId: Map<Long, Sticker>): Hub? {
            val counts = HashMap<Long, Int>()
            for (row in rows) {
                val ids = row.ids().distinct()
                if (ids.isEmpty() || ids.any { Dream.silences(byId[it]) }) continue
                for (id in ids) counts[id] = (counts[id] ?: 0) + 1
            }
            val best = counts.entries.minWithOrNull(
                compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key }
            ) ?: return null
            return Hub(byId.getValue(best.key), best.value)
        }

        /**
         * Строка ли это сна. Новые ходы строк сна не несут (см. шапку); нужна
         * показу ленты для старых ходов: лента на диске хранит их с тем текстом,
         * с каким они ушли в модель, строки сна лежат там одним списком с
         * записями, и слитый счёт «записей 3» мог бы значить «2 записи и сон».
         *
         * Узнаются обе подписи, какими сон подавался: [ProvenanceLabels.DREAM_FOR_MODEL]
         * и более ранняя [EARLIER_DREAM_LABEL].
         */
        fun isDreamLine(text: String): Boolean =
            listOf(ProvenanceLabels.DREAM_FOR_MODEL, EARLIER_DREAM_LABEL).any { label ->
                text.startsWith("$label:") || text.startsWith("$label,")
            }

        /**
         * Подпись сна, которой он подавался модели прежде, чем подписи стали
         * речью от лица агента (см. шапку [ProvenanceLabels]). Нужна только
         * [isDreamLine] — новые строки с ней не собираются.
         */
        const val EARLIER_DREAM_LABEL = "Тебе снилось"

        /**
         * Строка прибора. Печатается при любом исходе: прибор, который
         * появляется только при удаче, неотличим от сломанного.
         *
         * [alreadyInRibbon] — сколько из принесённых записей уже лежит в ленте
         * с прошлых ходов и второй раз в реплику не кладётся.
         */
        fun meter(offer: Offer, alreadyInRibbon: Int): String = buildString {
            append("Ассоциации: ")
            when (offer.silence) {
                Silence.NO_NIGHT -> {
                    append("ночей ещё не было")
                    return@buildString
                }
                Silence.NO_RECORDS -> append("к ответу нет записей, зацепиться не за что")
                Silence.NOTHING_FITS -> append("к этим записям не подошёл ни один сон")
                null -> {
                    append("снов подходило ").append(offer.fitting)
                    append(" · записей принесено ").append(offer.brought.size - alreadyInRibbon)
                    if (alreadyInRibbon > 0) append(" · уже в ленте ").append(alreadyInRibbon)
                }
            }
            if (offer.silenced > 0) {
                append(" · молчат из-за скрытых записей: ").append(offer.silenced)
            }
            offer.nightAt?.let { append(" · ночь ").append(DreamView.moment(it)) }
            offer.hub?.let { hub ->
                append(" · чаще всех в снах ночи: «").append(DreamView.short(hub.record.content))
                append("» — в ").append(hub.dreams).append(if (hub.dreams.endsWithOne()) " сне" else " снах")
            }
        }

        /** «в 1 сне», «в 21 сне», но «в 11 снах». */
        private fun Int.endsWithOne(): Boolean = this % 10 == 1 && this % 100 != 11
    }
}
