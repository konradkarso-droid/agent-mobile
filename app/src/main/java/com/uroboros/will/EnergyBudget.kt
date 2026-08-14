package com.uroboros.will

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Энергия/уверенность агента в текущей задаче (0..100).
 * Влияет ТОЛЬКО на стратегию (сколько думать перед действием, когда сдаться и попросить о помощи).
 * НИКОГДА не отменяет и не ослабляет жёсткие лимиты DeviceSafetyWatchdog (item 8) —
 * это два независимых контура, они не должны смешиваться в одну оценку.
 */

enum class WillZone {
    STORM,       // 100-50%: полная скорость, действовать сразу
    REFLECTION,  // 49-20%: сначала проанализировать, потом уже писать код
    EVACUATION   // <20%: прекратить попытки, объяснить блокер словами, ждать
}

enum class ErrorSeverity(val damage: Int) {
    LIGHT(2),     // мелкая синтаксическая опечатка
    MEDIUM(10),   // несовпадение типов и подобные содержательные ошибки
    SEVERE(30)    // ошибка повторяется (см. RiskTrigger.isRepeatedError) — застряли в цикле
}

class EnergyBudget(
    private val startEnergy: Int = 100
) {
    private val _energy = MutableStateFlow(startEnergy)
    val energy: StateFlow<Int> = _energy.asStateFlow()

    val zone: WillZone
        get() = when {
            _energy.value >= 50 -> WillZone.STORM
            _energy.value >= 20 -> WillZone.REFLECTION
            else -> WillZone.EVACUATION
        }

    /**
     * Уменьшает энергию на величину, соответствующую тяжести ошибки.
     * Не опускается ниже 0.
     */
    fun applyDamage(severity: ErrorSeverity) {
        _energy.value = (_energy.value - severity.damage).coerceAtLeast(0)
    }

    /**
     * Небольшое восстановление энергии при успешном шаге (например, ошибка исчезла).
     * Не поднимается выше исходного стартового значения.
     */
    fun applySuccess(amount: Int = 5) {
        _energy.value = (_energy.value + amount).coerceAtMost(startEnergy)
    }

    /**
     * Полный сброс — используется, например, при старте новой задачи
     * или после "ночной дефрагментации" (item 8, консолидация).
     */
    fun reset() {
        _energy.value = startEnergy
    }
}
