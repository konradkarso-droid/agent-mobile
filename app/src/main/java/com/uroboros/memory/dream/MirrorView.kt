package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.MemoryDatabase

/**
 * Раздел «Зеркало» в «Показать» и строка прибора в «Подробно».
 *
 * ТОЛЬКО ЧТЕНИЕ, НИ ОДНОЙ КНОПКИ — как у снов ([DreamView]): вариант зеркала
 * ничего не утверждает, соглашаться с ним нечем. Всё читается из базы при
 * показе, поэтому переживает перезапуск.
 *
 * ЧЕГО НЕ УМЕЕТ: показывает только последние [NIGHTS] ночей с вариантами;
 * прежние лежат в базе, и в приборе («всего сверено», «сбылось») они
 * считаются.
 */
class MirrorView(
    private val mirror: MirrorDao,
    private val dreams: DreamDao,
) {

    constructor(context: Context) : this(
        MemoryDatabase.getInstance(context).mirrorDao(),
        MemoryDatabase.getInstance(context).dreamDao(),
    )

    suspend fun section(): String {
        val nights = mirror.lastNights(NIGHTS).map { it to mirror.ofNight(it) }
        return render(dreams.lastMirrorOutcome(), nights)
    }

    suspend fun meter(): String =
        meter(dreams.lastMirrorOutcome(), mirror.countChecked(), mirror.countFulfilled())

    companion object {

        /**
         * Сколько ночей показывать. Объявленное число, не подобранное: чтобы
         * раздел оставался читаемым, а не рос по три строки за ночь.
         */
        const val NIGHTS = 7

        private const val NEVER =
            "ночей с зеркалом не было — оно смотрит ночью, когда агент уснул сам или по долгому нажатию «Разбор памяти»"

        /**
         * Строка прибора. Итог последней ночи печатается всегда — в том числе
         * причина молчания, иначе «нечего было сочинять» не отличить от
         * «сломано».
         */
        fun meter(lastOutcome: String?, checked: Int, fulfilled: Int): String =
            Mirror.OUTCOME_HEAD + "последняя ночь — " +
                (lastOutcome?.removePrefix(Mirror.OUTCOME_HEAD) ?: NEVER) +
                " · всего сверено $checked · сбылось $fulfilled"

        /** Слова раздела. [nights] — от новых к старым, варианты ночи по порядку. */
        fun render(lastOutcome: String?, nights: List<Pair<Long, List<MirrorVariant>>>): String = buildString {
            append("ЗЕРКАЛО\n")
            append("Последняя ночь: ").append(lastOutcome?.removePrefix(Mirror.OUTCOME_HEAD) ?: NEVER)
            if (nights.isEmpty()) return@buildString
            append("\nЧто собеседник может написать дальше. Это выдумка модели, ")
            append("она никуда не подаётся — только сверяется с вашими репликами.\n")
            for ((nightAt, variants) in nights) {
                append("\nНочь: ").append(DreamView.moment(nightAt)).append("\n")
                for (variant in variants) {
                    append("«").append(variant.text).append("»\n")
                    append("    смотрело на ходы ").append(variant.fromTurn).append("–").append(variant.toTurn)
                    append(" · ").append(status(variant)).append("\n")
                }
            }
        }.trimEnd()

        private fun status(variant: MirrorVariant): String = when {
            variant.fulfilledAt != null ->
                "сбылось в реплике «" + short(variant.fulfilledReply.orEmpty()) + "» (основы: " +
                    Mirror.splitStems(variant.fulfilledWords).sorted().joinToString(", ") + ")"
            variant.repliesSeen == 0 -> "ждёт реплик"
            else -> "не сбылось, сверено с ${variant.repliesSeen} из ${Mirror.CHECK_WINDOW}"
        }

        private fun short(text: String): String {
            val flat = text.replace('\n', ' ').trim()
            return if (flat.length <= DreamView.MAX_TEXT) flat else flat.take(DreamView.MAX_TEXT - 1).trimEnd() + "…"
        }
    }
}
