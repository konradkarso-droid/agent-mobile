package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sticker
import com.uroboros.memory.StickerDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Что приснилось прошлой ночью — раздел для экрана «Показать».
 *
 * ТОЛЬКО ЧТЕНИЕ, НИ ОДНОЙ КНОПКИ. Сон ничего не утверждает, соглашаться с ним
 * или отвергать нечего; всё, что здесь можно сделать, — посмотреть. Раздел стоит
 * отдельно от спорных пар судьи намеренно: рядом с ними сон читался бы как
 * находка судьи, то есть как заявка на правду.
 *
 * НОЧЬ ПОКАЗЫВАЕТСЯ С ЕЁ ВРЕМЕНЕМ, ВСЕГДА. Иначе позавчерашние сны на экране
 * неотличимы от сегодняшних, а отсутствие прохода — от прохода без снов.
 *
 * МОЛЧАНИЕ СЧИТАЕТСЯ. Сон, у которого хоть одно звено скрыто на проверку,
 * отвергнуто или удалено, не показывается целиком — правило одно с подачей
 * модели, см. [Dream.silences]. Но число таких снов печатается — без него
 * молчание экрана не отличить от поломки показа.
 *
 * ВАРИАНТЫ ОДНОГО СНА СХЛОПЫВАЮТСЯ. Близкие по словам записи («Меркурий светит
 * белым / красным / алым на закате») образуют семью, и сны, отличающиеся только
 * заменой записи внутри семьи, показываются одной строкой с перечнем вариантов.
 * На первой живой ночи это 40 снов против 20 различных. Схлопывание живёт
 * ТОЛЬКО здесь: в базе сны остаются как сплетены, и решение обратимо правкой
 * одного показа. Семья считается порогом правила противоречия
 * ([RiskTrigger.closeEnoughToCompare]) и только среди записей этой ночи.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - показывает только последнюю ночь; прежние ночи лежат в базе, ходить по ним
 *    отсюда нельзя;
 *  - семья считается по словам, а не по смыслу: пересказ другими словами семьёй
 *    не станет, и его варианты останутся отдельными снами. Обратная сторона —
 *    два сна, где записи похожи, но говорят разное, склеятся в один; поэтому
 *    варианты перечисляются текстом, а не прячутся;
 *  - длинные записи обрезаются до [MAX_TEXT] знаков — это показ, а не источник:
 *    полный текст записи смотрится в списке памяти ниже.
 *
 * Последней строкой раздела стоит давление сна — чем следующая ночь отличалась
 * бы от показанной (см. [SleepPressure]). Оно считается из того же чтения базы,
 * что и показ, лишних обращений не добавляет.
 */
class DreamView(
    private val dreams: DreamDao,
    private val stickers: StickerDao,
) {

    /**
     * Обычный вход с экрана. Хранилища берутся из базы процесса; отдельный
     * конструктор выше нужен затем, чтобы отбор молчащих снов проверялся
     * подделками, без Android и без устройства.
     */
    constructor(context: Context) : this(
        MemoryDatabase.getInstance(context).dreamDao(),
        MemoryDatabase.getInstance(context).stickerDao(),
    )

    suspend fun section(): String {
        // Вся память берётся одним чтением: номеров в снах сотни, и запрос на
        // каждый номер стоил бы сотни обращений к базе ради того же ответа.
        // Нужны и скрытые, и отвергнутые записи — по ним решается, молчать ли;
        // давлению сна нужны все записи по той же причине, что и самому сну.
        val all = stickers.getAll()
        val night = dreams.lastNight()
        val rows = night?.let { dreams.ofNight(it.nightAt) } ?: emptyList()
        val pressure = SleepPressure.line(SleepPressure.measure(all, night, rows))
        if (night == null) return NO_NIGHT + "\n\n" + pressure
        val byId = all.associateBy { it.id }

        val visible = mutableListOf<Pair<String, List<Sticker>>>()
        val dreamt = mutableSetOf<Long>()
        var silent = 0
        for (row in rows) {
            val ids = row.ids()
            dreamt += ids
            val records = ids.map { byId[it] }
            if (records.any { Dream.silences(it) }) {
                silent++
                continue
            }
            visible += row.kind to records.map { it!! }
        }
        return render(night, collapse(visible), silent, dreamt.size) + "\n\n" + pressure
    }

    /**
     * Один показанный сон: чем приснился, тексты звеньев в порядке цепочки и
     * тексты записей, которыми отличаются схлопнутые варианты этого же сна.
     */
    data class Shown(
        val kind: String,
        val texts: List<String>,
        val variants: List<String> = emptyList(),
    )

    /**
     * Схлопывание вариантов: сны одного вида, у которых на каждом месте цепочки
     * стоят записи из одной семьи, становятся одним показанным сном.
     *
     * Представителем группы берётся первый сон — порядок снов задан плетением
     * («проще — раньше»), и брать из группы что-то «лучшее» было бы выбором без
     * основания. Варианты перечисляются текстами тех записей, которыми они
     * отличаются от представителя, без повторов и в порядке групп.
     *
     * Семьи считаются только среди записей, попавших в сны этой ночи: по всей
     * памяти это были бы тысячи пар ради того же ответа.
     */
    private fun collapse(dreams: List<Pair<String, List<Sticker>>>): List<Shown> {
        val records = dreams.flatMap { it.second }.distinctBy { it.id }
        // Семьи — объединением: близость не транзитивна, но для показа связная
        // группа и есть то, что человек читает как «одно и то же разными
        // словами».
        val family = HashMap<Long, Long>()
        records.forEach { family[it.id] = it.id }
        fun root(id: Long): Long {
            var r = id
            while (family.getValue(r) != r) r = family.getValue(r)
            return r
        }
        for (i in records.indices) {
            for (j in i + 1 until records.size) {
                val a = records[i]
                val b = records[j]
                if (!RiskTrigger.closeEnoughToCompare(a.content, b.content)) continue
                val ra = root(a.id)
                val rb = root(b.id)
                if (ra != rb) family[ra] = rb
            }
        }

        val order = mutableListOf<List<Any>>()
        val groups = LinkedHashMap<List<Any>, MutableList<List<Sticker>>>()
        for ((kind, chain) in dreams) {
            val skeleton = listOf(kind) + chain.map { root(it.id) }
            groups.getOrPut(skeleton) { order += skeleton; mutableListOf() } += chain
        }

        return groups.entries.map { (skeleton, chains) ->
            val first = chains.first()
            val variants = LinkedHashSet<String>()
            for (chain in chains.drop(1)) {
                chain.forEachIndexed { at, record ->
                    if (record.id != first[at].id) variants += record.content
                }
            }
            Shown(skeleton.first() as String, first.map { it.content }, variants.toList())
        }
    }

    companion object {

        const val MAX_TEXT = 70

        const val NO_NIGHT = "СНЫ\n" +
            "Сна ещё не было. Ночь проходит долгим нажатием на «Разбор памяти»."

        /**
         * Слова раздела. Отдельно от чтения базы, чтобы проверяться без Android:
         * ошибка здесь — это молчание или враньё на экране, а не сбой.
         */
        fun render(
            night: DreamNight,
            shown: List<Shown>,
            silent: Int,
            dreamtRecords: Int,
        ): String = buildString {
            append("СНЫ\n")
            append("Ночь: ").append(moment(night.nightAt))
            append(" · ").append(startedBy(night.startedBy)).append("\n")
            append("Снов: ").append(night.dreams)
            // Различные — это после схлопывания вариантов. Оба числа стоят
            // рядом всегда: без второго вдвое короче ставший список выглядит
            // как пропажа снов, без первого не видно, сколько их сплелось.
            if (shown.isNotEmpty() && shown.size != night.dreams) {
                append(" · различных: ").append(shown.size)
            }
            // «Участвовало», а не «снилось»: это записи, прошедшие отбор сна, а
            // не попавшие в сны. Сколько из них не связалось ни с чем — строкой
            // ниже.
            append(" · участвовало записей: ").append(night.dreamers)
            if (night.dreamersCold > 0) append(" · холодных ").append(night.dreamersCold)
            if (night.dreamersArchive > 0) append(" · из архива ").append(night.dreamersArchive)
            append("\n")

            // Разница между «участвовал» и «приснился» показывает, широко ли сон
            // берёт: запись могла пройти отбор и всё равно ни с чем не связаться.
            val untouched = night.dreamers - dreamtRecords
            if (untouched > 0) {
                append("Участвовали, но не приснились: ").append(untouched).append("\n")
            }
            val notDreamt = buildList {
                if (night.skippedHidden > 0) add("скрытых ${night.skippedHidden}")
                if (night.skippedQuestions > 0) add("вопросов ${night.skippedQuestions}")
                // null — ночь до признака просьб: тогда они снились, и молчание
                // здесь правдивее нуля.
                night.skippedRequests?.let { if (it > 0) add("просьб $it") }
                if (night.skippedAgentReports > 0) {
                    add("отчётов агента ${night.skippedAgentReports}")
                }
            }
            if (notDreamt.isNotEmpty()) {
                append("Не снились вовсе: ").append(notDreamt.joinToString(", ")).append("\n")
            }
            // Река — всегда, когда ночь её знала: «притоков не было» и «притоки
            // были, но ни один не продлился» — разные вещи, и пустая река без
            // строки была бы неотличима от сломанной. null — ночь до реки.
            night.riverTributaries?.let { tributaries ->
                append("Река: ")
                when {
                    tributaries == 0 -> append("притоков не было — ни один сон прошлой ночи не вспомнен")
                    (night.riverDreams ?: 0) == 0 ->
                        append("притоков ").append(tributaries).append(", ни один не продлился")
                    else -> append("притоков ").append(tributaries)
                        .append(" · сплетено ").append(night.riverDreams)
                }
                append("\n")
            }
            if (night.ceilingHit) {
                append("Потолок снов сработал — до длинных сюжетов дело не дошло.\n")
            }
            if (silent > 0) {
                append("Не показано снов: ").append(silent)
                append(" — их звенья скрыты, отвергнуты или удалены.\n")
            }

            if (night.dreams == 0) {
                append("\nВ эту ночь ничего не приснилось.")
                return@buildString
            }
            if (shown.isEmpty()) {
                append("\nПоказать нечего: все сны этой ночи молчат.")
                return@buildString
            }
            for (dream in shown) {
                append("\n").append(label(dream.kind)).append(": ")
                append(dream.texts.joinToString(" → ") { short(it) })
                append("\n")
                if (dream.variants.isNotEmpty()) {
                    // Варианты названы текстами, а не числом: склейка по
                    // словам иногда ошибается, и человек должен видеть, что
                    // именно свёрнуто в эту строку.
                    append("    то же с: ")
                    append(dream.variants.joinToString(" · ") { short(it) })
                    append("\n")
                }
            }
        }.trimEnd()

        /**
         * Кто начал ночь, словами. Старая ночь без отметки так и названа: пустое
         * место на экране читалось бы как «по кнопке», а это неизвестно.
         */
        private fun startedBy(name: String?): String = when (name) {
            null -> "кто начал — не записано"
            NightStart.SELF.name -> "уснул сам"
            NightStart.BUTTON.name -> "по кнопке"
            // Имя как есть — по той же причине, что у вида сна ниже.
            else -> name
        }

        private fun label(kind: String): String = when (kind) {
            DreamWeaver.Kind.BRIDGE.name -> "мост"
            DreamWeaver.Kind.TIME.name -> "по времени"
            DreamWeaver.Kind.PLOT.name -> "сюжет"
            DreamRiver.KIND -> "река"
            // Имя как есть: вид сна, о котором этот показ не знает, лучше
            // назвать непонятно, чем спрятать.
            else -> kind
        }

        private fun short(text: String): String {
            val flat = text.replace('\n', ' ').trim()
            return if (flat.length <= MAX_TEXT) flat else flat.take(MAX_TEXT - 1).trimEnd() + "…"
        }

        /** Время ночи на экране и в приборе подачи — одним форматом. */
        internal fun moment(millis: Long): String =
            SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(millis))
    }
}
