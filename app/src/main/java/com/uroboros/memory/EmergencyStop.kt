package com.uroboros.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Причина, по которой взведён аварийный стоп.
 *
 * Намеренно один sealed-тип, а не два независимых поля: «остановил гейт» и
 * «остановил человек» — разные по сути события, и они не должны иметь шанса
 * оказаться заполненными одновременно (одно из них тогда было бы протухшим,
 * и непонятно, какое). Одна ссылка = одна причина = одна истина.
 */
sealed class StopCause {
    /** Стоп взведён автоматически: гейт вынес запрещающий вердикт. */
    data class ByGate(val verdict: ActionVerdict) : StopCause()

    /** Стоп нажат человеком. Вердикта тут нет и быть не может. */
    data class ByUser(val note: String) : StopCause()
}

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

    /**
     * Причина ПЕРВОГО срабатывания с момента последнего clear() — для UI/лога,
     * не для логики самого флага.
     *
     * Первая причина побеждает: если стоп уже взведён, повторный вызов
     * trigger()/triggerManual() поднимает флаг (он и так поднят), но причину
     * НЕ перезаписывает. Иначе диагностика затиралась бы — а весь смысл
     * несбрасываемого флага в том, чтобы человек увидел, почему всё встало.
     * compareAndSet(null, ...) даёт это без блокировок: первый писатель
     * выигрывает гарантированно, остальные проходят мимо.
     */
    private val cause = AtomicReference<StopCause?>(null)

    /** Взвод из гейта: есть вердикт. */
    fun trigger(verdict: ActionVerdict) {
        cause.compareAndSet(null, StopCause.ByGate(verdict))
        _active.value = true
    }

    /**
     * Взвод человеком: вердикта нет.
     *
     * Отдельный вход, а не подделанный ActionVerdict: фальшивый вердикт врал бы
     * логу о том, что действие проходило через оценку гейта, хотя оно через неё
     * не проходило.
     */
    fun triggerManual(note: String = "Остановлено пользователем") {
        cause.compareAndSet(null, StopCause.ByUser(note))
        _active.value = true
    }

    fun isActive(): Boolean = _active.value

    /** Причина срабатывания в общем виде (гейт или человек). */
    fun lastCause(): StopCause? = cause.get()

    /**
     * Совместимость с прежним API и уже залитым тестом: возвращает вердикт
     * ТОЛЬКО если стоп взвёл гейт. Ручной стоп даёт null — вердикта у него нет,
     * и выдумывать его здесь нельзя.
     */
    fun lastTriggerVerdict(): ActionVerdict? = (cause.get() as? StopCause.ByGate)?.verdict

    /** Вызывать ТОЛЬКО после явного подтверждения пользователем (см. Шаг 3/4). */
    fun clear() {
        _active.value = false
        cause.set(null)
    }
}
