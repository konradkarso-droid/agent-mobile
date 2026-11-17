package com.uroboros.safety

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Потолок непрерывной работы (DeviceSafetyWatchdog.shouldForceCooldown):
 * решает по времени работы модели на нынешнее обращение, которое меряет сам
 * движок. Своего счётчика, который кто-то заводит и сбрасывает, у потолка нет.
 */
class WorkCeilingTest {

    private val ceiling = DeviceSafetyWatchdog.HARD_TIMEOUT_MS

    @Test
    fun `обрывает на потолке и после`() {
        assertTrue(DeviceSafetyWatchdog.shouldForceCooldown(ceiling))
        assertTrue(DeviceSafetyWatchdog.shouldForceCooldown(ceiling + 60_000))
    }

    @Test
    fun `молчит - модель не работает`() {
        // Движок свободен — ноль; так же молчит, пока прогон ждёт движка в
        // очереди за чужим: его ожидание движок работой не считает.
        assertFalse(DeviceSafetyWatchdog.shouldForceCooldown(0))
    }

    @Test
    fun `молчит - работа короче потолка`() {
        assertFalse(DeviceSafetyWatchdog.shouldForceCooldown(ceiling - 1))
        assertFalse(DeviceSafetyWatchdog.shouldForceCooldown(60_000))
    }

    @Test
    fun `решение зависит только от времени работы - чужой конец его не сбрасывает`() {
        // Прежний счётчик у зовущих обнулялся концом соседнего прогона, и
        // идущий оставался без потолка. Здесь одно и то же время работы даёт
        // один и тот же ответ, сколько бы прогонов ни кончилось рядом.
        repeat(3) { assertTrue(DeviceSafetyWatchdog.shouldForceCooldown(ceiling + 1)) }
    }
}
