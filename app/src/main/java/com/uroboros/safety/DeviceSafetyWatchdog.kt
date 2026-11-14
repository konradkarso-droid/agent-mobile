package com.uroboros.safety

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * ВАЖНО про семантику зон: FATIGUE — это лекарство ОТ ЖАРЫ (паузы в
 * выдаче), WARNING только отмечает нагрев. К севшей батарее они неприменимы и даже
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
 * НЕПРОЧИТАННАЯ НОГА — ТРЕТЬЕ ЗНАЧЕНИЕ, А НЕ COMFORT. Сообщение о батарее
 * может прийти без температуры или без уровня заряда. Оценки этих ног тогда
 * не существует, и наружу это идёт как `null` — до самого прибора, без
 * подмены по дороге. Подмена нужна ровно в одном месте, где зона обязана быть
 * одной буквой, и живёт она в [comfortIfUnread] вместе с ценой.
 *
 * ЧЕГО ЭТОТ КЛАСС НЕ ОБЕЩАЕТ. Он живёт столько же, сколько область, которую
 * ему передали при создании. Сегодня это область экрана: подписки встают при
 * создании и снимаются через awaitClose, когда область отменяют. Сворачивание
 * приложения наблюдение не прерывает — область отменяется при уничтожении
 * активности, а не при уходе в фон. Но пересоздание активности (например
 * поворот экрана, если он не объявлен в манифесте как обрабатываемый вручную)
 * заводит новый сторож с чистым счётом.
 */
/**
 * Зона сторожа и что она делает. ОДНО ПРАВИЛО ДЛЯ ВСЕХ ПУТЕЙ — экрана,
 * телеграма, «пишу первым», ночи и разбора памяти: своих порогов зоны у путей
 * нет. Начинать работу модели или нет, решает [DeviceSafetyWatchdog.longRunBlockReason]
 * (критическая зона и низкий заряд); обрывает идущую работу
 * llm.LlmEngine.barrierStopReason (критическая зона и потолок непрерывной
 * работы); паузы в выдаче — там же, в утомлении.
 *
 * Почему одно. Разные пороги у разных путей давали не осторожность, а
 * молчание: необязательная работа (телеграм) замолкала от лёгкого нагрева,
 * который экран спокойно переживал, — то есть защищала железо от нагрузки
 * меньшей, чем та, что шла рядом без защиты. Выход из нормы у необязательной
 * работы не теряется: каждый эпизод пишется в «дом» (HomeEvents), и агент о
 * нём рассказывает.
 *
 * Чем зона вызвана — у [ThermalStatus] (статус системы) и у
 * DeviceSafetyWatchdog.zoneFromBatteryTemp (батарея).
 */
enum class SafetyZone {
    COMFORT,   // работа без ограничений
    WARNING,   // ничего не останавливает — отмечается в «доме» как нагрев
    FATIGUE,   // придерживает выдачу паузой (не на токен, см. ниже)
    CRITICAL   // не начинать и обрывать любую работу модели
}

/*
 * ПОЧЕМУ ПАУЗА В FATIGUE ДЕЙСТВУЕТ СЛАБЕЕ, ЧЕМ ЧИТАЕТСЯ ИЗ НАЗВАНИЯ ЗОНЫ.
 * Придерживает выдачу не этот класс, а тот, кто собирает поток генерации, и
 * пауза приходится у него на событие потока. Событие с текстом — это кусок
 * строки, а не токен: в замере на семнадцать токенов ответа через барьер
 * прошло пять событий, включая служебные. Значит пауза случается в несколько
 * раз реже, чем на каждый токен, и зона сверяется тогда же.
 *
 * Достаточно ли этого, чтобы щадить железо, НЕ ПРОВЕРЕНО: зона утомления на
 * устройстве не наблюдалась ни разу.
 */

/**
 * Состояние питания устройства — сырые факты, без интерпретации.
 *
 * @param temperatureCelsius температура батареи. Значение осмысленно ТОЛЬКО
 *                при [temperatureKnown] `true`; иначе поле не значит ничего и
 *                читать его нельзя. Ноль оставлен там намеренно: он не
 *                выплывает на экран так, как выплыл бы часовой вроде -1, а
 *                истину несёт булево рядом.
 * @param percent уровень заряда 0..100, либо [UNKNOWN_PERCENT], если сообщение
 *                от системы ещё не пришло или пришло без данных.
 * @param charging подключено ли внешнее питание (сеть, USB или беспроводная).
 * @param temperatureKnown было ли в сообщении показание температуры. Отдельное
 *                поле, а не часовое значение: 0.0 °C — законное показание, и
 *                метка «нет данных» не должна совпадать ни с одним из них.
 *
 * Порядок полей значим, и новое стоит ПОСЛЕДНИМ намеренно. У data class
 * порядок конструктора есть порядок компонентов при разборе на части
 * (`val (t, p, c) = state`): вставка поля в середину молча поменяла бы смысл
 * второго компонента у любого такого разбора, а найти его поиском нельзя —
 * имени поля в разборе нет. Добавление в конец такой подмены не допускает
 * ни при каком разборе. То же правило действует и для следующего, кто будет
 * сюда что-то дописывать.
 */
data class PowerState(
    val temperatureCelsius: Double,
    val percent: Int,
    val charging: Boolean,
    val temperatureKnown: Boolean,
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

    /**
     * Последний ДОСТАВЛЕННЫЙ подпиской тепловой статус, как он пришёл — сырым
     * числом. Нужен только опросу ([probeLine]): сравнивать «что доставили» не
     * с чем иначе, потому что прибор хранит худшее за сеанс, а [zone] отдаёт
     * уже сведённое из трёх оценок.
     *
     * Каналов, таким образом, три, и сливать их нельзя: худшее за срок,
     * итоговое сейчас и последнее доставленное по каждому источнику отвечают
     * на разные вопросы.
     */
    @Volatile private var lastThermalStatus: Int? = null

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
     * Разбор сообщения о батарее. Вынесен из приёмника, потому что читают его
     * ДВА пути: подписка и опрос ([probePowerState]). Две копии разбора со
     * временем разошлись бы молча, и тогда опрос сравнивал бы доставленное не
     * с тем же самым, а со своим прочтением тех же байтов — то есть врал бы
     * ровно в той роли, ради которой заведён.
     *
     * Отсутствие показания здесь НЕ достраивается до правдоподобного числа:
     * пришло сообщение без температуры — так и записываем. Достройка в этом
     * месте необратима, потому что дальше по дороге уже не видно, было ли
     * число прочитано или придумано.
     */
    private fun readPowerState(intent: Intent): PowerState {
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val temperatureKnown = tenths >= 0
        val celsius = if (temperatureKnown) tenths / 10.0 else 0.0

        // Шкала не всегда 100: считаем процент честно, через scale.
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            PowerState.UNKNOWN_PERCENT
        }

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

        return PowerState(
            temperatureCelsius = celsius,
            percent = percent,
            charging = plugged != 0,
            temperatureKnown = temperatureKnown,
        )
    }

    /**
     * Одно сообщение ACTION_BATTERY_CHANGED несёт и температуру, и заряд, и
     * подключение к питанию — поэтому поток один, а не три.
     */
    private fun powerStateFlow(): Flow<PowerState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                trySend(readPowerState(intent))
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        awaitClose { context.unregisterReceiver(receiver) }
    }

    /**
     * Текущее состояние питания. Начальное значение — «ничего ещё не известно»:
     * ни температуры, ни заряда. Система пришлёт настоящее почти сразу после
     * подписки.
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
                temperatureZone = zoneFromBatteryTemp(state),
                chargeZone = zoneFromCharge(state),
                atMs = System.currentTimeMillis(),
            )
        }
        .stateIn(
            scope = externalScope,
            started = SharingStarted.Eagerly,
            initialValue = PowerState(
                temperatureCelsius = 0.0,
                percent = PowerState.UNKNOWN_PERCENT,
                charging = false,
                temperatureKnown = false,
            )
        )

    /** Перевод статуса системы в зону — у [ThermalStatus], там же почему так. */
    private fun zoneFromThermalStatus(status: Int): SafetyZone = ThermalStatus.zone(status)

    /**
     * Оценка по температуре батареи, либо null, если температуры в сообщении
     * не было.
     *
     * Пороги 40/45/50 — не заглушки: это справочные величины для литиевых
     * батарей (до 40 безопасно, 41-44 рабочий потолок, выше 45 деградация).
     * Область, за которой они ничего не значат, названа честно: сверху они не
     * проверены ни разу. За всё время наблюдений на устройстве температура не
     * поднималась выше 39 с небольшим, то есть первый порог не переступался, а
     * второй и третий не наблюдались вовсе. Перепроверяются они не рассуждением,
     * а прогоном под нагрузкой с записью показаний.
     */
    private fun zoneFromBatteryTemp(state: PowerState): SafetyZone? = when {
        !state.temperatureKnown -> null
        state.temperatureCelsius < 40.0 -> SafetyZone.COMFORT
        state.temperatureCelsius < 45.0 -> SafetyZone.WARNING
        state.temperatureCelsius < 50.0 -> SafetyZone.FATIGUE
        else -> SafetyZone.CRITICAL
    }

    /**
     * Оценка по заряду, либо null, если уровень неизвестен.
     *
     * Заряд даёт ровно два исхода, без промежуточных: либо всё равно (COMFORT),
     * либо стоп (CRITICAL). Промежуточных «притормозить» здесь быть не может —
     * см. пояснение к [SafetyZone].
     *
     * Подключённое питание — полноценная оценка, а не пропуск: пока телефон на
     * зарядке, уровень не является поводом останавливаться, каким бы он ни был.
     */
    private fun zoneFromCharge(state: PowerState): SafetyZone? = when {
        state.charging -> SafetyZone.COMFORT
        !state.percentKnown -> null
        state.percent < STOP_RUNNING_BELOW_PERCENT -> SafetyZone.CRITICAL
        else -> SafetyZone.COMFORT
    }

    /**
     * Чем заменяется непрочитанная нога там, где зона обязана быть одной
     * буквой. Единственное место подмены во всём классе — держать её в одном
     * месте важнее краткости записи.
     *
     * Почему COMFORT, а не остановка. Ноги независимы, и пока одна молчит,
     * остальные считаются: системный тепловой статус — отдельный источник,
     * откалиброванный производителем под это железо, и на устройстве он
     * измерен живым. Останавливаться из-за отсутствия данных значит не
     * запускаться в первую секунду после старта приложения, когда ни одного
     * сообщения ещё не пришло.
     *
     * ЧЕГО ЭТА ПОДМЕНА НЕ ПОКРЫВАЕТ, и это важнее довода за неё. Довод выше
     * говорит о коротком промежутке до первого сообщения. Нога может оказаться
     * непрочитанной ПОСТОЯННО — на другом устройстве, где в сообщении о батарее
     * нужного показания нет вовсе. Тогда работа продолжится, как если бы всё
     * было в порядке, и единственным признаком останется строка прибора
     * ([formatZoneObservation]): события у источника идут, а оценок по ноге
     * нет. Кода, который это заметил бы сам, здесь нет намеренно — решение о
     * работе на половине показаний принимает человек, а не предохранитель.
     */
    private fun comfortIfUnread(zone: SafetyZone?): SafetyZone = zone ?: SafetyZone.COMFORT

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
     *
     * Начальное значение COMFORT — подстановка того же рода, что и в
     * [comfortIfUnread], и она СОЗНАТЕЛЬНО ОСТАВЛЕНА. Сделать зону обнуляемой
     * значит переписать [SafetyZoneSource] и всех её потребителей ради
     * поведения, которое и так объявлено: неизвестность работу не
     * останавливает. Область вранья: от подписки до первого события. На
     * устройстве замерено, что событий приходит сразу два — своё стартовое и
     * ответ системы вдогонку, — а любому запуску предшествует загрузка модели
     * около полутора минут. Видно это состояние по прибору: при нуле событий он
     * говорит, что источник молчит, а не что всё спокойно.
     */
    override val zone: StateFlow<SafetyZone> = combine(
        thermalStatusFlow().onEach { status ->
            lastThermalStatus = status
            witness.recordThermalStatus(
                zone = zoneFromThermalStatus(status),
                atMs = System.currentTimeMillis(),
                rawStatus = status,
            )
        },
        power
    ) { thermalStatus, powerState ->
        val thermal = stricterOf(
            zoneFromThermalStatus(thermalStatus),
            comfortIfUnread(zoneFromBatteryTemp(powerState))
        )
        stricterOf(thermal, comfortIfUnread(zoneFromCharge(powerState)))
    }.stateIn(
        scope = externalScope,
        started = SharingStarted.Eagerly,
        initialValue = SafetyZone.COMFORT
    )

    /**
     * Тепловой запас — третье показание производителя рядом со статусом:
     * число, где 1.0 — статус «сильный», выше — тяжелее, а ниже растёт
     * примерно вместе с температурой. Статус ступенчатый и о приближении к
     * ступени молчит; запас показывает, сколько до неё осталось. Нужен прибору
     * для подбора порогов — НА ЗОНУ НЕ ВЛИЯЕТ: какое значение запаса считать
     * поводом что-то менять, пока не измерено.
     *
     * Опрос раз в [HEADROOM_POLL_MS], а не по событию: подписки на запас до
     * Android 15 нет. Чаще раза в секунду спрашивать бесполезно — по описанию
     * Android запас считается по медленным датчикам, а слишком частый вызов
     * отвечает «не число».
     *
     * Чего не умеет: телефон без поддержки отвечает «не число» всегда — это
     * видно на приборе как «не даёт»; наибольшее за сеанс между двумя опросами
     * может быть пропущено.
     */
    @Suppress("unused")
    private val headroomPoll = externalScope.launch {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@launch
        while (isActive) {
            val value = try {
                powerManager.getThermalHeadroom(0)
            } catch (t: Throwable) {
                Float.NaN
            }
            witness.recordHeadroom(value, System.currentTimeMillis())
            delay(HEADROOM_POLL_MS)
        }
    }

    /**
     * Пороги запаса, заданные производителем для каждого статуса, — строкой.
     *
     * Вызов через отражение: метод есть с Android 15, а сборка идёт против
     * Android 14 (compileSdk 34). Поднимать compileSdk ради одной строки
     * прибора — правка всей сборки; отражение здесь ничего не решает, только
     * показывает, и при любой ошибке говорит её словами.
     */
    private fun headroomThresholdsText(): String {
        if (Build.VERSION.SDK_INT < 35) return "только с Android 15"
        return try {
            val raw = PowerManager::class.java.getMethod("getThermalHeadroomThresholds").invoke(powerManager) as Map<*, *>
            val map = raw.entries.mapNotNull { (k, v) -> ((k as? Int) ?: return@mapNotNull null) to ((v as? Float) ?: return@mapNotNull null) }.toMap()
            ThermalStatus.thresholdsLine(map)
        } catch (t: Throwable) {
            "не прочитались — ${(t.cause ?: t).javaClass.simpleName}"
        }
    }

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
        witness.format(zoneObservation(), label) + "\n" + probeLine(label)

    /**
     * ОПРОС — третий канал показаний, независимый от подписки и от прибора.
     *
     * Зачем он есть. Прибор считает то, что до него ДОШЛО, и по этому счёту
     * умеет сказать только «событий не было вовсе». Срок молчания диагнозом не
     * служит: оба источника присылают событие по изменению, поэтому долгая
     * тишина на покоящемся устройстве нормальна (замер записан в
     * [ZoneWitness]). Значит подписку, умершую ПОСРЕДИ сеанса, по потоку
     * событий поймать нельзя вовсе — а это и есть опасный случай: телефон
     * греется, новых сообщений нет, зона стоит на прежнем значении, и
     * предохранитель молчит ровно в тот прогон, ради которого поставлен.
     *
     * Опрос спрашивает источники напрямую в момент отрисовки и печатает рядом
     * доставленное и опрошенное. Расхождение и есть проверка доставки.
     *
     * ПОРОГА РАСХОЖДЕНИЯ ЗДЕСЬ НЕТ НАМЕРЕННО. Какая разница в градусах уже
     * означает поломку, а какая — обычное запаздывание, не измерено ни разу;
     * назначить это число «на глаз» значило бы повторить ошибку, из-за которой
     * срок молчания и приняли за симптом. Печатаются оба показания, сравнивает
     * человек.
     *
     * ЧЕГО ОПРОС НЕ УМЕЕТ: при действительно неизменных показаниях мёртвая
     * подписка и живая выглядят одинаково и здесь — сравнивать нечего, когда
     * ничего не менялось. Он различает их только тогда, когда что-то реально
     * сдвинулось, то есть под нагрузкой. Для покоя такого прибора у нас нет.
     *
     * Ни на зону, ни на прибор опрос НЕ влияет: он ничего не записывает и
     * никуда не подмешивается. Иначе исчезла бы та самая разница между «что
     * доставили» и «что там сейчас», ради которой он и заведён.
     */
    private fun probeLine(label: (SafetyZone) -> String): String {
        val lines = mutableListOf("Опрос источников сейчас:")

        val polledThermal = probeThermalStatus()
        val deliveredThermal = lastThermalStatus
        // Рядом с зоной — статус словом самой системы: «слабый» и «нет» дают
        // одну зону «норма», а различать их нужно, когда подбираются пороги.
        fun thermal(status: Int) = "«${label(zoneFromThermalStatus(status))}» (у системы «${ThermalStatus.word(status)}»)"
        lines += "  Тепловой статус: " + when {
            polledThermal == null -> "опросить нечем на этой версии Android"
            deliveredThermal == null -> "по подписке не приходило, опрос ${thermal(polledThermal)}"
            else -> "по подписке ${thermal(deliveredThermal)}, опрос ${thermal(polledThermal)}"
        }
        lines += "  Пороги запаса у производителя: " + headroomThresholdsText()

        val polledPower = probePowerState()
        lines += "  Батарея: " + if (polledPower == null) {
            "опрос не дал сообщения"
        } else {
            "по подписке ${describePower(power.value)}, опрос ${describePower(polledPower)}"
        }

        return lines.joinToString("\n")
    }

    /** Текущий тепловой статус напрямую, минуя подписку. */
    private fun probeThermalStatus(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                powerManager.currentThermalStatus
            } catch (t: Throwable) {
                null
            }
        } else {
            null
        }

    /**
     * Текущее состояние питания напрямую, минуя подписку.
     *
     * Сообщение о батарее в Android залипающее: система хранит последнее и
     * отдаёт его при регистрации. Регистрация с пустым приёмником как раз и
     * означает «отдай последнее, подписывать меня не надо». Что оно приходит
     * при регистрации, видно на замерах — первое событие батареи появляется в
     * первые же секунды, когда меняться ещё нечему.
     *
     * Показания разбираются той же [readPowerState], что и в подписке.
     */
    private fun probePowerState(): PowerState? = try {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { readPowerState(it) }
    } catch (t: Throwable) {
        // Прибор наблюдения не имеет права уронить экран, на котором он живёт.
        null
    }

    /** Показания питания одной строкой. Неизвестное печатается «?», а не нулём. */
    private fun describePower(state: PowerState): String {
        val temp = if (state.temperatureKnown) {
            String.format(Locale.US, "%.1f", state.temperatureCelsius) + " °C"
        } else {
            "? °C"
        }
        val charge = if (state.percentKnown) "${state.percent}%" else "?"
        return "$temp / $charge"
    }

    /**
     * Можно ли НАЧИНАТЬ длинную работу (TOTE-цикл): порог здесь выше, чем порог
     * остановки, и это намеренно. Бессмысленно браться за получасовую задачу с
     * запасом, которого заведомо не хватит до конца, — при этом уже начатую
     * работу останавливаем позже, чтобы не бросать её у самого финиша.
     *
     * Свойство само себя не применяет: его должен спросить тот, кто запускает
     * цикл. Жара сюда включена тоже — начинать в CRITICAL нельзя ни по какой
     * причине.
     *
     * Неизвестный заряд запуск РАЗРЕШАЕТ, по тому же доводу, что и в
     * [comfortIfUnread], и с той же непокрытой частью: на устройстве, где
     * уровень не читается никогда, этот барьер не сработает ни разу и молчание
     * его будет неотличимо от разрешения. Смотреть в таком случае надо на
     * строку прибора, а не на поведение кнопки.
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

    /**
     * Отмечает начало непрерывной генерации. Вызывается перед обращением к
     * модели.
     *
     * Повторный вызов до сброса момент НЕ сдвигает: потолок меряет от начала
     * обращения, а не от последнего события внутри него.
     */
    fun markInferenceStarted() {
        if (continuousInferenceStartMs == 0L) {
            continuousInferenceStartMs = System.currentTimeMillis()
        }
    }

    /**
     * Сбрасывает таймер. Вызывается по выходу из генерации — любому, и
     * обычному, и прерванному.
     */
    fun resetInferenceTimer() {
        continuousInferenceStartMs = 0L
    }

    /**
     * Жёсткий потолок на ОДНО непрерывное обращение к модели: пять минут,
     * дальше вызывающий обязан его прекратить. От зоны не зависит и
     * когнитивным состоянием не отменяется — это предохранитель, а не совет.
     *
     * ПАУЗЫ ПОСЛЕ СРАБАТЫВАНИЯ НЕТ, и это решение, а не недоделка. Есть
     * остановка и сброс таймера; следующее обращение начинается с чистыми
     * пятью минутами. Пауза строилась бы против нагрева, а нагрев здесь
     * выходит на полку сам: под нагрузкой устройство греется примерно на
     * 0.5 °C в минуту, а остывает около 2 °C в минуту, то есть вчетверо
     * быстрее. Область у этих чисел узкая — один телефон, замеры одного дня,
     * потолок наблюдался около 39 °C при первом пороге зоны 40. На другом
     * железе вывод надо получать заново, а не переносить; перепроверяется он
     * прогоном под нагрузкой с записью температуры до и после.
     *
     * ЧЕГО ЭТОТ ПОТОЛОК НЕ УМЕЕТ:
     *
     * 1. Он ограничивает одно обращение, а не работу целиком. Таймер заводится
     *    в начале каждого обращения и сбрасывается по его выходу, поэтому цикл
     *    из десяти обращений получает по пять минут на каждое. Ограничения на
     *    весь прогон в этом классе нет вовсе, и искать его здесь не надо.
     * 2. Он не оставляет следа. Класс не хранит ни счётчика срабатываний, ни
     *    момента последнего, поэтому на вопрос «случалось ли это вообще»
     *    ответить нечем — ни с экрана, ни из кода.
     */
    fun shouldForceCooldown(): Boolean {
        if (continuousInferenceStartMs == 0L) return false
        val elapsedMs = System.currentTimeMillis() - continuousInferenceStartMs
        return elapsedMs >= HARD_TIMEOUT_MS
    }

    companion object {
        private const val HARD_TIMEOUT_MS = 5 * 60 * 1000L // 5 минут

        /** Как часто спрашивать тепловой запас. Объявленное число, не подобранное. */
        const val HEADROOM_POLL_MS = 15_000L

        /** Ниже этого заряда без зарядки не НАЧИНАТЬ длинную работу. */
        const val START_RUN_MIN_PERCENT = 30

        /** Ниже этого заряда без зарядки ОСТАНОВИТЬ уже идущую работу. */
        const val STOP_RUNNING_BELOW_PERCENT = 15
    }
}
