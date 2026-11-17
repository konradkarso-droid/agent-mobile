package com.uroboros.llm

import com.uroboros.safety.DeviceSafetyWatchdog

/**
 * Хвост строки барьера про потолок непрерывной работы: что видел потолок за
 * прогон и сколько прогон ждал движка.
 *
 * ЗАЧЕМ. Потолок (DeviceSafetyWatchdog.shouldForceCooldown) в обычной жизни
 * не срабатывает, и его молчание само по себе не отличить от поломки. Число,
 * которое он видел, отличает: прогон дошёл до движка, а работы модели потолок
 * не увидел ни разу — значит, слеп.
 */
object CeilingLine {

    /**
     * [maxHeldMs] — наибольшее время работы модели на прогон, увиденное
     * проверками; [waitMs] — сколько прогон ждал движка, -1 — неизвестно;
     * [reachedEngine] — прогон дошёл до движка (через барьер прошли события).
     */
    fun of(maxHeldMs: Long, waitMs: Long, reachedEngine: Boolean): String {
        val ceilingSec = DeviceSafetyWatchdog.HARD_TIMEOUT_MS / 1000
        val held = when {
            maxHeldMs > 0 -> " Потолок: модель работала на прогон до ${sec(maxHeldMs)} с из $ceilingSec."
            reachedEngine -> " Потолок: работы модели не видел ни разу — потолок слеп."
            else -> " Потолок: до работы модели прогон не дошёл."
        }
        val wait = if (waitMs >= 0) " До движка ждал ${sec(waitMs)} с." else ""
        return held + wait
    }

    private fun sec(ms: Long) = String.format("%.1f", ms / 1000.0)
}
