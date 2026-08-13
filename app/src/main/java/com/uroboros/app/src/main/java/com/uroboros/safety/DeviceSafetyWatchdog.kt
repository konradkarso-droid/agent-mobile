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
import kotlinx.coroutines.flow.stateIn

/**
 * Физический предохранитель (Bible principle #1: физическая безопасность
 * выше когнитивного состояния). Зона считается вне зависимости от того,
 * что сейчас думает модель — ни одна функция здесь не принимает энергию
 * или confidence как параметр.
 */
enum class SafetyZone {
    COMFORT,   // < 40°C — полная мощность
    WARNING,   // 40-45°C — снизить число потоков
    FATIGUE,   // 45-49°C — троттлинг токенов (delay между генерацией)
    CRITICAL   // > 50°C — полный стоп, заморозка контекста
}

class DeviceSafetyWatchdog(
    private val context: Context,
    externalScope: CoroutineScope
) {
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

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

    private fun batteryTempFlow(): Flow<Double> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
                if (tenths >= 0) trySend(tenths / 10.0)
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        awaitClose { context.unregisterReceiver(receiver) }
    }

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

    private fun stricterOf(a: SafetyZone, b: SafetyZone): SafetyZone =
        if (a.ordinal >= b.ordinal) a else b

    val zone: StateFlow<SafetyZone> = combine(
        thermalStatusFlow(),
        batteryTempFlow()
    ) { thermalStatus, batteryTemp ->
        stricterOf(zoneFromThermalStatus(thermalStatus), zoneFromBatteryTemp(batteryTemp))
    }.stateIn(
        scope = externalScope,
        started = SharingStarted.Eagerly,
        initialValue = SafetyZone.COMFORT
    )

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
    }
}
