package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.MemoryDatabase
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
 * отвергнуто или удалено, не показывается целиком: половина цепочки бессмысленна,
 * а мост через скрытую запись выдавал бы её содержание соседями. Но число таких
 * снов печатается — без него молчание экрана не отличить от поломки показа.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - показывает только последнюю ночь; прежние ночи лежат в базе, ходить по ним
 *    отсюда нельзя;
 *  - похожие сны не схлопываются (см. [DreamWeaver]): у семейства близких
 *    записей на экране будет несколько почти одинаковых строк;
 *  - длинные записи обрезаются до [MAX_TEXT] знаков — это показ, а не источник:
 *    полный текст записи смотрится в списке памяти ниже.
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
        val night = dreams.lastNight() ?: return NO_NIGHT
        val rows = dreams.ofNight(night.nightAt)
        // Вся память берётся одним чтением: номеров в снах сотни, и запрос на
        // каждый номер стоил бы сотни обращений к базе ради того же ответа.
        // Нужны и скрытые, и отвергнутые записи — по ним решается, молчать ли.
        val byId = stickers.getAll().associateBy { it.id }

        val shown = mutableListOf<Shown>()
        val dreamt = mutableSetOf<Long>()
        var silent = 0
        for (row in rows) {
            val ids = row.recordIds.split(",").mapNotNull { it.trim().toLongOrNull() }
            dreamt += ids
            val records = ids.map { byId[it] }
            val silenced = records.any {
                it == null || it.reviewPending || it.rejectedAt != null
            }
            if (silenced) {
                silent++
                continue
            }
            shown += Shown(row.kind, records.map { it!!.content })
        }
        return render(night, shown, silent, dreamt.size)
    }

    /** Один показанный сон: чем приснился и тексты звеньев в порядке цепочки. */
    data class Shown(val kind: String, val texts: List<String>)

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
            append("Ночь: ").append(moment(night.nightAt)).append("\n")
            append("Снов: ").append(night.dreams)
            append(" · снилось записей: ").append(night.dreamers)
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
                if (night.skippedAgentReports > 0) {
                    add("отчётов агента ${night.skippedAgentReports}")
                }
            }
            if (notDreamt.isNotEmpty()) {
                append("Не снились вовсе: ").append(notDreamt.joinToString(", ")).append("\n")
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
            }
        }.trimEnd()

        private fun label(kind: String): String = when (kind) {
            DreamWeaver.Kind.BRIDGE.name -> "мост"
            DreamWeaver.Kind.TIME.name -> "по времени"
            DreamWeaver.Kind.PLOT.name -> "сюжет"
            // Имя как есть: вид сна, о котором этот показ не знает, лучше
            // назвать непонятно, чем спрятать.
            else -> kind
        }

        private fun short(text: String): String {
            val flat = text.replace('\n', ' ').trim()
            return if (flat.length <= MAX_TEXT) flat else flat.take(MAX_TEXT - 1).trimEnd() + "…"
        }

        private fun moment(millis: Long): String =
            SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(millis))
    }
}
