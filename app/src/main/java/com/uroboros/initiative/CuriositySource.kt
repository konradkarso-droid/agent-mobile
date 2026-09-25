package com.uroboros.initiative

import android.content.Context
import com.uroboros.memory.dream.CuriosityAsk
import com.uroboros.memory.dream.CuriosityAskMarker
import com.uroboros.memory.dream.CuriosityGauge
import com.uroboros.memory.dream.DreamPickup
import com.uroboros.memory.dream.DreamView

/**
 * Источник «спросить о сне»: тот же выход пружины любопытства, что в реплике
 * владельца (CuriosityAsk), только без реплики — владелец молчит.
 *
 * Решение и строка — CuriosityAsk, без своих порогов. Сон модели не
 * называется: строка перечисляет записи лидера (см. CuriosityAsk.line). Своя
 * у неё только последняя фраза: просит спросить прямо, без «если к месту»
 * (почему — у CuriosityAsk.lineFirst). Остальные отличия от пути в реплике — в том, что
 * вокруг хода:
 *  - сон отмечается спрошенным, когда строка ушла в движок (та же мерка);
 *  - ход с вопросом идёт в подхват с ПУСТЫМ вопросом: исключать из слов сна
 *    нечего, и следующая реплика владельца о сне засчитается ответом
 *    (см. DreamPickup, «СПРОШЕННЫЙ СОН»);
 *  - реплики владельца нет, поэтому ожидание ответа ею не снимается.
 */
class CuriositySource(context: Context) : InitiativeSource {

    private val gauge = CuriosityGauge(context)
    private val marker = CuriosityAskMarker(context)

    override suspend fun offer(): InitiativeSource.Offer {
        val decision = CuriosityAsk.decide(gauge.read(), marker.awaiting())
        val leader = when (decision) {
            is CuriosityAsk.Decision.Refuse -> return InitiativeSource.Offer.Silent("любопытство — ${decision.reason}")
            is CuriosityAsk.Decision.Ask -> decision.leader
        }
        return InitiativeSource.Offer.Say(
            line = InitiativeDecision.SILENCE_WORDS + " " + CuriosityAsk.lineFirst(leader),
            what = "сон «${DreamView.brief(leader.brief.kind, leader.brief.texts)}»",
            onSent = { at -> marker.markAsked(leader, at) },
            onAppended = { DreamPickup.afterTurn("", listOf(leader.dream)) },
        )
    }
}
