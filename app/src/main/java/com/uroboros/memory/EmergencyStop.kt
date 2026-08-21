package com.uroboros.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Аварийный стоп. Флаг не сбрасывается сам — только явным вызовом clear()
 * после того, как пользователь увидел причину последнего срабатывания
 * (см. EmergencyStopEvent-лог, будет отдельным шагом).
 *
 * Не путать с TOTE-coroutine job.cancel() — это отдельный, второй слой
 * (см. design note в бэклоге item 9). Этот объект — то, что видит ActionGate.
 */
object EmergencyStop {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> get() = _active

    // Последний вердикт, который вызвал стоп — для UI/лога, не для логики самого флага.
    private val lastTrigger = AtomicReference<ActionVerdict?>(null)

    fun trigger(verdict: ActionVerdict) {
        lastTrigger.set(verdict)
        _active.value = true
    }

    fun isActive(): Boolean = _active.value

    fun lastTriggerVerdict(): ActionVerdict? = lastTrigger.get()

    /** Вызывать ТОЛЬКО после явного подтверждения пользователем (см. Шаг 3/4). */
    fun clear() {
        _active.value = false
        lastTrigger.set(null)
    }
}
