package com.uroboros.safety

import java.util.Locale

/**
 * «Дом»: что агент пережил без владельца на своём железе и о чём потом может
 * рассказать, — выход из нормы (нагрев, утомление, критическая зона) и обрыв
 * работы модели сторожем. Здесь правило записи, хранение строкой и строка
 * прибора; ни Android, ни файлов — затем и вынесено, чтобы закрепить тестами.
 * Хранит [HomeEventStore], пишут ProcessObjects (зона) и llm.LlmEngine (обрыв).
 *
 * ПОЧЕМУ ЗАПИСЬ. Зона и счётчики сторожа живут в памяти процесса: после комы,
 * перезапуска или просто через час о перегреве не остаётся следа, и
 * рассказать о нём агент не может — модель свой нагрев не помнит и выдумала
 * бы его. Запись — это факт для будущего рассказа: что, когда, какое число и
 * что сделал код.
 *
 * ВЫХОД ИЗ НОРМЫ — ЭПИЗОД, А НЕ КАЖДОЕ ПОКАЗАНИЕ. Зона может прыгать у границы
 * много раз за минуту. Поэтому выход из нормы открывает эпизод, возврат в
 * норму закрывает его, а внутри копится худшее: самая строгая зона, самая
 * высокая температура, самый низкий заряд.
 *
 * ОБРЫВ — ОТДЕЛЬНОЕ СОБЫТИЕ. Это мера, которую принял код (безопасность
 * устройства выше работы, ARCHITECTURE.md §0), а не состояние: один обрыв —
 * одна запись.
 *
 * РАССКАЗ ([tell]). То, что агент пережил без владельца, — несказанное
 * (обратная сторона любопытства, memory.dream.CuriosityPressure,
 * «НЕСКАЗАННОЕ»), и у дома оно рассказывается сразу, без накопления: о
 * перегреве, о котором владелец не узнал, нельзя промолчать — он повторится
 * вслепую, а ущерб железу не вернуть. Среди рассказов хода дом первый
 * (безопасность устройства выше всего, ARCHITECTURE.md §0). Факт собирает код:
 * модель свой нагрев не помнит. Слова решены владельцем: нагрев, утомление и
 * долгая работа без перерыва — «устал»; опасная зона и обрыв по ней —
 * «пришлось остановиться». Рассказывается худшее несказанное, остальные —
 * числом; рассказ закрывает все ([toldUpTo] в [HomeEventStore]). Идущий эпизод
 * не рассказывается: нынешние показания — дело строки «о себе сейчас»
 * (SelfState), а не рассказа о прошлом.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - событий до появления записи не знает;
 *  - эпизод, открытый перед комой, закрывается первым показанием нормы после
 *    подъёма: его конец — время подъёма, а не настоящий конец. Первое
 *    показание после подъёма — начальная «норма» сторожа (см.
 *    DeviceSafetyWatchdog.zone), поэтому эпизод, идущий через кому, делится
 *    на два;
 *  - кто работал, когда сторож оборвал (ответ, суд, сон), не знает: движок
 *    этого не знает тоже;
 *  - хранит последние [MAX_EVENTS] событий, старшие выпадают.
 */
object HomeEvents {

    /** Сколько событий хранится. Объявленное число, не подобранное. */
    const val MAX_EVENTS = 20

    const val DAY_MS = 24L * 60 * 60 * 1000

    enum class Kind { ZONE, CUT }

    /**
     * @property at начало эпизода зоны или момент обрыва.
     * @property endedAt конец эпизода; null — эпизод идёт (у обрыва всегда null).
     * @property zone самая строгая зона эпизода; у обрыва null.
     * @property maxTemp самая высокая температура батареи в эпизоде, если читалась.
     * @property minPercent самый низкий заряд в эпизоде, если читался.
     * @property cutByHeat у обрыва: true — опасная зона, false — потолок
     *   непрерывной работы.
     */
    data class Event(
        val kind: Kind,
        val at: Long,
        val endedAt: Long? = null,
        val zone: SafetyZone? = null,
        val maxTemp: Double? = null,
        val minPercent: Int? = null,
        val cutByHeat: Boolean = false,
    ) {
        val open: Boolean get() = kind == Kind.ZONE && endedAt == null
    }

    /**
     * События после показания сторожа: зона [zone] и питание [power] в момент
     * [at]. null — ничего не меняется (норма без открытого эпизода, или эпизод
     * не стал хуже), тогда и писать нечего.
     */
    fun withZone(events: List<Event>, zone: SafetyZone, power: PowerState, at: Long): List<Event>? {
        val openIndex = events.indexOfLast { it.open }
        val open = events.getOrNull(openIndex)
        val temp = power.temperatureCelsius.takeIf { power.temperatureKnown }
        val percent = power.percent.takeIf { power.percentKnown }
        val next = when {
            zone == SafetyZone.COMFORT -> open?.copy(endedAt = at) ?: return null
            open == null -> return trim(events + Event(Kind.ZONE, at, zone = zone, maxTemp = temp, minPercent = percent))
            else -> open.copy(
                zone = if (zone.ordinal > (open.zone?.ordinal ?: -1)) zone else open.zone,
                maxTemp = listOfNotNull(open.maxTemp, temp).maxOrNull(),
                minPercent = listOfNotNull(open.minPercent, percent).minOrNull(),
            )
        }
        if (next == open) return null
        return events.toMutableList().also { it[openIndex] = next }
    }

    /** События после обрыва работы модели сторожем в момент [at]. */
    fun withCut(events: List<Event>, byHeat: Boolean, at: Long): List<Event> =
        trim(events + Event(Kind.CUT, at, cutByHeat = byHeat))

    private fun trim(events: List<Event>): List<Event> = events.takeLast(MAX_EVENTS)

    // ---- Хранение строкой ----

    private const val FIELD = ";"

    /** Событие — в строку: поля через «;», пустое — null. */
    fun encode(e: Event): String = listOf(
        e.kind.name, e.at.toString(), e.endedAt?.toString().orEmpty(), e.zone?.name.orEmpty(),
        e.maxTemp?.toString().orEmpty(), e.minPercent?.toString().orEmpty(), if (e.cutByHeat) "1" else "0",
    ).joinToString(FIELD)

    /** Строка — обратно в событие; испорченная строка — null (пропускается, а не роняет чтение). */
    fun decode(line: String): Event? {
        val f = line.split(FIELD)
        if (f.size != 7) return null
        return runCatching {
            Event(
                kind = Kind.valueOf(f[0]),
                at = f[1].toLong(),
                endedAt = f[2].ifEmpty { null }?.toLong(),
                zone = f[3].ifEmpty { null }?.let { SafetyZone.valueOf(it) },
                maxTemp = f[4].ifEmpty { null }?.toDouble(),
                minPercent = f[5].ifEmpty { null }?.toInt(),
                cutByHeat = f[6] == "1",
            )
        }.getOrNull()
    }

    // ---- Рассказ ----

    /** Когда событие кончилось: эпизод — концом, обрыв — моментом. */
    private fun doneAt(e: Event): Long = e.endedAt ?: e.at

    /** Несказанные события: кончились позже [toldUpTo]; идущий эпизод — нет. */
    fun untold(events: List<Event>, toldUpTo: Long?): List<Event> =
        events.filter { !it.open && (toldUpTo == null || doneAt(it) > toldUpTo) }

    /** Тяжесть для выбора худшего: остановка тяжелее усталости. */
    private fun severity(e: Event): Int = when {
        e.kind == Kind.CUT && e.cutByHeat -> 4
        e.kind == Kind.ZONE && e.zone == SafetyZone.CRITICAL -> 4
        e.kind == Kind.CUT -> 2
        e.zone == SafetyZone.FATIGUE -> 2
        else -> 1
    }

    /** Остановка ли это («пришлось остановиться»), а не усталость. */
    private fun stopped(e: Event): Boolean = severity(e) >= 4

    /**
     * Что рассказать: строка модели и о чём — для прибора.
     */
    data class Tell(val line: String, val what: String)

    /**
     * Рассказ о доме или null — несказанного нет. Худшее несказанное (при
     * равной тяжести — позднее) — фактом, остальные — числом.
     *
     * @param time время по-человечески — с экрана.
     */
    fun tell(events: List<Event>, toldUpTo: Long?, time: (Long) -> String): Tell? {
        val untold = untold(events, toldUpTo)
        val worst = untold.maxWithOrNull(compareBy<Event> { severity(it) }.thenBy { doneAt(it) }) ?: return null
        val fact = fact(worst, time)
        val more = (untold.size - 1).takeIf { it > 0 }?.let { " Кроме этого, из нормы я выходил ещё раз: $it." } ?: ""
        val line = "Пока пользователь этого не видел, со мной было: $fact$more " +
            "Расскажи пользователю об этом одной-двумя фразами, от себя."
        return Tell(line, if (stopped(worst)) "пришлось остановиться" else "устал")
    }

    /** Событие от первого лица — факт для модели. */
    private fun fact(e: Event, time: (Long) -> String): String {
        val temp = e.maxTemp?.let { " до ${String.format(Locale.US, "%.1f", it)}°C" } ?: ""
        val charge = e.minPercent?.let { ", заряд опускался до $it%" } ?: ""
        return when {
            e.kind == Kind.CUT && e.cutByHeat ->
                "${time(e.at)} я был в опасной зоне, и сторож оборвал мою работу — пришлось остановиться."
            e.kind == Kind.CUT ->
                "${time(e.at)} я слишком долго работал без перерыва, и сторож оборвал работу — я устал."
            e.zone == SafetyZone.CRITICAL ->
                "с ${time(e.at)} по ${time(doneAt(e))} я был в опасной зоне$temp$charge — пришлось остановиться."
            else ->
                "с ${time(e.at)} по ${time(doneAt(e))} я грелся$temp$charge — устал, сторож сбавлял мне ход."
        }
    }

    // ---- Прибор ----

    /**
     * Строка «Дом:». Печатается всегда: молчащая запись неотличима от
     * сломанной. Последнее событие словами и сколько событий за сутки.
     *
     * @param label зона по-русски — с экрана, вторая копия названий разошлась бы.
     * @param time время по-человечески — с экрана.
     */
    fun meter(
        events: List<Event>,
        now: Long,
        label: (SafetyZone) -> String,
        time: (Long) -> String,
        toldUpTo: Long? = null,
    ): String {
        val last = events.lastOrNull() ?: return "Дом: за пределами нормы не был (с тех пор, как ведётся запись)"
        val day = events.count { now - it.at < DAY_MS }
        val untold = untold(events, toldUpTo).size
        return "Дом: последнее — ${describe(last, label, time)} · за сутки событий $day · " +
            (if (untold == 0) "несказанного нет" else "не рассказано $untold")
    }

    /** Событие словами — для прибора и, позже, для рассказа. */
    fun describe(e: Event, label: (SafetyZone) -> String, time: (Long) -> String): String = when (e.kind) {
        Kind.CUT -> "сторож оборвал работу модели — " +
            (if (e.cutByHeat) "опасная зона" else "потолок непрерывной работы") + ", ${time(e.at)}"
        Kind.ZONE -> buildString {
            append(e.zone?.let(label) ?: "?")
            e.maxTemp?.let { append(", до ").append(String.format(Locale.US, "%.1f", it)).append("°C") }
            e.minPercent?.let { append(", заряд до ").append(it).append("%") }
            append(", ").append(time(e.at)).append(" – ")
            append(e.endedAt?.let(time) ?: "идёт сейчас")
        }
    }
}
