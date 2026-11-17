package com.uroboros.safety

import java.util.Locale

/**
 * Простои процесса агента: отрезки, когда он не шёл, хотя должен был, — и
 * почему, насколько это видно изнутри. Исполнитель — [StallWatch], здесь только
 * счёт и слова, чтобы проверяться без Android.
 *
 * ЗАЧЕМ. Прогон суда при погасшем экране стоит на одной паре десятки минут и
 * часы, а в руках у человека та же пара считается за секунды. Изнутри прогона
 * не отличить три разных случая, а лечатся они по-разному:
 *  - телефон уснул целиком (блокировка сна не удержала его);
 *  - телефон не спал, а процессу агента не давали процессора — заморозила
 *    прошивка или система;
 *  - процесс шёл, но медленно.
 *
 * КАК. Свой поток отмечается каждые [BEAT_MS] и при каждой отметке берёт три
 * часа: время от загрузки телефона, которое идёт и во сне (realtime); время
 * бодрствования, которое во сне стоит (uptime); время процессора, которое
 * получил весь процесс (cpu). Отметка, опоздавшая больше чем на [STALL_MS], —
 * простой. Разность первых двух часов за простой — сколько спал телефон;
 * третьи часы — сколько процессор за это время работал на агента.
 *
 * ГЛАВНОЕ СВОЙСТВО. Поток отметок ничего не ждёт, кроме своего сна, — ни
 * замков, ни движка, ни сети. Поэтому прогон, вставший на НАШЕМ ожидании
 * (замок, движок занят), простоя здесь не даёт: отметки идут вовремя. Простой
 * появляется, только когда не шёл сам процесс. Отсюда и чтение: прогон стоял, а
 * простоя нет — искать в нашем коде.
 *
 * КАКИЕ ПРОСТОИ ПИШУТСЯ. Без процессора и медленные — всегда: процесс без дела
 * при неспящем телефоне всё равно должен слышать телеграм. Сон телефона — только
 * если простой начался за работой (прогон, ход, занятый движок): без работы
 * агент блокировку сна не держит, и сон телефона тогда — норма, а не простой.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - не отличает заморозку прошивкой от нулевой доли процессора — оба случая
 *    «процессора не давали», и слово одно;
 *  - простой короче [STALL_MS] сверх отметки не пишется;
 *  - смерть процесса — не простой: новый процесс начинает отметки заново, а
 *    кому пишет AgentLife;
 *  - группа процесса берётся в начале и в конце простоя; что было посередине,
 *    не видно.
 *
 * Чистый объект.
 */
object StallMeter {

    /** Как часто отмечается поток. Объявленное число, не подобранное. */
    const val BEAT_MS = 10_000L

    /** На сколько отметка должна опоздать, чтобы считаться простоем. Объявленное число, не подобранное. */
    const val STALL_MS = 60_000L

    /** Сколько последних простоев хранить. Объявленное число. */
    const val KEPT = 5

    /**
     * Доля бодрствования, меньше которой процессор, полученный процессом,
     * значит «процессора не давали». Объявленное число, не подобранное:
     * работающий прогон занимает процессор целиком, стоящий — почти нисколько.
     */
    const val NO_CPU_SHARE = 0.01

    /**
     * Одна отметка: [wallMs] — часы телефона (для строки); [realtimeMs] — от
     * загрузки, идут и во сне; [uptimeMs] — от загрузки без сна; [cpuMs] —
     * процессор процесса; [group] — группа процесса словом системы;
     * [working] — шла ли работа (прогон, ход, занятый движок).
     */
    data class Beat(
        val wallMs: Long,
        val realtimeMs: Long,
        val uptimeMs: Long,
        val cpuMs: Long,
        val group: String,
        val working: Boolean,
    )

    /** Простой: с [fromMs] по часам телефона, [gapMs] всего, из них телефон спал [sleptMs], процессора [cpuMs]. */
    data class Episode(
        val fromMs: Long,
        val gapMs: Long,
        val sleptMs: Long,
        val cpuMs: Long,
        val working: Boolean,
        val groupBefore: String,
        val groupAfter: String,
    )

    enum class Kind { PHONE_SLEPT, NO_CPU, SLOW }

    /** Простой между двумя отметками; null — его нет или он не пишется (см. шапку). */
    fun episode(prev: Beat, now: Beat): Episode? {
        val gap = now.realtimeMs - prev.realtimeMs
        if (gap < BEAT_MS + STALL_MS) return null
        val slept = (gap - (now.uptimeMs - prev.uptimeMs)).coerceIn(0, gap)
        val e = Episode(
            fromMs = prev.wallMs,
            gapMs = gap,
            sleptMs = slept,
            cpuMs = (now.cpuMs - prev.cpuMs).coerceAtLeast(0),
            working = prev.working,
            groupBefore = prev.group,
            groupAfter = now.group,
        )
        return if (kind(e) == Kind.PHONE_SLEPT && !e.working) null else e
    }

    fun kind(e: Episode): Kind {
        val awake = e.gapMs - e.sleptMs
        return when {
            e.sleptMs * 2 >= e.gapMs -> Kind.PHONE_SLEPT
            e.cpuMs < awake * NO_CPU_SHARE -> Kind.NO_CPU
            else -> Kind.SLOW
        }
    }

    /**
     * Строки для экрана: последний простой подробно, прежние — коротко.
     * [watchingSince] — с какого момента идут отметки (null — ещё не шли);
     * [clock] — время словами; [where] — группа словами.
     */
    fun meter(
        episodes: List<Episode>,
        watchingSince: Long?,
        clock: (Long) -> String,
        where: (String) -> String,
    ): String {
        if (watchingSince == null) return "Простои: отметки ещё не шли"
        val newest = episodes.sortedByDescending { it.fromMs }
        val last = newest.firstOrNull()
            ?: return "Простои: не было с ${clock(watchingSince)} (отметка раз в ${BEAT_MS / 1000} с)"
        val head = "Простои: последний ${clock(last.fromMs)} — ${duration(last.gapMs)}" +
            (if (last.working) ", за работой" else ", без работы") + ": " + verdict(last) +
            " · группа ${where(last.groupBefore)}" +
            (if (last.groupAfter != last.groupBefore) " → ${where(last.groupAfter)}" else "")
        val earlier = newest.drop(1).joinToString("") { e ->
            "\n· ${clock(e.fromMs)} ${duration(e.gapMs)}, ${short(e)}" + (if (e.working) ", за работой" else "")
        }
        return head + earlier
    }

    private fun verdict(e: Episode): String = when (kind(e)) {
        Kind.PHONE_SLEPT ->
            "телефон спал ${duration(e.sleptMs)} — процесс стоял вместе с ним"
        Kind.NO_CPU ->
            "телефон не спал, процессора агент получил ${seconds(e.cpuMs)} — процессора ему не давали"
        Kind.SLOW ->
            "телефон не спал, процессора агент получил ${seconds(e.cpuMs)} — шёл, но отметка опаздывала"
    }

    private fun short(e: Episode): String = when (kind(e)) {
        Kind.PHONE_SLEPT -> "телефон спал"
        Kind.NO_CPU -> "процессора не давали"
        Kind.SLOW -> "шёл медленно"
    }

    /** Длительность словами: «19 мин 40 с», «3 ч 28 мин», «45 с». */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600} ч ${(s % 3600) / 60} мин"
            s >= 60 -> "${s / 60} мин ${s % 60} с"
            else -> "$s с"
        }
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.1f с", ms / 1000.0)

    /** Простой одной строкой для хранения; [decode] читает обратно. */
    fun encode(e: Episode): String = listOf(
        e.fromMs, e.gapMs, e.sleptMs, e.cpuMs, if (e.working) 1 else 0,
        clean(e.groupBefore), clean(e.groupAfter),
    ).joinToString(",")

    fun decode(s: String): Episode? {
        val p = s.split(',')
        if (p.size != 7) return null
        return runCatching {
            Episode(p[0].toLong(), p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4] == "1", p[5], p[6])
        }.getOrNull()
    }

    /** Разделители хранения из слова системы убираются: группа — слово, не текст. */
    private fun clean(group: String) = group.replace(',', ' ').replace(';', ' ')
}
