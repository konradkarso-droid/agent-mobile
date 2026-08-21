package com.uroboros.memory

import android.content.Context
import android.util.Log

/**
 * Единственная точка прохода для действий, требующих разрешения (item 1 + дыра №3).
 * Оборачивает ActionGate.evaluate() и ОБЯЗАТЕЛЬНО записывает ActionEvidence —
 * и на ALLOW, и на DENY. До этого таблица action_evidence существовала, но никогда
 * не заполнялась: аудит-след был пуст, а значит периодическому пересмотру (item 6)
 * нечего было бы пересматривать — контрмера против "нарезки салями" не работала.
 *
 * Сам ActionGate остаётся чистым Kotlin без Android-зависимостей (юнит-тестируемым);
 * вся работа с Context/Room живёт здесь.
 *
 * Fail-closed при сбое записи следа: если действие невозможно зафиксировать,
 * оно не выполняется. Состояние "действие произошло, но записи о нём нет" —
 * ровно то, ради исключения которого этот след и заводится.
 *
 * Это заготовка будущего ToolRegistry.invoke() (см. ARCHITECTURE.md, §3.2):
 * когда появятся настоящие Tool'ы, проверка EmergencyStop встанет первой строкой
 * здесь же, а не в каждом инструменте отдельно.
 */
object GatedAction {

    suspend fun evaluate(context: Context, request: ActionRequest): ActionVerdict {
        val verdict = ActionGate.evaluate(request)

        return try {
            MemoryDatabase.getInstance(context)
                .actionEvidenceDao()
                .insert(verdict.toEvidence(request))
            verdict
        } catch (e: Exception) {
            Log.e("GatedAction", "не удалось записать ActionEvidence для ${request.type}", e)
            ActionVerdict(
                result = GateResult.DENY,
                riskWeight = verdict.riskWeight,
                signalBreakdown = verdict.signalBreakdown,
                reason = "действие отклонено: не удалось записать evidence-след " +
                    "(${e.javaClass.simpleName}). Исходный вердикт был ${verdict.result}"
            )
        }
    }
}
