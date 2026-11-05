package com.uroboros.safety

import java.util.Locale

/**
 * Выгрузка модели по сигналу памяти телефона — решение и строки экрана.
 * Чистая логика: замеры и сигналы приносит служба (AgentService), выгружает
 * движок (LlmEngine.unloadForMemory).
 *
 * ЗАЧЕМ. Модель держится в памяти между ответами (около 4.4 ГБ на телефоне в
 * 8 ГБ), и система убивает процесс агента, когда памяти не хватает, — в
 * разговоре и в простое. Кома в простое стоит того же подъёма модели при
 * следующем сообщении, что и выгрузка, но вдобавок теряет всё, что живёт в
 * памяти процесса. Выгрузка по сигналу меняет неуправляемую смерть на
 * управляемое освобождение памяти. Порядок ARCHITECTURE.md §0: безопасность
 * устройства выше полезности.
 *
 * СИГНАЛ — ДВА ИСТОЧНИКА:
 *  - замер системы (ActivityManager.MemoryInfo): флаг «мало» — свободно ниже
 *    системного порога, с которого система начинает убивать фоновые
 *    приложения. Порог — системный, своего запаса нет ([MARGIN_BYTES] = 0,
 *    объявленное число);
 *  - onTrimMemory уровня «в фоне» и выше ([TRIM_BACKGROUND]). С API 34 система
 *    не шлёт приложениям уровней давления (RUNNING_*, MODERATE, COMPLETE) —
 *    поэтому опрос, а не только обратный вызов. «Экран скрыт» (UI_HIDDEN) —
 *    не о памяти и сигналом не считается.
 *
 * НЕ ВЫГРУЖАЕТ:
 *  - посреди работы — ответа, суда, ночи: модель оборвала бы их, как кома.
 *    Сигнал не копится: следующий замер решит заново;
 *  - при открытом экране приложения: владелец там, и ответ на экране нашёл бы
 *    модель выгруженной. Экран, открываясь, поднимает модель сам.
 *
 * ЧЕГО НЕ УМЕЕТ: кома, пришедшая раньше сигнала или посреди работы, остаётся
 * комой; придёт ли флаг «мало» до комы — не мерено, это и показывает строка
 * «перед комой» ([beforeComa]); подъём после выгрузки — на первом же деле
 * (сообщение, инициатива, ночь, экран), он не быстрее подъёма после комы.
 */
object MemoryPressure {

    /** Как часто служба спрашивает систему. Объявленное число, не подобранное. */
    const val POLL_MS = 15_000L

    /** Запас над системным порогом. Объявленное число: ноль — порог системы как есть. */
    const val MARGIN_BYTES = 0L

    /** Уровень onTrimMemory «в фоне» (ComponentCallbacks2.TRIM_MEMORY_BACKGROUND): от него и выше — сигнал. */
    const val TRIM_BACKGROUND = 40

    /**
     * Замер системы: свободно [availBytes], порог [thresholdBytes], флаг
     * системы «мало» [low], когда — [at].
     */
    data class Reading(val availBytes: Long, val thresholdBytes: Long, val low: Boolean, val at: Long)

    /** Мало ли памяти по замеру: флаг системы или свободно не выше порога с запасом. */
    fun isLow(r: Reading): Boolean = r.low || r.availBytes <= r.thresholdBytes + MARGIN_BYTES

    sealed class Decision {
        /** Выгрузить: [why] — сигнал словами. */
        data class Unload(val why: String) : Decision()

        /** Сигнал есть, но не сейчас: [why] — что мешает. */
        data class Hold(val why: String) : Decision()

        /** Сигнала нет или выгружать нечего. */
        object Nothing : Decision()
    }

    /**
     * Решение по замеру [r] и уровню onTrimMemory [trimLevel] (null — не
     * приходил с прошлого замера). [modelLoaded] — модель в памяти; [busy] —
     * какая работа идёт («ответ», «суд или ночь»), null — движок свободен;
     * [screenShown] — экран приложения открыт.
     */
    fun decide(r: Reading?, trimLevel: Int?, modelLoaded: Boolean, busy: String?, screenShown: Boolean): Decision {
        val why = listOfNotNull(
            r?.takeIf(::isLow)?.let { "свободно ${gb(it.availBytes)} при пороге ${gb(it.thresholdBytes)}" },
            trimLevel?.takeIf { it >= TRIM_BACKGROUND }?.let { "система просит освободить память (уровень $it)" },
        ).joinToString("; ")
        if (why.isEmpty() || !modelLoaded) return Decision.Nothing
        busy?.let { return Decision.Hold("идёт $it") }
        if (screenShown) return Decision.Hold("открыт экран")
        return Decision.Unload(why)
    }

    /** Строка экрана: последний замер, выгрузки за сутки ([unloadsToday] — моменты), что последним мешало. */
    fun meterLine(r: Reading?, unloadsToday: List<Long>, lastHold: String?, clock: (Long) -> String): String {
        val now = r?.let { "свободно ${gb(it.availBytes)}, порог ${gb(it.thresholdBytes)}" + if (isLow(it)) ", мало" else "" }
            ?: "замера ещё не было"
        val unloads = if (unloadsToday.isEmpty()) "выгрузок по сигналу за сутки нет"
        else "выгрузок по сигналу за сутки ${unloadsToday.size}, последняя ${clock(unloadsToday.max())}"
        val hold = lastHold?.let { " · не выгружал: $it" } ?: ""
        return "Память телефона: $now · $unloads$hold"
    }

    /**
     * Строка «перед комой»: последний замер [r], сделанный до комы в [comaAt]
     * не раньше чем за два опроса; иначе null — замер не о той коме.
     */
    fun beforeComa(r: Reading?, comaAt: Long): String? {
        if (r == null || r.at > comaAt || comaAt - r.at > 2 * POLL_MS) return null
        return "Перед комой: свободно ${gb(r.availBytes)}, порог ${gb(r.thresholdBytes)}, «мало»: " + if (isLow(r)) "да" else "нет"
    }

    /** Выгрузки за сутки до [now] из [all]. */
    fun today(all: List<Long>, now: Long): List<Long> = all.filter { now - it in 0..DAY_MS }

    private const val DAY_MS = 24 * 60 * 60_000L

    /** Байты словами — «1.2 ГБ». */
    fun gb(bytes: Long): String = String.format(Locale.US, "%.1f ГБ", bytes / (1024.0 * 1024 * 1024))
}
