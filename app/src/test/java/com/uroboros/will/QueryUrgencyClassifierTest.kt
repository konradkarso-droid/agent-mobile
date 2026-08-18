package com.uroboros.will

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryUrgencyClassifierTest {

    @Test
    fun `override word forces HeavyUrgent regardless of content`() {
        val decision = QueryUrgencyClassifier.classify(
            query = "стоп, подожди секунду про котиков",
            currentError = null,
            taskDescription = "починить функцию сортировки"
        )
        assertEquals(QueryDecision.HeavyUrgent, decision)
    }

    @Test
    fun `bare question unrelated to task is Light`() {
        val decision = QueryUrgencyClassifier.classify(
            query = "какая сегодня погода?",
            currentError = "unresolved reference: tota",
            taskDescription = "починить функцию sumPositive"
        )
        assertEquals(QueryDecision.Light, decision)
    }

    @Test
    fun `short imperative about the task is HeavyUrgent`() {
        val decision = QueryUrgencyClassifier.classify(
            query = "останови",
            currentError = "unresolved reference: tota",
            taskDescription = "починить функцию sumPositive"
        )
        assertEquals(QueryDecision.HeavyUrgent, decision)
    }

    @Test
    fun `long imperative about the task is HeavyDeferred`() {
        val decision = QueryUrgencyClassifier.classify(
            query = "объясните пожалуйста подробно почему компилятор ругается именно на эту строку кода",
            currentError = "unresolved reference: tota",
            taskDescription = "починить функцию sumPositive"
        )
        assertEquals(QueryDecision.HeavyDeferred, decision)
    }

    @Test
    fun `ambiguous grammar with high overlap falls through to Jaccard heavy`() {
        val decision = QueryUrgencyClassifier.classify(
            query = "функция sumPositive сломана",
            currentError = "unresolved reference: tota",
            taskDescription = "починить функцию sumPositive"
        )
        assertEquals(QueryDecision.HeavyUrgent, decision)
    }

    @Test
    fun `ambiguous grammar with low overlap falls through to Jaccard light`() {
        val decision = QueryUrgencyClassifier.classify(
            query = "давно не было дождя",
            currentError = "unresolved reference: tota",
            taskDescription = "починить функцию sumPositive"
        )
        assertEquals(QueryDecision.Light, decision)
    }

    @Test
    fun `blank reference is fail-closed heavy`() {
        val decision = QueryUrgencyClassifier.classify(
            query = "функция sumPositive сломана",
            currentError = null,
            taskDescription = ""
        )
        assertEquals(QueryDecision.HeavyUrgent, decision)
    }

    @Test
    fun `blank query is Light`() {
        val decision = QueryUrgencyClassifier.classify(
            query = "   ",
            currentError = "unresolved reference: tota",
            taskDescription = "починить функцию sumPositive"
        )
        assertEquals(QueryDecision.Light, decision)
    }
}
