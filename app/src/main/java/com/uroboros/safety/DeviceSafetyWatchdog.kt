package com.uroboros.safety

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Физический предохранитель (Bible principle #1: физическая безопасность
 * выше когнитивного состояния). Зона считается вне зависимости от того,
 * что сейчас думает модель — ни одна функция здесь не принимает энергию
 * или confidence как параметр.
 *
 * Питание. Раньше отсюда читалась ТОЛЬКО температура батареи, поэтому агент
 * был защищён от перегрева, но не от того, что телефон просто выключится
 * посреди получасового цикла. Теперь из того же самого широковещательного
 * сообщения ACTION_BATTERY_CHANGED читаются ещё уровень заряда и факт
 * подключения к зарядке — новых разрешений и подписок не нужно.
 *
 * ВАЖНО про семантику зон: WARNING/FATIGUE — это лекарства ОТ ЖАРЫ (меньше
 * потоков, задержки между токенами). К севшей батарее они неприменимы и даже
 * вредны: задержки растягивают работу во времени и тратят больше заряда на ту
 * же задачу. Поэтому низкий заряд НЕ участвует в «мягких» зонах, а только в
 * одном крайнем случае — CRITICAL, где нужное действие совпадает: полный стоп.
 * Всё остальное решается отдельным свойством [canStartLongRun], которое
 * спрашивают ОДИН РАЗ перед запуском длинной работы, а не на каждом токене.
 *
 * НАБЛЮДАЕМОСТЬ ([ZoneWitness]). Зона почти всегда COMFORT, и с экрана
 * невозможно отличить «устройству не жарко» от «источник данных молчит».
 * Поэтому события каждого источника считаются по дороге — см. [witness] и
 * пояснение к порядку объявления полей ниже. Сам счёт ни на что не влияет:
 * зона вычисляется ровно так же, как вычислялась бы без него.
 *
 * ЧЕГО ЭТОТ КЛАСС НЕ ОБЕЩАЕТ. Он живёт столько же, сколько область, которую
 * ему передали при создании. Сегодня это область экрана: подписки встают при
 * создании и снимаются через awaitClose, когда область отменяют. Сворачивание
 * приложения наблюдение не прерывает — область отменяется при уничтожении
 * активности, а не при уходе в фон. Но пересоздание активности (например
 * поворот экрана, если он не объявлен в манифесте как обрабатываемый вручную)
 * заводит новый сторож с чистым счётом.
 */
enum class SafetyZone {
    COMFORT,   // < 40°C — полная мощность
    WARNING,   // 40-45°C — снизить число потоков
    FATIGUE,   // 45-49°C — троттлинг токенов (delay между генерацией)
    CRITICAL   // > 50°C ИЛИ заряд < 15% без зарядки — полный стоп, заморозка контекста
}

/**
 * Состояние питания устройства — сырые факты, без интерпретации.
 *
 * @param temperatureCelsius температура батареи.
 * @param percent уровень заряда 0..100, либо [UNKNOWN_PERCENT], если сообщение
 *                от системы ещё не пришло или пришло без данных.
 * @param charging подключено ли внешнее питание (сеть, USB или беспроводная).
 */
data class PowerState(
    val temperatureCelsius: Double,
    val percent: Int,
    val charging: Boolean,
) {
    val percentKnown: Boolean get() = percent != UNKNOWN_PERCENT

    companion object {
        const val UNKNOWN_PERCENT = -1
    }
}

class DeviceSafetyWatchdog(
    private val context: Context,
    externalScope: CoroutineScope
) : SafetyZoneSource {
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /**
     * Прибор наблюдения. Объявлен ДО потоков намеренно, и это не вопрос вкуса.
     *
     * Поля класса в Kotlin инициализируются сверху вниз, а [SharingStarted.Eagerly]
     * начинает собирать поток прямо во время создания объекта. То есть первое
     * событие может прийти раньше, чем закончится конструктор. Стой прибор ниже
     * потоков — сбор обратился бы к ещё не созданному полю. Падение случилось бы
     * не всегда, а только когда система успела прислать сообщение достаточно
     * быстро, то есть на чужом телефоне и не при отладке.
     *
     * Сюда только пишут; наружу отдаётся неизменяемый снимок ([zoneObservation]),
     * а не сам прибор, чтобы записывать в него могли только эти два потока.
     */
    private val witness = ZoneWitness(startedAtMs = System.currentTimeMillis())

    private fun thermalStatusFlow(): Flow<Int> = callbackFlow {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                trySend(status)
            }
            powerManager.addThermalStatusListener(listener)
            trySend(powerManager.currentThermalStatus)
            awaitClose { powerManager.removeThermalStatusListener(listener) }
        } else {
            trySend(PowerManager.THERMAL_STATUS_NONE)
            awaitClose { }
        }
    }

    /**
     * Одно сообщение ACTION_BATTERY_CHANGED несёт и температуру, и заряд, и
     * подключение к питанию — поэтому поток один, а не три.
     */
    private fun powerStateFlow(): Flow<PowerState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return

                val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                val celsius = if (tenths >= 0) tenths / 10.0 else 0.0

                // Шкала не всегда 100: считаем процент честно, через scale.
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val percent = if (level >= 0 && scale > 0) {
                    (level * 100) / scale
                } else {
                    PowerState.UNKNOWN_PERCENT
                }

                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val charging = plugged != 0

                trySend(PowerState(celsius, percent, charging))
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        awaitClose { context.unregisterReceiver(receiver) }
    }

    /**
     * Текущее состояние питания. Начальное значение — «заряд неизвестен»:
     * система пришлёт настоящее почти сразу после подписки.
     *
     * Счёт событий стоит ДО stateIn намеренно. StateFlow схлопывает одинаковые
     * подряд значения, поэтому на нём считались бы не сообщения от системы, а
     * изменения показаний: телефон с ровной температурой выглядел бы как
     * телефон с мёртвой подпиской — ровно та ошибка, против которой прибор и
     * поставлен.
     */
    val power: StateFlow<PowerState> = powerStateFlow()
        .onEach { state ->
            witness.recordPower(
                temperatureZone = zoneFromBatteryTemp(state.temperatureCelsius),
                chargeZone = zoneFromCharge(state),
                atMs = System.currentTimeMillis(),
            )
        }
        .stateIn(
            scope = externalScope,
            started = SharingStarted.Eagerly,
            initialValue = PowerState(0.0, PowerState.UNKNOWN_PERCENT, charging = false)
        )

    private fun zoneFromThermalStatus(status: Int): SafetyZone = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> SafetyZone.COMFORT
        PowerManager.THERMAL_STATUS_LIGHT,
        PowerManager.THERMAL_STATUS_MODERATE -> SafetyZone.WARNING
        PowerManager.THERMAL_STATUS_SEVERE -> SafetyZone.FATIGUE
        else -> SafetyZone.CRITICAL
    }

    private fun zoneFromBatteryTemp(celsius: Double): SafetyZone = when {
        celsius < 40.0 -> SafetyZone.COMFORT
        celsius < 45.0 -> SafetyZone.WARNING
        celsius < 50.0 -> SafetyZone.FATIGUE
        else -> SafetyZone.CRITICAL
    }

    /**
     * Заряд даёт ровно два исхода, без промежуточных: либо всё равно (COMFORT),
     * либо стоп (CRITICAL). Промежуточных «притормозить» здесь быть не может —
     * см. пояснение к [SafetyZone].
     *
     * Неизвестный заряд не останавливает работу: настоящее значение придёт
     * через долю секунды после подписки, а тормозить из-за отсутствия данных
     * значит не запускаться в первую секунду после старта приложения.
     */
    private fun zoneFromCharge(state: PowerState): SafetyZone = when {
        state.charging -> SafetyZone.COMFORT
        !state.percentKnown -> SafetyZone.COMFORT
        state.percent < STOP_RUNNING_BELOW_PERCENT -> SafetyZone.CRITICAL
        else -> SafetyZone.COMFORT
    }

    private fun stricterOf(a: SafetyZone, b: SafetyZone): SafetyZone =
        if (a.ordinal >= b.ordinal) a else b

    /**
     * Итоговая зона — строжайшая из трёх независимых оценок.
     *
     * Счёт событий теплового статуса стоит ДО combine по той же причине, что и
     * у питания: наружу combine отдаёт уже сведённый результат, и по нему
     * невозможно узнать, какая из трёх оценок его дала. А это и есть главный
     * вопрос при разборе: половина механизма может молчать, а зона выглядеть
     * исправной.
     */
    override val zone: StateFlow<SafetyZone> = combine(
        thermalStatusFlow().onEach { status ->
            witness.recordThermalStatus(
                zone = zoneFromThermalStatus(status),
                atMs = System.currentTimeMillis(),
            )
        },
        power
    ) { thermalStatus, powerState ->
        val thermal = stricterOf(
            zoneFromThermalStatus(thermalStatus),
            zoneFromBatteryTemp(powerState.temperatureCelsius)
        )
        stricterOf(thermal, zoneFromCharge(powerState))
    }.stateIn(
        scope = externalScope,
        started = SharingStarted.Eagerly,
        initialValue = SafetyZone.COMFORT
    )

    /**
     * Снимок наблюдения: сколько событий пришло от каждого источника, когда
     * пришло последнее, до чего доходила каждая из трёх оценок.
     *
     * Отвечает на вопрос, на который не отвечает [zone]: считается ли она
     * вообще. Границы прибора описаны в [ZoneWitness] — читать их обязательно
     * перед тем, как делать выводы из показаний.
     */
    fun zoneObservation(): ZoneWitnessSnapshot =
        witness.snapshot(System.currentTimeMillis())

    /**
     * То же наблюдение готовой строкой для экрана.
     *
     * @param label как называть зону по-русски. Приходит снаружи: название для
     *              человека уже живёт на экране, и вторая копия здесь разошлась
     *              бы с первой молча.
     */
    fun formatZoneObservation(label: (SafetyZone) -> String): String =
        witness.format(zoneObservation(), label)

    /**
     * Можно ли НАЧИНАТЬ длинную работу (TOTE-цикл): порог здесь выше, чем порог
     * остановки, и это намеренно. Бессмысленно браться за получасовую задачу с
     * запасом, которого заведомо не хватит до конца, — при этом уже начатую
     * работу останавливаем позже, чтобы не бросать её у самого финиша.
     *
     * Свойство само себя не применяет: его должен спросить тот, кто запускает
     * цикл. Жара сюда включена тоже — начинать в CRITICAL нельзя ни по какой
     * причине.
     */
    val canStartLongRun: Boolean
        get() {
            if (zone.value == SafetyZone.CRITICAL) return false
            val state = power.value
            if (state.charging) return true
            if (!state.percentKnown) return true
            return state.percent >= START_RUN_MIN_PERCENT
        }

    /** Человекочитаемая причина отказа — для отчёта на экране. null, если можно. */
    fun longRunBlockReason(): String? {
        if (zone.value == SafetyZone.CRITICAL) {
            return "устройство в критической зоне (перегрев или разряд)"
        }
        val state = power.value
        if (!state.charging && state.percentKnown && state.percent < START_RUN_MIN_PERCENT) {
            return "заряд ${state.percent}% без зарядки, нужно от $START_RUN_MIN_PERCENT%"
        }
        return null
    }

    private var continuousInferenceStartMs: Long = 0L

    /** Отмечает начало непрерывной генерации. Вызывается перед стартом inference. */
    fun markInferenceStarted() {
        if (continuousInferenceStartMs == 0L) {
            continuousInferenceStartMs = System.currentTimeMillis()
        }
    }

    /** Сбрасывает таймер непрерывной работы. Вызывается после паузы/остановки. */
    fun resetInferenceTimer() {
        continuousInferenceStartMs = 0L
    }

    /**
     * Жёсткий потолок: пауза после каждых 5 минут непрерывной работы,
     * не зависит от зоны и не может быть отменена когнитивным состоянием (item 7a).
     */
    fun shouldForceCooldown(): Boolean {
        if (continuousInferenceStartMs == 0L) return false
        val elapsedMs = System.currentTimeMillis() - continuousInferenceStartMs
        return elapsedMs >= HARD_TIMEOUT_MS
    }

    companion object {
        private const val HARD_TIMEOUT_MS = 5 * 60 * 1000L // 5 минут
        const val COOLDOWN_MS = 45 * 1000L // 45 секунд паузы

        /** Ниже этого заряда без зарядки не НАЧИНАТЬ длинную работу. */
        const val START_RUN_MIN_PERCENT = 30

        /** Ниже этого заряда без зарядки ОСТАНОВИТЬ уже идущую работу. */
        const val STOP_RUNNING_BELOW_PERCENT = 15
    }
}
