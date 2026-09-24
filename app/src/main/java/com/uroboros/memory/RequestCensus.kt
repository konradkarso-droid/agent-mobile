package com.uroboros.memory

import android.content.Context

/**
 * Перепись просьб: сколько записей памяти признак [RiskTrigger.isOnlyRequests]
 * узнаёт как просьбы — и какие именно, текстами.
 *
 * ЗАЧЕМ ТЕКСТЫ, А НЕ ЧИСЛО. Признак пока ничего не снимает, только считает (см.
 * [RiskTrigger.isOnlyRequests]). Прежде чем он начнёт прятать записи от снов,
 * судьи и ответов, человек должен глазами увидеть, что именно он узнал: одно
 * утверждение, принятое за просьбу, по числу не видно.
 *
 * ВОПРОСЫ СЧИТАЮТСЯ РЯДОМ, НО ОТДЕЛЬНО. Слитый счёт спрятал бы всплеск ложных
 * срабатываний нового признака под видом «вопросов стало больше».
 *
 * СЧИТАЮТСЯ ЖИВЫЕ ЗАПИСИ. Скрытые на проверку и отвергнутые не входят: их и так
 * нет ни в снах, ни в ответах, и снимать их нечего.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - пропущенную просьбу не покажет: запись, которую признак не узнал, стоит
 *    среди утверждений, и отсюда её не видно. Такие видны в снах, где просьбы
 *    всё ещё снятся;
 *  - перечень обрезан до [MAX_LISTED] самых свежих и до [MAX_TEXT] знаков на
 *    запись; полный текст — в списке памяти.
 */
object RequestCensus {

    const val MAX_LISTED = 20
    const val MAX_TEXT = 70

    /** Вход с экрана: вся память одним чтением. */
    suspend fun section(context: Context): String =
        render(MemoryDatabase.getInstance(context).stickerDao().getAll())

    /** Слова раздела. Отдельно от чтения базы, чтобы проверяться без Android. */
    fun render(records: List<Sticker>): String = buildString {
        val live = records.filter { !it.reviewPending && it.rejectedAt == null }
        val requests = live.filter { RiskTrigger.isOnlyRequests(it.content) }
            .sortedByDescending { it.createdAt }
        val questions = live.count { RiskTrigger.isOnlyQuestions(it.content) }

        append("ПРОСЬБЫ — пока только счёт, ничего не снимается\n")
        append("Живых записей: ").append(live.size)
        append(" · одни просьбы: ").append(requests.size)
        append(" · одни вопросы: ").append(questions)
        if (requests.isEmpty()) {
            append("\nПросьб не узнано.")
            return@buildString
        }
        for (record in requests.take(MAX_LISTED)) {
            append("\n№").append(record.id).append(" ").append(short(record.content))
        }
        if (requests.size > MAX_LISTED) {
            append("\n… и ещё ").append(requests.size - MAX_LISTED)
        }
    }

    private fun short(text: String): String {
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length <= MAX_TEXT) flat else flat.take(MAX_TEXT - 1).trimEnd() + "…"
    }
}
