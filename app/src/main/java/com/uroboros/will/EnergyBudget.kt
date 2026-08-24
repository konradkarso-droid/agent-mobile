package com.uroboros.will

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Энергия/уверенность агента в текущей задаче (0..100).
 * Влияет ТОЛЬКО на стратегию (сколько думать перед действием, когда сдаться и попросить о помощи).
 * НИКОГДА не отменяет и не ослабляет жёсткие лимиты DeviceSafetyWatchdog (item 8) —
 * это два независимых контура, они не должны смешиваться в одну оценку.
 *
 * Калибровка 2026-08-23 (после первого живого прогона на Mi 10T).
 * Найденная проблема: плоский SEVERE=30 выставлялся уже при ПЕРВОМ совпадении подписи,
 * поэтому три повтора подряд сжигали 90 из 100 и цикл уходил по энергии раньше,
 * чем consecutiveSimilar успевал дорасти до stuckThreshold=5. Порог застревания был
 * недостижим ни при каких входных данных — ось 3 (item 8a) молчала не из-за подписей,
 * а из-за арифметики двух порогов, выставленных независимо друг от друга.
 *
 * Новая шкала строится вокруг одного вопроса: продвинулись или топчемся.
 *  - продвижение (usefulProgress=true, но компиляция ещё не прошла) — БЕСПЛАТНО;
 *  - топтание (usefulProgress=false) — MEDIUM, ровно 10 шагов до эвакуации при старте 100;
 *  - повтор — нарастающий штраф, см. applyRepeatDamage.
 */

enum class WillZone {
    STORM,       // 100-50%: полная скорость, действовать сразу
    REFLECTION,  // 49-20%: сначала проанализировать, потом уже писать код
    EVACUATION   // <20%: прекратить попытки, объяснить блокер словами, ждать
}

enum class ErrorSeverity(val damage: Int) {
    LIGHT(0),     // шаг не довёл до компиляции, но продвинул дело — движение вперёд не штрафуем
    MEDIUM(10),   // бесполезный шаг: ни компиляции, ни продвижения
    SEVERE(10)    // БАЗОВЫЙ шаг штрафа за повтор; фактический урон считает applyRepeatDamage
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
     * Урон за повторяющуюся ошибку, нарастающий по числу повторов подряд.
     * Первый повтор стоит как обычная бесполезная итерация, каждый следующий — дороже.
     *
     * Смысл именно в нарастании: мягкий сигнал усиливает давление постепенно и
     * подводит к ЖЁСТКОМУ барьеру (stuckThreshold в ToteEngine), а не подменяет его
     * собой. Проверка на 100 единицах при stuckThreshold=3: 10 + 10 + 20 + 30 = 70,
     * на третьем повторе остаётся 30% — то есть барьер застревания срабатывает
     * ЗАВЕДОМО раньше, чем кончится энергия. Это и есть то, чего не хватало.
     *
     * @param consecutiveSimilar сколько повторов подряд уже насчитано (1 — первый).
     */
    fun applyRepeatDamage(consecutiveSimilar: Int) {
        val steps = consecutiveSimilar.coerceAtLeast(1)
        val damage = ErrorSeverity.SEVERE.damage * steps
        _energy.value = (_energy.value - damage).coerceAtLeast(0)
    }

    /**
     * Небольшое восстановление энергии при успешном шаге (например, ошибка исчезла).
     * Не поднимается выше исходного стартового значения.
     *
     * ВНИМАНИЕ (2026-08-23): сейчас НЕ вызывается нигде. Внутри TOTE-цикла успешный
     * и полезный шаг сразу возвращает ToteResult.Success и цикл заканчивается, так что
     * награждать внутри цикла попросту некого. Событие "не довёл до компиляции, но
     * продвинул" теперь обрабатывается через LIGHT=0 (не штрафуем), а не через награду.
     * Метод оставлен намеренно — восстановление будет уместно в item 6b (консолидация).
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
