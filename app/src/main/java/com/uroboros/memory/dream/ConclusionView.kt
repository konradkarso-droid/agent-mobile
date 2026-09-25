package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.Sticker
import com.uroboros.memory.StickerDao

/**
 * Раздел «Выводы» в «Показать» и строка прибора в «Подробно».
 *
 * ТОЛЬКО ЧТЕНИЕ, НИ ОДНОЙ КНОПКИ — как у зеркала ([MirrorView]): вывод
 * никуда не подаётся, соглашаться с ним нечем. Всё читается из базы при
 * показе, поэтому переживает перезапуск.
 *
 * Сон под выводом показывается живыми записями, по номеру и без отметки
 * обращения. Если звено сна теперь молчит ([Dream.silences]), молчит и вывод:
 * он сложен из слов этих записей и выдал бы их. Правило одно с показом снов.
 *
 * ЧЕГО НЕ УМЕЕТ: показывает только последние [NIGHTS] ночей с выводами;
 * прежние лежат в базе, и в приборе («всего сделано») они считаются.
 */
class ConclusionView(
    private val conclusions: ConclusionDao,
    private val dreams: DreamDao,
    private val stickers: StickerDao,
) {

    constructor(context: Context) : this(
        MemoryDatabase.getInstance(context).conclusionDao(),
        MemoryDatabase.getInstance(context).dreamDao(),
        MemoryDatabase.getInstance(context).stickerDao(),
    )

    suspend fun section(): String {
        val rows = conclusions.lastNights(NIGHTS)
        val nights = HashMap<Long, Map<String, Dream>>()
        val items = rows.map { row ->
            val ofNight = nights.getOrPut(row.dreamNightAt) {
                dreams.ofNight(row.dreamNightAt).associateBy { it.recordIds }
            }
            val dream = ofNight[row.dreamRecordIds]
            Item(row, dream, dream?.ids().orEmpty().map { stickers.getById(it) })
        }
        return render(conclusions.lastOutcome(), items)
    }

    suspend fun meter(): String = meter(conclusions.lastOutcome(), conclusions.countAccepted())

    /**
     * Строка вывода с тем, что нужно для показа сна.
     *
     * @property dream сон вывода; null — сна в таблице снов нет (тогда вывод
     *   молчит: проверить звенья нечем).
     * @property records записи звеньев по порядку; null — записи нет.
     */
    data class Item(val row: ConclusionRow, val dream: Dream?, val records: List<Sticker?>)

    companion object {

        /**
         * Сколько ночей показывать. Объявленное число, не подобранное: чтобы
         * раздел оставался читаемым.
         */
        const val NIGHTS = 7

        private const val NEVER =
            "ночей с выводами не было — они делаются в ночь по кнопке (долгое нажатие «Разбор памяти»)"

        const val SILENT = "вывод по сну молчит — звено на проверке или удалено"

        /**
         * Строка прибора. Итог последней ночи печатается всегда — в том числе
         * причина молчания, иначе «связывать нечего» не отличить от «сломано».
         */
        fun meter(lastOutcome: String?, accepted: Int): String =
            Conclusion.OUTCOME_HEAD + "последняя ночь — " +
                (lastOutcome?.removePrefix(Conclusion.OUTCOME_HEAD) ?: NEVER) +
                " · всего сделано $accepted"

        /** Слова раздела. [items] — от новых ночей к старым, внутри ночи по порядку. */
        fun render(lastOutcome: String?, items: List<Item>): String = buildString {
            append("ВЫВОДЫ\n")
            append("Последняя ночь: ").append(lastOutcome?.removePrefix(Conclusion.OUTCOME_HEAD) ?: NEVER)
            append("\nМысли агента о связи записей сна. Пока никуда не подаются — только здесь.")
            var night: Long? = null
            for (item in items) {
                val row = item.row
                if (row.nightAt != night) {
                    night = row.nightAt
                    append("\n\nНочь: ").append(DreamView.moment(row.nightAt))
                }
                append("\n")
                val dream = item.dream
                if (dream == null || item.records.isEmpty() || item.records.any { Dream.silences(it) }) {
                    append(SILENT)
                    continue
                }
                if (row.accepted) {
                    append(Conclusion.shown(row.text))
                } else {
                    append("отброшено: «").append(row.text).append("» — ").append(row.reason.orEmpty())
                }
                append("\n    ")
                append(DreamView.brief(dream.kind, item.records.map { it!!.content }))
            }
        }.trimEnd()
    }
}
