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
 *  - ссылка на найденную запись — её первые слова (см. [ANCHOR_WORDS]); что
 *    малая модель свяжет ссылку с записью строкой выше, не проверено ничем,
 *    кроме чтения ответов;
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

    /** Строка сна для модели и сны, которые в неё легли (см. [lines]). */
    data class Line(val text: String, val dreams: List<Picked>)

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
         * Сколько первых слов записи ответа называется в строке сна. Запись
         * целиком уже стоит строкой выше; здесь нужна только ссылка на неё.
         * Верно, пока записи короткие фразы; для длинной записи четыре слова
         * могут не отличить её от соседки с тем же началом.
         */
        const val ANCHOR_WORDS = 4

        /**
         * Строки снов для модели.
         *
         * СОН НЕ ПОВТОРЯЕТ НАЙДЕННОЕ. Запись ответа уже стоит строкой выше с
         * меткой «Пользователь сказал»; сон называет её коротко (см.
         * [ANCHOR_WORDS]) и целиком приносит только то, что добавил. Иначе
         * малая модель видит найденную фразу по три раза и принимает повтор за
         * главное, не замечая принесённого.
         *
         * Сны «рядом по времени» с одной и той же записью ответа ложатся одной
         * строкой: «рядом с «…» было: «…», «…»». Мосты и сюжеты — по строке на
         * сон, у них своя форма.
         *
         * Каждая строка начинается с метки происхождения сна
         * ([ProvenanceLabels.DREAM_FOR_MODEL]), как строки записей — со своей.
         */
        fun lines(picked: List<Picked>, answerIds: Set<Long>): List<Line> {
            fun ref(r: Sticker): String =
                if (r.id in answerIds) "«${anchor(r.content)}»" else "«${r.content}»"

            val out = mutableListOf<Line>()
            val byAnchor = LinkedHashMap<List<Long>, MutableList<Picked>>()
            for (p in picked) {
                when (p.dream.kind) {
                    DreamWeaver.Kind.TIME.name -> {
                        val anchors = p.records.filter { it.id in answerIds }.map { it.id }
                        byAnchor.getOrPut(anchors) { mutableListOf() } += p
                    }
                    // Мост хранится краем, мостом и краем (см. DreamWeaver.Kind.BRIDGE).
                    DreamWeaver.Kind.BRIDGE.name -> {
                        val r = p.records
                        val body = if (r.size == 3) {
                            "${ref(r[0])} и ${ref(r[2])} связались через ${ref(r[1])}"
                        } else {
                            r.joinToString(", ") { ref(it) }
                        }
                        out += Line("${ProvenanceLabels.DREAM_FOR_MODEL}, что $body.", listOf(p))
                    }
                    DreamWeaver.Kind.PLOT.name -> out += Line(
                        "${ProvenanceLabels.DREAM_FOR_MODEL}: ${p.records.joinToString(" → ") { ref(it) }}.",
                        listOf(p),
                    )
                    // Сон реки — продолжение вспомненного сна (см. DreamRiver).
                    // Для модели он такая же цепочка, как сюжет; пометка
                    // говорит, что цепочка тянется из прошлой ночи.
                    DreamRiver.KIND -> out += Line(
                        "${ProvenanceLabels.DREAM_FOR_MODEL}: снова и дальше — " +
                            "${p.records.joinToString(" → ") { ref(it) }}.",
                        listOf(p),
                    )
                    // Вид, о котором подача не знает, называется перечнем, а не
                    // прячется.
                    else -> out += Line(
                        "${ProvenanceLabels.DREAM_FOR_MODEL}: ${p.records.joinToString(", ") { ref(it) }}.",
                        listOf(p),
                    )
                }
            }
            // Строки «по времени» идут первыми: это самые простые сны, в том же
            // порядке «проще — раньше», что и отбор.
            val time = byAnchor.values.map { group ->
                val anchors = group.first().records.filter { it.id in answerIds }
                val added = group.flatMap { p -> p.records.filter { it.id !in answerIds } }
                    .distinctBy { it.id }
                val near = anchors.joinToString(" и ") { "«${anchor(it.content)}»" }
                Line(
                    "${ProvenanceLabels.DREAM_FOR_MODEL}, что рядом с $near было: " +
                        added.joinToString(", ") { "«${it.content}»" } + ".",
                    group,
                )
            }
            return time + out
        }

        /** Первые [ANCHOR_WORDS] слов, с многоточием, если дальше есть ещё. */
        private fun anchor(text: String): String {
            val words = text.trim().split(Regex("\\s+"))
            if (words.size <= ANCHOR_WORDS) return text.trim().trimEnd('.', '!', '?')
            return words.take(ANCHOR_WORDS).joinToString(" ").trimEnd(',', '.', ';', ':') + "…"
        }

        /**
         * Строка ли это сна. Нужна показу ленты, чтобы считать сны отдельно от
         * записей: строки обоих видов лежат в ходе одним списком, а слитый
         * счёт «записей 3» мог бы значить «2 записи и сон».
         */
        fun isDreamLine(text: String): Boolean =
            text.startsWith("${ProvenanceLabels.DREAM_FOR_MODEL}:") ||
                text.startsWith("${ProvenanceLabels.DREAM_FOR_MODEL},")

        /**
         * Строка прибора. Печатается при любом исходе: прибор, который
         * появляется только при удаче, неотличим от сломанного.
         *
         * [alreadyInRibbon] — сколько из отобранных снов уже лежит в ленте с
         * прошлых ходов и второй раз не подаётся; [lineCount] — сколькими
         * строками легли все отобранные.
         */
        fun meter(offer: Offer, alreadyInRibbon: Int, lineCount: Int = offer.picked.size): String = buildString {
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
                    // Сны с одной записью ответа ложатся одной строкой (см.
                    // [lines]), и под вопросом считаются строки, а не сны.
                    if (lineCount in 1 until offer.picked.size) {
                        append(" · строк ").append(lineCount)
                    }
                }
            }
            if (offer.silenced > 0) {
                append(" · молчат из-за скрытых записей: ").append(offer.silenced)
            }
            offer.nightAt?.let { append(" · ночь ").append(DreamView.moment(it)) }
        }
    }
}
