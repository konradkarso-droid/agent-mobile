package com.uroboros.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Проверка механики согласия на задачу и безусловного потолка (шаг b, 2026-08-24).
 *
 * Почему юнит-тест, а не живой прогон: границы области действия разрешения и
 * потолок нельзя увидеть на устройстве — чтобы наткнуться на них вживую, нужно
 * действие, которого пока не существует. Тот же вывод, что и с stuckThreshold:
 * стохастический прогон по 16 минут ничего не доказывает, детерминированный
 * тест доказывает.
 *
 * ЧТО ЭТОТ ТЕСТ НЕ ПРОВЕРЯЕТ, и это важно не спутать: он проверяет МЕХАНИКУ
 * потолка, а не его ЗНАЧЕНИЕ. Что 10.0 — правильная граница, зелёный тест не
 * говорит. Значение остаётся черновым и калибруется, когда появятся реальные
 * действия таких весов.
 *
 * Про искусственный перевес. Нынешняя шкала при одном объекте выдаёт максимум
 * 8.0 (граница процесса 3.0 + необратимость 3.0 + не-пользовательское
 * происхождение 2.0). Перевалить за потолок сегодня в бою НЕЧЕМ — признака
 * "действие выходит за пределы устройства" в шкале пока нет. Поэтому перевес
 * здесь набран честными параметрами через охват: affectedObjectCount = 6 даёт
 * +2.5, итого 10.5. Блины на штангу, потому что целой штанги такого веса не
 * существует. Когда шкала пополнится, этот тест стоит переписать на реальное
 * тяжёлое действие.
 */
class ActionGateAuthorizationTest {

    private val now = 1_000_000L
    private val taskId = "task-alpha"
    private val runner = "TermuxKotlinRunner"

    /**
     * EmergencyStop — глобальный синглтон, а JUnit гоняет весь набор в одной JVM.
     * Взведённый здесь стоп утёк бы в соседние тесты и завалил бы их с причиной,
     * которую потом ищи. Чистим до и после — до, потому что мусор мог прийти
     * извне; после, потому что мусор не должен уйти наружу.
     */
    @Before
    fun setUp() = EmergencyStop.clear()

    @After
    fun tearDown() = EmergencyStop.clear()

    /** Запуск сгенерированного кода как он есть: необратимо, чужой процесс, вывод модели. */
    private fun runnerRequest(
        taskSessionId: String? = taskId,
        affectedObjectCount: Int = 1
    ) = ActionRequest(
        type = ActionType.EXTERNAL_PROCESS,
        requestedBy = runner,
        provenance = ActionProvenance.MODEL_OUTPUT,
        crossesDeviceBoundary = true,
        isReversible = false,
        affectedObjectCount = affectedObjectCount,
        taskSessionId = taskSessionId
    )

    private fun authorization(
        type: ActionType = ActionType.EXTERNAL_PROCESS,
        requestedBy: String = runner,
        taskSessionId: String = taskId,
        grantedAt: Long = now,
        lifetimeMs: Long = TaskAuthorization.DEFAULT_LIFETIME_MS
    ) = TaskAuthorization.grantedByHuman(
        type = type,
        requestedBy = requestedBy,
        taskSessionId = taskSessionId,
        grantedBy = "пользователь",
        now = grantedAt,
        lifetimeMs = lifetimeMs
    )

    @Test
    fun `запуск кода без разрешения отклоняется`() {
        val verdict = ActionGate.evaluate(runnerRequest(), authorization = null, now = now)

        assertEquals(GateResult.DENY, verdict.result)
        assertEquals(8.0, verdict.riskWeight, 0.0001)
        // Текст отказа обязан называть, чего не хватает: человек читает его на
        // экране и должен понять, что делать.
        assertTrue(verdict.reason.contains("подтверждение"))
    }

    @Test
    fun `подходящее разрешение открывает запуск кода`() {
        val verdict = ActionGate.evaluate(runnerRequest(), authorization(), now = now)

        assertEquals(GateResult.ALLOW, verdict.result)
    }

    @Test
    fun `разрешение не уменьшает вес риска`() {
        val denied = ActionGate.evaluate(runnerRequest(), authorization = null, now = now)
        val allowed = ActionGate.evaluate(runnerRequest(), authorization(), now = now)

        // Главное свойство всей конструкции: согласие — отдельная ось, а не скидка.
        // Если этот тест однажды упадёт, значит согласие превратилось в валюту.
        assertEquals(8.0, allowed.riskWeight, 0.0001)
        assertEquals(denied.riskWeight, allowed.riskWeight, 0.0001)
        assertEquals(
            denied.signalBreakdown["crossesDeviceBoundary"],
            allowed.signalBreakdown["crossesDeviceBoundary"]
        )
    }

    @Test
    fun `разрешение от другой задачи не подходит`() {
        val verdict = ActionGate.evaluate(
            runnerRequest(),
            authorization(taskSessionId = "task-beta"),
            now = now
        )

        assertEquals(GateResult.DENY, verdict.result)
    }

    @Test
    fun `разрешение для другого компонента не подходит`() {
        val verdict = ActionGate.evaluate(
            runnerRequest(),
            authorization(requestedBy = "TermuxKotlinCompiler"),
            now = now
        )

        assertEquals(GateResult.DENY, verdict.result)
    }

    @Test
    fun `разрешение на другой тип действия не подходит`() {
        val verdict = ActionGate.evaluate(
            runnerRequest(),
            authorization(type = ActionType.FILE_WRITE),
            now = now
        )

        assertEquals(GateResult.DENY, verdict.result)
    }

    @Test
    fun `истёкшее разрешение не подходит`() {
        val expired = authorization(grantedAt = now, lifetimeMs = 1000L)
        val verdict = ActionGate.evaluate(runnerRequest(), expired, now = now + 2000L)

        assertEquals(GateResult.DENY, verdict.result)
    }

    @Test
    fun `запрос вне задачи не покрывается никаким разрешением`() {
        // taskSessionId = null означает "действие не принадлежит задаче".
        // Разрешение выдаётся задаче, значит покрыть такой запрос оно не может.
        val verdict = ActionGate.evaluate(
            runnerRequest(taskSessionId = null),
            authorization(),
            now = now
        )

        assertEquals(GateResult.DENY, verdict.result)
    }

    @Test
    fun `выше потолка не спасает даже подходящее разрешение`() {
        val heavy = runnerRequest(affectedObjectCount = 6) // 8.0 + 2.5 = 10.5
        val verdict = ActionGate.evaluate(heavy, authorization(), now = now)

        assertEquals(GateResult.DENY, verdict.result)
        assertEquals(10.5, verdict.riskWeight, 0.0001)
        // Отказ по потолку обязан читаться иначе, чем отказ из-за отсутствия
        // подтверждения: иначе человек будет искать кнопку, которой нет.
        assertTrue(verdict.reason.contains("безусловно"))
    }

    @Test
    fun `лёгкое действие проходит и без всякого разрешения`() {
        // Обратимое локальное действие: 2.0 за происхождение, порог не задет.
        // Проверка на то, что новая логика не сломала обычный путь.
        val light = ActionRequest(
            type = ActionType.WRITE_MEMORY,
            requestedBy = "KotlinCodingTask",
            provenance = ActionProvenance.MODEL_OUTPUT,
            crossesDeviceBoundary = false,
            isReversible = true
        )
        val verdict = ActionGate.evaluate(light, authorization = null, now = now)

        assertEquals(GateResult.ALLOW, verdict.result)
        assertEquals(2.0, verdict.riskWeight, 0.0001)
    }

    @Test
    fun `запрещённый тип не открывается разрешением`() {
        // FILE_DELETE не в allow-list. Список — потолок над потолком: проверка
        // типа стоит выше арифметики, поэтому согласие сюда не достаёт вообще.
        val delete = ActionRequest(
            type = ActionType.FILE_DELETE,
            requestedBy = runner,
            provenance = ActionProvenance.MODEL_OUTPUT,
            crossesDeviceBoundary = false,
            isReversible = true,
            taskSessionId = taskId
        )
        val verdict = ActionGate.evaluate(
            delete,
            authorization(type = ActionType.FILE_DELETE),
            now = now
        )

        assertEquals(GateResult.DENY, verdict.result)
    }

    @Test
    fun `аварийный стоп перебивает любое разрешение`() {
        // Порядок проверок в гейте: стоп -> allow-list -> веса -> порог ->
        // потолок -> разрешение. Этот тест закрепляет самое верхнее звено:
        // пока стоп взведён, подходящее разрешение не значит ничего.
        EmergencyStop.triggerManual("проверка порядка проверок")

        val verdict = ActionGate.evaluate(runnerRequest(), authorization(), now = now)

        assertEquals(GateResult.DENY, verdict.result)
        assertTrue(verdict.reason.contains("АВАРИЙНЫЙ СТОП"))
    }

    @Test
    fun `после снятия стопа разрешение снова действует`() {
        // Стоп не аннулирует разрешение навсегда — он перекрывает путь, пока
        // взведён. Это важно проверить отдельно: иначе можно было бы решить,
        // что после clear() нужно выдавать согласие заново.
        EmergencyStop.triggerManual("временная остановка")
        EmergencyStop.clear()

        val verdict = ActionGate.evaluate(runnerRequest(), authorization(), now = now)

        assertEquals(GateResult.ALLOW, verdict.result)
    }

    @Test
    fun `разрешение знает свои границы само по себе`() {
        val auth = authorization()

        assertTrue(auth.covers(ActionType.EXTERNAL_PROCESS, runner, taskId, now))
        assertTrue(!auth.covers(ActionType.EXTERNAL_PROCESS, runner, "task-beta", now))
        assertTrue(auth.isExpired(now + TaskAuthorization.DEFAULT_LIFETIME_MS))
        assertNotNull(auth.id)
    }
}
