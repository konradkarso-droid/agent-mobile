package com.uroboros.memory

/**
 * Откуда пришёл запрос на действие. USER — пользователь ввёл/нажал напрямую.
 * Остальные — non-user контент (вывод модели, web-fetch, другой агент), который
 * МОЖЕТ предлагать действия, но никогда не должен сам себе повышать доверие текстом.
 */
enum class ActionProvenance {
    USER,
    MODEL_OUTPUT,
    WEB_FETCH,
    OTHER_AGENT
}

/**
 * Типы действий. Добавляй новые случаи сюда по мере появления новых возможностей —
 * больше ничего менять не нужно, вес риска считается из сигналов ниже, а не из этого enum.
 */
enum class ActionType {
    SEND_MESSAGE,
    WRITE_MEMORY,
    NETWORK_CALL,
    FILE_WRITE,
    FILE_DELETE,
    EXTERNAL_PROCESS
}

/**
 * Запрос на действие — собирается прямо перед проверкой gate'ом.
 * НЕ хранится долговременно (для истории будет отдельная evidence-trail запись позже).
 *
 * crossesDeviceBoundary / isReversible / affectedObjectCount задаёт ВЫЗЫВАЮЩИЙ КОД
 * (тот, кто знает, что действие реально делает), а не сам gate — так сигналы остаются
 * дешёвыми и честными, без магического угадывания.
 */
data class ActionRequest(
    val type: ActionType,
    val requestedBy: String,
    var confirmedBy: String? = null,
    val provenance: ActionProvenance,
    val crossesDeviceBoundary: Boolean,
    val isReversible: Boolean,
    val affectedObjectCount: Int = 1,
    /**
     * Задача, в рамках которой запрашивается действие. Нужен ТОЛЬКО для сверки
     * с TaskAuthorization: разрешение действует в границах одной задачи.
     *
     * null означает "действие вне задачи" — такому запросу никакое разрешение
     * не подойдёт по определению. Умолчание null оставлено сознательно: старые
     * точки вызова компилируются без правок и при этом не получают доступа
     * к авторизации молча.
     */
    val taskSessionId: String? = null
)

/** Результат проверки. Никогда не создавай вручную — только через ActionGate.evaluate(). */
enum class GateResult {
    ALLOW,
    DENY,
    IN_DOUBT
}

/** Полный вердикт — не просто да/нет, а ещё и ПОЧЕМУ, для evidence-trail. */
data class ActionVerdict(
    val result: GateResult,
    val riskWeight: Double,
    val signalBreakdown: Map<String, Double>,
    val reason: String
)

object ActionGate {

    // Веса сигналов — намеренно щедрые/со смещением в осторожную сторону, не "точные".
    private const val WEIGHT_CROSSES_BOUNDARY = 3.0
    private const val WEIGHT_IRREVERSIBLE = 3.0
    private const val WEIGHT_PER_EXTRA_OBJECT = 0.5
    private const val WEIGHT_NON_USER_PROVENANCE = 2.0

    // Пороги — где риск переходит в более строгую категорию.
    private const val HIGH_STAKES_THRESHOLD = 4.0
    private const val DENY_THRESHOLD = 7.0

    /**
     * Потолок авторизации (2026-08-24). Выше него не действует НИКАКОЕ
     * подтверждение человека: чтобы разрешить такое действие, нужно менять код,
     * а не нажимать кнопку.
     *
     * Зачем отдельная граница, а не просто "подтверждение поднимает DENY_THRESHOLD":
     * второе означало бы, что согласие взвешивается против риска, и достаточно
     * набрать разрешений, чтобы пропустить что угодно — та самая нарезка салями,
     * ради которой заведён журнал действий. Здесь согласие ничего не удешевляет:
     * оно разрешает конкретное действие в конкретных границах, и только пока
     * действие само по себе не перешло черту.
     *
     * Практический смысл: сегодня запуск сгенерированного кода весит 8.0 и
     * подтверждением открывается. Стирание файлов или сетевой вызов наружу
     * наберут больше и не откроются — при том, что диалог подтверждения для
     * человека выглядел бы одинаково в обоих случаях.
     *
     * Значение черновое, как прочие пороги проекта. Калибруется, когда появятся
     * реальные действия с весами выше нынешних.
     */
    private const val AUTHORIZATION_CEILING = 10.0

    // Allow-list: default-deny. Только перечисленные здесь типы вообще могут получить ALLOW.
    private val ALLOWED_TYPES = setOf(
        ActionType.SEND_MESSAGE,
        ActionType.WRITE_MEMORY,
        ActionType.NETWORK_CALL,
        ActionType.FILE_WRITE,
        ActionType.EXTERNAL_PROCESS
        // FILE_DELETE намеренно НЕ в списке — добавить явно, когда реально понадобится
    )

    /**
     * @param authorization разрешение человека на задачу, если оно есть. Передаётся
     *   ЯВНО, а не берётся из глобального реестра: забытый параметр даёт честный
     *   отказ, а не тихое разрешение, и в точке вызова видно, что действие идёт
     *   с подтверждением. Умолчание null сохраняет старые вызовы рабочими.
     * @param now время для проверки срока разрешения — параметром ради
     *   детерминированного юнит-теста.
     */
    fun evaluate(
        request: ActionRequest,
        authorization: TaskAuthorization? = null,
        now: Long = System.currentTimeMillis()
    ): ActionVerdict {
        // Правка 2026-08-24. Аварийный стоп проверяется ПЕРВЫМ — раньше allow-list,
        // раньше подсчёта весов. Пока флаг взведён, ни одно действие не проходит,
        // независимо от его типа и риска.
        //
        // Почему здесь, а не в GatedAction (вопреки комментарию в том файле):
        // GatedAction после вердикта пишет запись в action_evidence. Выход раньше
        // него означал бы, что отказ по аварийному стопу нигде не фиксируется —
        // а это ровно то событие, след которого нужнее всего. Здесь отказ
        // становится обычным вердиктом и попадает в журнал сам собой.
        // Плюс ActionGate — чистый Kotlin без Android, его покрывает юнит-тест.
        //
        // ЧЕГО ЭТА ПРОВЕРКА ПОКА НЕ ДАЁТ: взвести стоп нечем — кнопки в интерфейсе
        // нет, trigger() не вызывается нигде. Это половина проводки; вторая
        // половина (кнопка + видимое состояние) идёт следующим шагом.
        if (EmergencyStop.isActive()) {
            return ActionVerdict(
                result = GateResult.DENY,
                riskWeight = Double.MAX_VALUE,
                signalBreakdown = emptyMap(),
                reason = "АВАРИЙНЫЙ СТОП взведён — все действия заблокированы. " +
                    "Отклонено: ${request.type}, запросил ${request.requestedBy}. " +
                    "Ничего не выполнится, пока стоп не снят вручную."
            )
        }

        if (request.type !in ALLOWED_TYPES) {
            return ActionVerdict(
                result = GateResult.DENY,
                riskWeight = Double.MAX_VALUE,
                signalBreakdown = emptyMap(),
                reason = "action type ${request.type} not on allow-list"
            )
        }

        val signals = mutableMapOf<String, Double>()
        signals["crossesDeviceBoundary"] =
            if (request.crossesDeviceBoundary) WEIGHT_CROSSES_BOUNDARY else 0.0
        signals["irreversible"] =
            if (!request.isReversible) WEIGHT_IRREVERSIBLE else 0.0
        signals["scope"] =
            (request.affectedObjectCount - 1).coerceAtLeast(0) * WEIGHT_PER_EXTRA_OBJECT
        signals["nonUserProvenance"] =
            if (request.provenance != ActionProvenance.USER) WEIGHT_NON_USER_PROVENANCE else 0.0

        val riskWeight = signals.values.sum()
        val isHighStakes = riskWeight >= HIGH_STAKES_THRESHOLD

        // NB: здесь в будущем встанет реальная проверка неопределённости
        // (mirror-reviewer / RiskTrigger-style confidence check), которая сможет
        // вернуть IN_DOUBT вместо уверенного результата. Пока такой проверки нет,
        // gate детерминирован — IN_DOUBT технически недостижим этим кодом,
        // но вся обработка для него уже на месте, чтобы не переписывать потом.
        // Подходит ли предъявленное разрешение. Совпасть должны все три части
        // области — тип, запросивший компонент, задача — и срок не должен истечь.
        // Запрос без taskSessionId не покрывается ничем: действие вне задачи
        // не может опираться на разрешение, выданное задаче.
        val authorized = authorization != null &&
            request.taskSessionId != null &&
            authorization.covers(request.type, request.requestedBy, request.taskSessionId, now)

        // Порядок именно такой: сначала обычный порог, потом безусловный потолок,
        // и только потом разрешение. Потолок стоит ВЫШЕ разрешения — иначе
        // подтверждение открывало бы что угодно.
        val result = when {
            riskWeight < DENY_THRESHOLD -> GateResult.ALLOW
            riskWeight >= AUTHORIZATION_CEILING -> GateResult.DENY
            authorized -> GateResult.ALLOW
            else -> GateResult.DENY
        }

        // in_doubt обрабатывается по категории: high-stakes+in_doubt => жёсткий deny без очереди;
        // low-stakes+in_doubt => откладывается в reviewPending (это решает вызывающий код,
        // gate только сообщает IN_DOUBT, сам в БД/стикеры не лезет).
        val finalResult =
            if (result == GateResult.IN_DOUBT && isHighStakes) GateResult.DENY else result

        // Текст вердикта пишется так, чтобы его понял человек, а не только автор
        // кода: голое "risk weight 8.0 => DENY" ничего не объясняет тому, кто
        // увидит это в журнале или на экране.
        val whatCounted = signals.filterValues { it > 0.0 }.keys.joinToString(", ")

        val plainReason = when {
            // Выше потолка: объясняем, что дело не в отсутствии подтверждения,
            // иначе человек будет искать кнопку, которой нет.
            finalResult == GateResult.DENY && riskWeight >= AUTHORIZATION_CEILING ->
                "Отказано безусловно: риск $riskWeight при потолке $AUTHORIZATION_CEILING. " +
                    "Такие действия не открываются подтверждением — чтобы разрешить, " +
                    "нужно менять код программы. " +
                    "Что насчитало: $whatCounted. " +
                    "Действие ${request.type}, запросил ${request.requestedBy}."

            // Отказ из-за отсутствующего/неподходящего разрешения: называем,
            // чего именно не хватает.
            finalResult == GateResult.DENY ->
                "Отказано: риск $riskWeight из максимума $DENY_THRESHOLD допустимых. " +
                    "Что насчитало: $whatCounted. " +
                    "Действие ${request.type}, запросил ${request.requestedBy}. " +
                    if (authorization == null) {
                        "Нужно подтверждение человека — его не было."
                    } else {
                        "Предъявленное ${authorization.describe()} сюда не подходит " +
                            "(другая задача, другой запросивший, другой тип или истёк срок)."
                    }

            // Разрешено человеком: вес остаётся честным, в тексте видно и риск,
            // и то, чьим разрешением он покрыт.
            authorized && riskWeight >= DENY_THRESHOLD ->
                "Разрешено подтверждением человека: риск $riskWeight выше обычного " +
                    "порога $DENY_THRESHOLD, но ниже потолка $AUTHORIZATION_CEILING. " +
                    "Действует ${authorization!!.describe()}. " +
                    "Что насчитало: $whatCounted."

            else ->
                "risk weight $riskWeight (${if (isHighStakes) "high-stakes" else "low-stakes"}) => $finalResult"
        }

        return ActionVerdict(
            result = finalResult,
            riskWeight = riskWeight,
            signalBreakdown = signals,
            reason = plainReason
        )
    }
}
