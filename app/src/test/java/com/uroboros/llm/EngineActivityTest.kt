package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Занятость модели.
 *
 * ГЛАВНОЕ ЗДЕСЬ: две генерации разом не дают «свободна», пока не кончится
 * вторая, — иначе агент уснул бы посреди разговора, пока судья заканчивал пару.
 */
class EngineActivityTest {

    private var now = 0L
    private val activity = EngineActivity { now }

    @Test
    fun `до первой генерации — не занята и конца не было`() {
        assertFalse(activity.busy)
        assertNull(activity.lastEndedAtMs)
    }

    @Test
    fun `занята, пока идёт, и помнит конец`() {
        activity.begin()
        assertTrue(activity.busy)
        now = 500L
        activity.end()
        assertFalse(activity.busy)
        assertEquals(500L, activity.lastEndedAtMs)
    }

    @Test
    fun `две генерации разом — свободна только после обеих`() {
        activity.begin()
        activity.begin()
        now = 100L
        activity.end()
        assertTrue("одна ещё идёт", activity.busy)
        now = 200L
        activity.end()
        assertFalse(activity.busy)
        assertEquals(200L, activity.lastEndedAtMs)
    }
}
