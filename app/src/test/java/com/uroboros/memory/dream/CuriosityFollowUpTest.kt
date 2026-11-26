package com.uroboros.memory.dream

import com.uroboros.memory.desk.Promises
import com.uroboros.memory.nav.SemanticFeatures
import com.uroboros.memory.nav.TestVerbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CuriosityFollowUpTest {
    private val now = 10 * Promises.TERM_MS
    private val promise = Promises.Promise(
        "Проверю колодку рубанка.", "Проверь колодку рубанка.", now - CuriosityFollowUp.MIN_AGE_MS, false,
    )
    private val reply = "Колодка рубанка опять люфтит."

    @Test
    fun aRelatedStatementCanReturnToOneEarlierPromise() {
        assertEquals(promise, CuriosityFollowUp.promise(reply, null, listOf(promise), now) { false })
        assertTrue(CuriosityFollowUp.promiseLine(promise).contains("пока нет подтверждения"))
        assertNull(CuriosityFollowUp.promise(reply, null, listOf(promise), now) { true })
    }

    @Test
    fun skipsQuestionsForeignSpeechExpiredAndUnsupportedPromises() {
        val unsuitable = listOf(
            promise.copy(at = now),
            promise.copy(at = now + CuriosityFollowUp.MIN_AGE_MS),
            promise.copy(at = now - Promises.TERM_MS - 1),
            promise.copy(asked = null),
            promise.copy(asked = "Покажи шлифовальный станок."),
            promise.copy(together = true),
        )
        assertNull(CuriosityFollowUp.promise(reply, null, unsuitable, now) { false })
        for (text in listOf("Колодка рубанка люфтит?", "Клод: $reply", "Нож рубанка заточен.")) {
            assertNull(text, CuriosityFollowUp.promise(text, null, listOf(promise), now) { false })
        }
        assertNull(CuriosityFollowUp.promise(reply, "Что случилось?", listOf(promise), now) { false })
    }

    @Test
    fun recognizesTwoSemanticAnchorsInAnOtherwiseDifferentFormulation() {
        val semantics = SemanticFeatures(TestVerbs.vectors, TestVerbs.lemmas)
        val original = promise.copy(
            sentence = "Узнаю про стоматолога и лечение.",
            asked = "Проверь стоматолога и лечение.",
        )
        val rephrased = "Дантист предложил терапию."
        assertNull(CuriosityFollowUp.promise(rephrased, null, listOf(original), now, semantic = null) { false })
        assertEquals(original, CuriosityFollowUp.promise(rephrased, null, listOf(original), now, semantics) { false })
        assertNull(CuriosityFollowUp.promise("Дантист едет в автобусе.", null, listOf(original), now, semantics) { false })
    }

    @Test
    fun usableButEmptySourcesCanPromptAnotherSearch() {
        assertNull(CuriosityFollowUp.afterSearch(false, 0, true))
        assertNull(CuriosityFollowUp.afterSearch(true, 1, true))
        assertTrue(CuriosityFollowUp.afterSearch(true, 0, true).orEmpty().contains("расширить поиск"))
        assertTrue(CuriosityFollowUp.afterSearch(true, 0, false).orEmpty().contains("уточнить тему"))
    }
}
