package com.uroboros.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImperativeFormTest {

    @Test
    fun `ловит повелительные из списка`() {
        for (w in listOf("попробуй", "назови", "расскажи", "перечисли", "остановите")) {
            assertTrue(w, ImperativeForm.looksImperative(w))
        }
    }

    @Test
    fun `короткий корень не ловит`() {
        assertFalse(ImperativeForm.looksImperative("ели"))
    }

    @Test
    fun `повелительное на мягкий знак не ловит — чего не умеет`() {
        assertFalse(ImperativeForm.looksImperative("проверь"))
    }
}
