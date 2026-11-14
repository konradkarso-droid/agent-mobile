package com.uroboros.llm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись хода по занятому номеру — когда она значит «на диске чужой
 * разговор» и что об этом сказать на экране. Чистая логика для
 * [JournalStore.append]; почему занятый номер значит чужой разговор, сказано
 * там.
 */
object JournalOverwrite {

    /** Занят ли номер [index] среди сохранённых [indices]. */
    fun occupied(indices: Collection<Int>, index: Int): Boolean = index in indices

    /** Строка экрана: сколько ходов ушло в архив, каким номером и когда. */
    fun line(count: Int, archiveIndex: Int, atMs: Long): String =
        "Лента: прежний разговор ($count ходов) не поднялся и ушёл в архив №$archiveIndex " +
            "перед новым ходом (${SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(atMs))})"
}
