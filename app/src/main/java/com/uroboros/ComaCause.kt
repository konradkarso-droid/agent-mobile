package com.uroboros

import java.util.Locale

/**
 * Отчего агент впадал в кому — словами для строки жизни ([AgentLife]).
 *
 * ОТКУДА. Упавший процесс записать свою смерть не успевает, но её записывает
 * сам Android: на Android 11 и новее он хранит последние концы процесса
 * приложения с причиной, временем и памятью, что процесс занимал
 * (ActivityManager.getHistoricalProcessExitReasons). Читает их [AgentLife],
 * здесь — только слова и счёт, чтобы проверяться без Android.
 *
 * ЗАЧЕМ. Кома обрывает всё, что жило в памяти процесса, — например, очередь
 * ответов в телеграме (gate.link.ChatLink). Счёт подъёмов говорит, что жизнь
 * прерывалась, но не почему; а чинить надо по-разному: смахивание — привычка
 * владельца, нехватка памяти — размер модели и снимков, сбой — код.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - сколько концов хранит Android и как долго, решает он сам; счёт идёт только
 *    по тому, что он отдал, и это не счёт всех ком с установки;
 *  - прошивка (MIUI) убивает процесс по-своему, и Android записывает это как
 *    сумеет: чаще всего сигналом 9 или «остановлен вручную», с пояснением
 *    прошивки или без; различить «смахнул владелец» и «убила прошивка» по
 *    такой записи можно не всегда, поэтому пояснение печатается как есть;
 *  - на Android 10 записей нет вовсе, и строка так и говорит.
 *
 * Чистый объект.
 */
object ComaCause {

    /** Причина конца процесса — по ApplicationExitInfo.REASON_*; соответствие — в [AgentLife]. */
    enum class Kind {
        EXIT_SELF,
        SIGNALED,
        LOW_MEMORY,
        CRASH,
        CRASH_NATIVE,
        ANR,
        INITIALIZATION_FAILURE,
        PERMISSION_CHANGE,
        EXCESSIVE_RESOURCE_USAGE,
        USER_REQUESTED,
        USER_STOPPED,
        DEPENDENCY_DIED,
        FREEZER,
        PACKAGE_STATE_CHANGE,
        PACKAGE_UPDATED,
        OTHER,
        UNKNOWN,
    }

    /**
     * Один конец процесса: [atMs] — когда; [status] — код выхода или номер
     * сигнала (смысл зависит от [kind]); [description] — пояснение системы или
     * прошивки, может не быть; [rssKb] — сколько памяти процесс занимал, КБ,
     * 0 — неизвестно.
     */
    data class Exit(val atMs: Long, val kind: Kind, val status: Int, val description: String?, val rssKb: Long)

    /** Что удалось узнать о прошлых комах. */
    sealed class Reading {
        /** Android до 11: концов процесса он не хранит. */
        object TooOld : Reading()

        /** Спросить не удалось; [why] — словами. */
        data class Failed(val why: String) : Reading()

        /** Концы процесса, новые первыми; пусто — Android ничего не отдал. */
        data class Exits(val list: List<Exit>) : Reading()
    }

    /** Сколько знаков пояснения печатать. Объявленное число, не подобранное. */
    const val DESCRIPTION_CHARS = 60

    /** Причина словами — для строки прошлой комы. */
    fun word(kind: Kind, status: Int): String = when (kind) {
        Kind.EXIT_SELF -> "вышел сам (код $status)"
        Kind.SIGNALED -> "убит сигналом $status"
        Kind.LOW_MEMORY -> "нехватка памяти"
        Kind.CRASH -> "сбой (исключение в коде)"
        Kind.CRASH_NATIVE -> "сбой в родном коде (движок модели)"
        Kind.ANR -> "завис, не отвечал системе"
        Kind.INITIALIZATION_FAILURE -> "не смог запуститься"
        Kind.PERMISSION_CHANGE -> "сменилось разрешение"
        Kind.EXCESSIVE_RESOURCE_USAGE -> "система сочла, что ест слишком много"
        Kind.USER_REQUESTED -> "остановлен вручную (смахивание или «Остановить»)"
        Kind.USER_STOPPED -> "остановлен профиль пользователя телефона"
        Kind.DEPENDENCY_DIED -> "умер процесс, от которого зависел"
        Kind.FREEZER -> "заморожен системой"
        Kind.PACKAGE_STATE_CHANGE -> "приложение выключено или изменено"
        Kind.PACKAGE_UPDATED -> "поставилась новая сборка"
        Kind.OTHER -> "иная причина"
        Kind.UNKNOWN -> "причина неизвестна"
    }

    /** Причина коротко — для счёта по последним комам. */
    fun short(kind: Kind, status: Int): String = when (kind) {
        Kind.EXIT_SELF -> "вышел сам"
        Kind.SIGNALED -> "сигнал $status"
        Kind.LOW_MEMORY -> "память"
        Kind.CRASH -> "сбой"
        Kind.CRASH_NATIVE -> "сбой движка"
        Kind.ANR -> "завис"
        Kind.USER_REQUESTED -> "вручную"
        Kind.PACKAGE_UPDATED -> "новая сборка"
        Kind.UNKNOWN -> "неизвестно"
        else -> word(kind, status)
    }

    /**
     * Строки для экрана: прошлая кома и счёт причин по всем концам, что отдал
     * Android. [moment] — время словами («04.11 11:16»).
     */
    fun lines(reading: Reading, moment: (Long) -> String): String = when (reading) {
        Reading.TooOld -> "Причина комы: Android до 11 её не хранит"
        is Reading.Failed -> "Причина комы: не узнал — ${reading.why}"
        is Reading.Exits -> {
            val list = reading.list
            if (list.isEmpty()) "Причина комы: Android записей не отдал"
            else {
                val last = list.first()
                val head = buildString {
                    append("Прошлая кома ").append(moment(last.atMs)).append(" — ").append(word(last.kind, last.status))
                    if (last.rssKb > 0) append(", занимал ").append(size(last.rssKb))
                    last.description?.trim()?.takeIf { it.isNotEmpty() }?.let { d ->
                        val shown = if (d.length <= DESCRIPTION_CHARS) d else d.take(DESCRIPTION_CHARS).trimEnd() + "…"
                        append(" («").append(shown).append("»)")
                    }
                }
                // При равном счёте — что случилось позже: порядок вставки (новые первыми) сохраняется сортировкой.
                val counts = LinkedHashMap<String, Int>()
                for (e in list) short(e.kind, e.status).let { counts[it] = (counts[it] ?: 0) + 1 }
                val tally = counts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }
                "$head\nИз последних ${list.size}: $tally"
            }
        }
    }

    /** Память словами: от гигабайта — «4.1 ГБ», меньше — «850 МБ». */
    fun size(kb: Long): String =
        if (kb >= 1024L * 1024) String.format(Locale.US, "%.1f ГБ", kb / (1024.0 * 1024)) else "${kb / 1024} МБ"
}
