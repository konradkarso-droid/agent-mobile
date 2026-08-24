package com.uroboros.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Проверка того, что аварийный стоп реально останавливает действия (правка 2026-08-24).
 *
 * Зачем этот тест вообще написан. До этой правки EmergencyStop был мёртвым кодом:
 * поиск по репозиторию давал ровно два совпадения — сам файл и комментарий-обещание
 * в GatedAction ("проверка встанет первой строкой... когда появятся Tool'ы").
 * Ни одной проверки, ни одного вызова trigger(). То есть механизм ВЫГЛЯДЕЛ
 * существующим, но не останавливал ничего. Такое опаснее отсутствующей функции:
 * читающий код (в том числе мы сами через месяц) решит, что защита есть.
 *
 * EmergencyStop — глобальный синглтон, а JUnit гоняет все тесты в одной JVM,
 * поэтому флаг обязательно гасится до и после каждого теста. Иначе один тест
 * протечёт в другой и уронит его без всякой связи с кодом.
 */
class ActionGateEmergencyStopTest {

    /** Обычное безобидное действие, которое в спокойном состоянии проходит. */
    private val harmlessRequest = ActionRequest(
        type = ActionType.WRITE_MEMORY,
        requestedBy = "тест",
        provenance = ActionProvenance.USER,
        crossesDeviceBoundary = false,
        isReversible = true
    )

    @Before
    fun resetBefore() = EmergencyStop.clear()

    @After
    fun resetAfter() = EmergencyStop.clear()

    @Test
    fun `harmless action passes while the stop is down`() {
        assertEquals(GateResult.ALLOW, ActionGate.evaluate(harmlessRequest).result)
    }

    @Test
    fun `the same action is blocked once the stop is raised`() {
        EmergencyStop.trigger(someRealVerdict())

        val verdict = ActionGate.evaluate(harmlessRequest)

        assertEquals(GateResult.DENY, verdict.result)
        assertTrue(
            "причина отказа должна называть стоп словами: ${verdict.reason}",
            verdict.reason.contains("АВАРИЙНЫЙ СТОП")
        )
    }

    /**
     * Стоп сильнее любых других правил: он должен гасить даже то, что и без него
     * было бы отклонено, и причина должна указывать на стоп, а не на allow-list —
     * иначе человек, читающий журнал, не поймёт настоящую причину остановки.
     */
    @Test
    fun `the stop outranks the allow-list check`() {
        EmergencyStop.trigger(someRealVerdict())

        val verdict = ActionGate.evaluate(
            harmlessRequest.copy(type = ActionType.FILE_DELETE)
        )

        assertEquals(GateResult.DENY, verdict.result)
        assertTrue(verdict.reason.contains("АВАРИЙНЫЙ СТОП"))
    }

    @Test
    fun `clearing the stop lets actions through again`() {
        EmergencyStop.trigger(someRealVerdict())
        assertEquals(GateResult.DENY, ActionGate.evaluate(harmlessRequest).result)

        EmergencyStop.clear()

        assertEquals(GateResult.ALLOW, ActionGate.evaluate(harmlessRequest).result)
    }

    /** Стоп сам не гаснет — только явным clear(), это в нём сделано намеренно. */
    @Test
    fun `the stop does not reset itself between checks`() {
        EmergencyStop.trigger(someRealVerdict())

        repeat(3) { ActionGate.evaluate(harmlessRequest) }

        assertTrue(EmergencyStop.isActive())
    }

    /**
     * Отказ по риску объясняется человеческим языком, а не голым числом.
     * FILE_DELETE отсутствует в allow-list, поэтому берём набор сигналов,
     * который перевешивает порог сам по себе: чужая граница + необратимость +
     * не-пользовательское происхождение = 3 + 3 + 2 = 8 при пороге 7.
     */
    @Test
    fun `risk denial explains itself in plain words`() {
        val verdict = ActionGate.evaluate(
            ActionRequest(
                type = ActionType.EXTERNAL_PROCESS,
                requestedBy = "тестовый раннер",
                provenance = ActionProvenance.MODEL_OUTPUT,
                crossesDeviceBoundary = true,
                isReversible = false
            )
        )

        assertEquals(GateResult.DENY, verdict.result)
        assertTrue(
            "причина должна быть читаемой: ${verdict.reason}",
            verdict.reason.contains("Отказано") && verdict.reason.contains("тестовый раннер")
        )
    }

    /**
     * Настоящий вердикт для trigger() — не собранный руками, а полученный от гейта,
     * как это и произойдёт в бою (FILE_DELETE не в allow-list, гарантированный DENY).
     */
    private fun someRealVerdict(): ActionVerdict =
        ActionGate.evaluate(harmlessRequest.copy(type = ActionType.FILE_DELETE))
}
