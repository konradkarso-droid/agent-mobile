package com.uroboros.initiative

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Счёт проверок «пора ли написать первым» за сутки. Чистая логика: ни базы, ни
 * Android — хранение строкой у InitiativeTallyStore.
 *
 * ЗАЧЕМ. Строка «Первым:» говорит, почему агент не пишет СЕЙЧАС. Почему он не
 * писал ВООБЩЕ, из неё не видно: два разных случая — «проверял, но нечего
 * сказать» и «не проверял, потому что телефон спал» — на ней выглядят одинаково
 * (последней причиной). Здесь каждая проверка оставляет один итог, и за сутки
 * видно, что держит: молчание владельца, отсутствие повода, зона — или пауза.
 *
 * ПАУЗА — ВРЕМЯ МЕЖДУ ПРОВЕРКАМИ ПО ЧАСАМ. Проверка задумана раз в минуту, но
 * минута отсчитывается временем, которое в глубоком сне телефона не идёт (так
 * устроен отсчёт `delay`). Поэтому счёт проверок — это счёт минут бодрствования,
 * а не минут суток, и «самая долгая пауза» показывает, сколько агент не мог
 * написать, даже если бы было что. Пауза относится к суткам проверки, которая
 * её закрыла.
 *
 * ОДИН ИТОГ НА ПРОВЕРКУ. Проверка, прошедшая общие условия, идёт дальше
 * (загрузка модели, лента, ход) — у неё свой итог ([Outcome.LOAD_FAILED],
 * [Outcome.TURN_FAILED], [Outcome.WROTE]), а не два.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - не отличает глубокий сон телефона от смерти процесса: и то и другое —
 *    пауза. Смерть видна по счёту комы (AgentLife), здесь не повторяется;
 *  - сутки — по часам телефона, в полночь счёт начинается заново; хранятся
 *    только сегодняшние и одни прошлые сутки с проверками;
 *  - причина «нечего сказать» хранится последняя, а не все за сутки.
 */
object InitiativeTally {

    /** Итог одной проверки. [label] — словами для прибора. */
    enum class Outcome(val label: String) {
        WROTE("написал"),
        SILENCE_SHORT("владелец молчал меньше часа"),
        NOTHING_TO_SAY("нечего сказать"),
        AWAITING("ждал ответа"),
        OWNER_NEVER("владелец ещё не писал"),
        RUNNING("шёл разбор или сон"),
        ENGINE_BUSY("модель занята"),
        ZONE("зона не «норма»"),
        WATCHDOG("сторож против"),
        POWER_UNKNOWN("нет показаний батареи"),
        EMERGENCY_STOP("аварийный стоп"),
        TIMES_UNREADABLE("не прочиталось время реплик"),
        NO_MODEL("модель не выбиралась"),
        LOAD_FAILED("модель не загрузилась"),
        TURN_FAILED("ход не вышел"),
        CHECK_FAILED("проверка сорвалась");

        companion object {
            fun of(kind: InitiativeDecision.Kind): Outcome = when (kind) {
                InitiativeDecision.Kind.RUNNING -> RUNNING
                InitiativeDecision.Kind.EMERGENCY_STOP -> EMERGENCY_STOP
                InitiativeDecision.Kind.POWER_UNKNOWN -> POWER_UNKNOWN
                InitiativeDecision.Kind.ZONE -> ZONE
                InitiativeDecision.Kind.WATCHDOG -> WATCHDOG
                InitiativeDecision.Kind.ENGINE_BUSY -> ENGINE_BUSY
                InitiativeDecision.Kind.TIMES_UNREADABLE -> TIMES_UNREADABLE
                InitiativeDecision.Kind.OWNER_NEVER -> OWNER_NEVER
                InitiativeDecision.Kind.SILENCE_SHORT -> SILENCE_SHORT
                InitiativeDecision.Kind.AWAITING -> AWAITING
                InitiativeDecision.Kind.NOTHING_TO_SAY -> NOTHING_TO_SAY
                InitiativeDecision.Kind.NO_MODEL -> NO_MODEL
            }
        }
    }

    /**
     * Счёт одних суток.
     *
     * @param day сутки — «гггг-мм-дд» по часам телефона ([dayKey]).
     * @param longestGapMs самая долгая пауза между проверками, закрытая в эти сутки.
     * @param lastAt время последней проверки.
     * @param nothingWhy последняя причина «нечего сказать» словами источника.
     */
    data class Day(
        val day: String,
        val checks: Int,
        val counts: Map<Outcome, Int>,
        val longestGapMs: Long,
        val lastAt: Long,
        val nothingWhy: String?,
    )

    /** Сегодняшние и прошлые сутки с проверками; null — проверок не было. */
    data class State(val today: Day?, val previous: Day?)

    val EMPTY = State(null, null)

    /** Сутки по часам телефона. */
    fun dayKey(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    /**
     * Добавить итог проверки в момент [at]. [why] — причина «нечего сказать»
     * словами источника, берётся только с [Outcome.NOTHING_TO_SAY].
     */
    fun add(state: State, at: Long, outcome: Outcome, why: String? = null, day: String = dayKey(at)): State {
        val lastAt = (state.today ?: state.previous)?.lastAt
        val gap = lastAt?.let { (at - it).coerceAtLeast(0) } ?: 0L
        val (current, previous) = when {
            state.today == null -> Day(day, 0, emptyMap(), 0, at, null) to state.previous
            state.today.day != day -> Day(day, 0, emptyMap(), 0, at, null) to state.today
            else -> state.today to state.previous
        }
        val counts = current.counts.toMutableMap()
        counts[outcome] = (counts[outcome] ?: 0) + 1
        val updated = current.copy(
            checks = current.checks + 1,
            counts = counts,
            longestGapMs = maxOf(current.longestGapMs, gap),
            lastAt = at,
            nothingWhy = if (outcome == Outcome.NOTHING_TO_SAY && why != null) why else current.nothingWhy,
        )
        return State(updated, previous)
    }

    /**
     * Строки прибора: сегодня и прошлые сутки. Печатаются всегда — пустой счёт
     * назван словами, иначе неотличим от сломанного.
     */
    fun meter(state: State, now: Long): List<String> {
        val today = dayKey(now)
        val lines = ArrayList<String>(2)
        val current = state.today?.takeIf { it.day == today }
        lines += if (current == null) {
            "Первым сегодня: проверок ещё не было"
        } else {
            "Первым сегодня: ${dayText(current)}"
        }
        val previous = if (state.today != null && state.today.day != today) state.today else state.previous
        previous?.let { lines += "Первым ${shortDate(it.day)}: ${dayText(it)}" }
        return lines
    }

    private fun dayText(d: Day): String = buildString {
        append("проверок ").append(d.checks)
        append(", самая долгая пауза ").append(duration(d.longestGapMs))
        append("; написал ").append(d.counts[Outcome.WROTE] ?: 0)
        d.counts.entries
            .filter { it.key != Outcome.WROTE && it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<Outcome, Int>> { it.value }.thenBy { it.key.ordinal })
            .forEach { (o, n) ->
                append(", ").append(o.label).append(' ').append(n)
                if (o == Outcome.NOTHING_TO_SAY && d.nothingWhy != null) append(" (последнее: ").append(d.nothingWhy).append(')')
            }
    }

    /** «3 ч 40 мин», «12 мин», «меньше минуты». */
    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        if (minutes < 1) return "меньше минуты"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m мин"
            m == 0L -> "$h ч"
            else -> "$h ч $m мин"
        }
    }

    private fun shortDate(day: String): String =
        day.split('-').let { if (it.size == 3) "${it[2]}.${it[1]}" else day }

    // Хранение строкой: одни сутки — одна строка, поля через табуляцию.
    // Причина источника идёт последней, табуляции и переводы строк в ней
    // заменяются пробелами.

    fun encode(d: Day?): String? = d?.let {
        listOf(
            it.day, it.checks.toString(), it.longestGapMs.toString(), it.lastAt.toString(),
            it.counts.entries.joinToString(",") { (o, n) -> "${o.name}:$n" },
            it.nothingWhy?.replace(Regex("[\\t\\n\\r]"), " ") ?: "",
        ).joinToString("\t")
    }

    /** Разбор строки [encode]; null — пусто или не разобралось (счёт начнётся заново). */
    fun decode(s: String?): Day? {
        if (s.isNullOrBlank()) return null
        val f = s.split('\t', limit = 6)
        if (f.size < 6) return null
        val counts = f[4].split(',').filter { it.isNotBlank() }.mapNotNull { part ->
            val (name, n) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val outcome = Outcome.values().firstOrNull { it.name == name } ?: return@mapNotNull null
            outcome to (n.toIntOrNull() ?: return@mapNotNull null)
        }.toMap()
        return Day(
            day = f[0],
            checks = f[1].toIntOrNull() ?: return null,
            counts = counts,
            longestGapMs = f[2].toLongOrNull() ?: return null,
            lastAt = f[3].toLongOrNull() ?: return null,
            nothingWhy = f[5].ifBlank { null },
        )
    }
}
