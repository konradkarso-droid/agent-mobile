package com.uroboros.memory.dream

import com.uroboros.memory.judge.SelfJudgeDecision

/**
 * Шаги ночи без кнопки: строка о себе ([SelfLineStep]), зеркало ([MirrorStep])
 * и выводы ([ConclusionStep]) для ПОСЛЕДНЕЙ ночи, у которой их ещё нет. Чистое
 * решение, без базы и Android; зовёт тело агента (AgentService) раз в минуту
 * бодрствования, сразу за проверкой сна.
 *
 * ЗАЧЕМ. Ночь по кнопке делает шаги сама, а самостоятельный сон — только сны:
 * ему модель не нужна, и он идёт при любом заряде. Без этого шаги случались бы
 * лишь тогда, когда владелец не забыл нажать, — а агент живёт и тогда, когда
 * владельца нет рядом днями.
 *
 * ПОВОД — ПОСЛЕДНЯЯ НОЧЬ БЕЗ ИТОГА ШАГА. Не часы и не время суток: ночь
 * наступает, когда агент уснул (см. [SleepDecision]), а засыпает он, только
 * когда накопилось новое. Нет разговора — нет новых записей, нет ночей, нет и
 * шагов: заряд впустую не тратится. Сделан шаг или нет, видно по итогу в строке
 * ночи ([DreamNight.selfLineOutcome], [DreamNight.mirrorOutcome],
 * [DreamNight.conclusionsOutcome]): null — шага не было. Делаются только
 * недостающие; ночь по кнопке пишет итоги всех трёх и повторно не трогается.
 *
 * УСЛОВИЯ ([refusal]) — те же, что у долгой работы модели без человека, но
 * БЕЗ ЗАРЯДКИ. В отличие от суда, шаги короткие — пять обращений к модели, а
 * не часы, — поэтому держать телефон на зарядке ради них не нужно. Нижняя
 * граница заряда — у сторожа (DeviceSafetyWatchdog.longRunBlockReason: без
 * зарядки не начинать ниже его порога); здесь число не повторяется. Тишина —
 * та же, что у суда ([SelfJudgeDecision.QUIET_MS]): модель не грузится в паузе
 * живого разговора.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - достаётся только последней ночи: две ночи подряд без подходящих условий
 *    — шаги получит вторая, первая останется без них;
 *  - итог «не делаю — <причина>» — тоже итог: шаг, отказавший из-за условий
 *    посреди захода (нагрелся, заряд упал ниже порога), для этой ночи не
 *    повторяется;
 *  - оборванный заход (владелец написал, остановили) доделывается при
 *    следующей тишине — но только недостающими шагами: уже записанные не
 *    переделываются;
 *  - сколько заряда уходит на заход, не рассчитано: оно МЕРЯЕТСЯ — заряд до и
 *    после пишется в итог захода ([battery]). По этим строкам и решать, не
 *    поднять ли порог.
 */
object NightSteps {

    /**
     * Потолок блокировки процессора на заход — страховка, а не расписание:
     * шаги кончаются сами, намного раньше. Объявленное число.
     */
    const val BUDGET_MS = 20L * 60 * 1000

    /**
     * Сколько ждать после того, как модель не загрузилась. То же, что отдых
     * суда после прогона: загрузка — гигабайты и десятки секунд, повторять её
     * раз в минуту значило бы греть телефон впустую.
     */
    const val REST_AFTER_FAILED_LOAD_MS = SelfJudgeDecision.REST_AFTER_RUN_MS

    const val HEAD = "Шаги ночи: "

    /** Каких шагов у ночи нет. */
    data class Missing(val selfLine: Boolean, val mirror: Boolean, val conclusions: Boolean) {
        val any: Boolean get() = selfLine || mirror || conclusions

        /** Недостающие словами: «строка о себе, зеркало, выводы». */
        fun words(): String = listOfNotNull(
            "строка о себе".takeIf { selfLine },
            "зеркало".takeIf { mirror },
            "выводы".takeIf { conclusions },
        ).joinToString(", ")
    }

    fun missing(night: DreamNight): Missing = Missing(
        selfLine = night.selfLineOutcome == null,
        mirror = night.mirrorOutcome == null,
        conclusions = night.conclusionsOutcome == null,
    )

    data class Inputs(
        /** Идёт разбор, ночь или другой заход. */
        val running: Boolean,
        val emergencyStop: Boolean,
        /** Прислал ли сторож хоть одно показание батареи. */
        val powerKnown: Boolean,
        /** Отказ сторожа словами (longRunBlockReason); null — можно. */
        val watchdogRefusal: String?,
        val engineBusy: Boolean,
        /** Сколько тихо: с конца последнего обращения к модели или с подъёма тела. */
        val quietMs: Long,
        /** Сколько ещё ждать после незагрузившейся модели; ≤ 0 — не ждать. */
        val restLeftMs: Long,
        /** Выбиралась ли модель хоть раз — иначе загружать нечего. */
        val modelChosen: Boolean,
    )

    /**
     * Почему шаги сейчас не начинаются, словами; null — можно. Первое
     * невыполненное условие и называется. Сторож без показаний считается
     * неработающим: без живой остановки по нагреву модель не зовётся.
     */
    fun refusal(i: Inputs): String? = when {
        i.running -> "идёт разбор или ночь"
        i.emergencyStop -> "взведён аварийный стоп"
        !i.powerKnown -> "сторож ещё не прислал показаний батареи"
        i.watchdogRefusal != null -> i.watchdogRefusal
        i.engineBusy -> "модель занята"
        i.quietMs < SelfJudgeDecision.QUIET_MS ->
            "тихо ${minutes(i.quietMs)} мин из ${minutes(SelfJudgeDecision.QUIET_MS)}"
        i.restLeftMs > 0 -> "модель не загрузилась, следующая попытка через ${minutes(i.restLeftMs) + 1} мин"
        !i.modelChosen -> "модель ни разу не выбиралась — загружать нечего"
        else -> null
    }

    /**
     * Заряд до и после захода: так видно, сколько он стоит. null — показания
     * не было. «на зарядке» — если заход начался на зарядке: тогда разница
     * о цене ничего не говорит.
     */
    fun battery(before: Int?, after: Int?, charging: Boolean): String {
        val from = before?.let { "$it%" } ?: "?"
        val to = after?.let { "$it%" } ?: "?"
        return "заряд $from → $to" + if (charging) ", на зарядке" else ""
    }

    /**
     * Итог захода одной строкой: первая строка итога каждого шага через « · ».
     * Полные итоги лежат в строке ночи и видны в «Подробно».
     */
    fun summary(outcomes: List<String>): String =
        outcomes.mapNotNull { it.lineSequence().firstOrNull { line -> line.isNotBlank() } }
            .joinToString(" · ")
            .ifEmpty { "шаги ничего не сказали" }

    private fun minutes(ms: Long): Long = ms.coerceAtLeast(0) / 60_000
}
