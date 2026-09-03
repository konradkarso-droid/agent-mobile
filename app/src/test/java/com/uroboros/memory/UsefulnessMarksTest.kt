package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Проверки на молчание для правила «кому засчитывается польза».
 *
 * Что здесь закрепляется: считается только обращение ради ответа пользователю, и
 * только тем записям, что нашлись по словам вопроса. Обе половины правила легко
 * ослабить случайной правкой — достаточно добавить цель в перечисление или
 * посчитать всю выдачу вместо совпавших, — и ни то, ни другое не уронит сборку и
 * не будет видно на экране: выдача не изменится, изменится только число в колонке.
 *
 * Чего эти тесты НЕ проверяют: доходит ли отметка до базы и правда ли matched
 * собран по словам вопроса. Это проводка, а не правило. Юнит-тестом её здесь не
 * покрыть — путь выборки пишет в android.util.Log, а подмены Android-вызовов в
 * сборке нет намеренно: она превратила бы любой случайный Android-вызов в тихий
 * ноль, а в этом проекте отличать «сломалось» от «промолчало» дороже удобства.
 * Проводка проверена на устройстве двусторонней приёмкой, см. KDoc usefulnessMarks.
 */
class UsefulnessMarksTest {

    // Выдача из трёх записей: 1 — принцип, приехавший по слою; 2 и 3 нашлись по словам.
    private val returned = listOf(1L, 2L, 3L)
    private val matched = setOf(2L, 3L)

    @Test
    fun `при ответе пользователю отметку получают только совпавшие по словам`() {
        assertEquals(
            setOf(2L, 3L),
            usefulnessMarks(RetrievalPurpose.ANSWERING_USER, returned, matched)
        )
    }

    @Test
    fun `при просмотре не получает никто`() {
        assertEquals(
            emptySet<Long>(),
            usefulnessMarks(RetrievalPurpose.BROWSING, returned, matched)
        )
    }

    @Test
    fun `при обращении агента не получает никто`() {
        assertEquals(
            emptySet<Long>(),
            usefulnessMarks(RetrievalPurpose.AGENT_INTERNAL, returned, matched)
        )
    }

    @Test
    fun `запись, попавшая и в принципы, и в совпадения, отметку получает`() {
        assertEquals(
            setOf(1L),
            usefulnessMarks(RetrievalPurpose.ANSWERING_USER, listOf(1L, 2L), setOf(1L))
        )
    }

    @Test
    fun `совпавшая, но не попавшая в выдачу, отметки не получает`() {
        // Отсечка limit отбрасывает часть совпавших. Платить за то, что до ответа
        // не доехало, нельзя: польза меряется участием в ответе, а не совпадением.
        assertEquals(
            setOf(2L),
            usefulnessMarks(RetrievalPurpose.ANSWERING_USER, listOf(2L), setOf(2L, 3L))
        )
    }

    /**
     * Главная проверка на молчание. Список целей, за которые платят, должен состоять
     * ровно из одной — обращения ради ответа. Добавят в перечисление новое значение
     * и решат его засчитывать — этот тест упадёт и заставит решение назвать вслух,
     * а не протащить мимо разбора.
     */
    @Test
    fun `платит ровно одна цель обращения`() {
        val paying = RetrievalPurpose.values().filter { purpose ->
            usefulnessMarks(purpose, listOf(7L), setOf(7L)).isNotEmpty()
        }
        assertEquals(listOf(RetrievalPurpose.ANSWERING_USER), paying)
    }
}
