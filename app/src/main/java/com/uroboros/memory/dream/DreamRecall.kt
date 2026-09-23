package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.Sticker
import com.uroboros.memory.StickerDao

/**
 * Какие сны подать модели к ответу — и строки, которыми они уйдут.
 *
 * СОН ВСПЛЫВАЕТ ЧЕРЕЗ ЗАПИСИ ОТВЕТА, А НЕ ЧЕРЕЗ ПОИСК. Подаётся только сон,
 * в котором есть хотя бы одна запись, уже отобранная к вопросу. Сон приходит
 * ассоциацией к найденному, а не отдельным источником: иначе он спорил бы с
 * отбором за внимание модели.
 *
 * ПОДАЁТСЯ ТОЛЬКО ТО, ЧТО ДОБАВЛЯЕТ. Сон, все звенья которого уже стоят в
 * ответе, нового не несёт и не подаётся. Из поданных каждый следующий обязан
 * принести запись, которой не принесли предыдущие: пачка фраз, набранных
 * подряд, снится «все со всеми», и без этого правила её пересказы заняли бы
 * всю квоту.
 *
 * ПОРЯДОК. Короче цепочка — раньше, при равной длине — по виду и строке
 * номеров (строкой, не числом: «8,10» идёт раньше «8,9»; для отбора это всё
 * равно, важна только неизменность).
 * Это «проще — раньше», как у плетения, и детерминированно: одна и та же
 * память даёт один и тот же отбор. Выбора «лучшего» сна здесь нет, и признака
 * для него тоже нет.
 *
 * МОЛЧАНИЕ. Сон со скрытым, отвергнутым или удалённым звеном не подаётся
 * целиком — то же правило, что у показа, см. [Dream.silences]. Такие сны
 * считаются отдельно, чтобы прибор отличал их от «сна не было».
 *
 * НИКОГО НЕ ГРЕЕТ. Записи читаются через [StickerDao.getAll], без отметки
 * обращения: запись, пришедшая в запрос через сон, не считается найденной, и
 * слой её не меняется. Иначе поданный сон грел бы свои записи, те чаще
 * попадали бы в отбор, а с ними чаще всплывали бы те же сны — самонакачка.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - только последняя ночь. Более старые сны не подаются — это пока и есть их
 *    остывание. Сплетения между ночами (реки) нет;
 *  - что модель скажет «мне снилось», а не «было», держится на метке и её
 *    грамматике (см. [ProvenanceLabels.DREAM_FOR_MODEL]); код этого не видит;
 *  - запись, пришедшая только через сон, теряет свою метку происхождения: сон
 *    цитирует её текст без «Пользователь сказал». Промах в безопасную сторону —
 *    модель скорее недоверит, чем примет сон за сказанное;
 *  - тексты записей подаются целиком, без обрезки, как и сами записи;
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

    /** Отбор к ответу, в котором стоят записи [answerIds]. Базу не меняет. */
    suspend fun offer(answerIds: Set<Long>): Offer {
        val night = dreams.lastNight() ?: return Offer(nightAt = null, silence = Silence.NO_NIGHT)
        if (answerIds.isEmpty()) return Offer(nightAt = night.nightAt, silence = Silence.NO_RECORDS)
        val byId = stickers.getAll().associateBy { it.id }
        return pick(night.nightAt, dreams.ofNight(night.nightAt), byId, answerIds, QUOTA)
    }

    /**
     * Отметить поданными. Звать только для снов, которые действительно ушли в
     * движок (см. [Dream.servedCount]).
     */
    suspend fun markServed(picked: List<Picked>, at: Long) {
        for (p in picked) served.markServed(p.dream.nightAt, p.dream.recordIds, at)
    }

    /** Сон, прошедший отбор, вместе с живыми записями его цепочки по порядку. */
    data class Picked(val dream: Dream, val records: List<Sticker>)

    /** Почему не подано ничего. */
    enum class Silence {
        /** Ночь не проходила ни разу. */
        NO_NIGHT,

        /** К ответу не нашлось записей — сну не за что зацепиться. */
        NO_RECORDS,

        /** Ночь есть, записи есть, но ни один сон не подошёл. */
        NOTHING_FITS,
    }

    /**
     * Итог отбора. [fitting] — сколько снов подошло до квоты и до правила «каждый
     * следующий приносит новое»; [silenced] — сколько касались записей ответа,
     * но молчат из-за скрытого звена.
     */
    data class Offer(
        val nightAt: Long?,
        val picked: List<Picked> = emptyList(),
        val fitting: Int = 0,
        val silenced: Int = 0,
        val silence: Silence? = null,
    )

    companion object {

        /**
         * Сколько снов подаётся к одному ответу. Стартовое число, объявленное, а
         * не подобранное; верно, пока прибор не показывает, что «подходило»
         * стабильно больше «подано». Перепроверяется строкой прибора [meter].
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
                val fresh = ids.filter { it !in answerIds && it !in brought }
                if (fresh.isEmpty()) continue
                brought += fresh
                picked += Picked(row, ids.map { byId.getValue(it) })
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
         * Строка сна для модели. Начинается с метки происхождения так же, как
         * строки записей, и в ленте лежит рядом с ними.
         */
        fun line(p: Picked): String {
            val t = p.records.map { "«${it.content}»" }
            val body = when (p.dream.kind) {
                DreamWeaver.Kind.TIME.name ->
                    "${t.joinToString(" и ")} — это было рядом по времени"
                // Мост хранится краем, мостом и краем (см. DreamWeaver.Kind.BRIDGE).
                DreamWeaver.Kind.BRIDGE.name ->
                    if (t.size == 3) "${t[0]} и ${t[2]} — связались через ${t[1]}"
                    else t.joinToString(", ")
                DreamWeaver.Kind.PLOT.name -> t.joinToString(" → ")
                // Вид, о котором подача не знает, называется перечнем, а не
                // прячется.
                else -> t.joinToString(", ")
            }
            return "${ProvenanceLabels.DREAM_FOR_MODEL}: $body."
        }

        /**
         * Строка ли это сна. Нужна показу ленты, чтобы считать сны отдельно от
         * записей: строки обоих видов лежат в ходе одним списком, а слитый
         * счёт «записей 3» мог бы значить «2 записи и сон».
         */
        fun isDreamLine(text: String): Boolean =
            text.startsWith("${ProvenanceLabels.DREAM_FOR_MODEL}:")

        /**
         * Строка прибора. Печатается при любом исходе: прибор, который
         * появляется только при удаче, неотличим от сломанного.
         *
         * [alreadyInRibbon] — сколько из отобранных уже лежит в ленте с прошлых
         * ходов и второй раз не подаётся.
         */
        fun meter(offer: Offer, alreadyInRibbon: Int): String = buildString {
            append("Снов: ")
            when (offer.silence) {
                Silence.NO_NIGHT -> {
                    append("ночей ещё не было")
                    return@buildString
                }
                Silence.NO_RECORDS -> append("к ответу нет записей, зацепиться не за что")
                Silence.NOTHING_FITS -> append("к этим записям не подошёл ни один")
                null -> {
                    append("подходило ").append(offer.fitting)
                    append(" · подано ").append(offer.picked.size - alreadyInRibbon)
                    if (alreadyInRibbon > 0) append(" · уже в ленте ").append(alreadyInRibbon)
                }
            }
            if (offer.silenced > 0) {
                append(" · молчат из-за скрытых записей: ").append(offer.silenced)
            }
            offer.nightAt?.let { append(" · ночь ").append(DreamView.moment(it)) }
        }
    }
}
