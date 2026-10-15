package com.uroboros.initiative

import android.content.Context
import com.uroboros.memory.dream.CuriosityAsk
import com.uroboros.memory.dream.CuriosityAskMarker
import com.uroboros.memory.dream.CuriosityGauge
import com.uroboros.memory.dream.DreamPickup

/**
 * Источник «расскажу, что надумал»: несказанный вывод сна, который накопился
 * (dream.CuriosityPressure, «НЕСКАЗАННОЕ»), агент рассказывает сам, пока
 * владелец молчит. Решение и строка — CuriosityAsk (decideTellFirst,
 * tellLineFirst), без своих порогов.
 *
 * Вокруг хода — как у [CuriositySource], потому что рассказ и вопрос о сне
 * делят одну метку (Dream.askedAt):
 *  - вывод отмечается рассказанным, когда строка ушла в движок;
 *  - ход идёт в подхват с ПУСТЫМ вопросом: ответ владельца о сне засчитается
 *    ответом (см. DreamPickup);
 *  - реплики владельца нет, поэтому ожидание ответа ею не снимается.
 *
 * ЧЕГО НЕ УМЕЕТ: «в теме сна» без реплики не бывает, поэтому первым
 * рассказывается только накопившееся; вывод, к которому подошёл бы разговор,
 * ждёт реплики владельца.
 */
class TellSource(context: Context) : InitiativeSource {

    private val gauge = CuriosityGauge(context)
    private val marker = CuriosityAskMarker(context)

    override suspend fun offer(): InitiativeSource.Offer {
        val teller = when (val d = CuriosityAsk.decideTellFirst(gauge.read(), marker.awaiting())) {
            is CuriosityAsk.TellDecision.Refuse -> return InitiativeSource.Offer.Silent("рассказ — ${d.reason}")
            is CuriosityAsk.TellDecision.Tell -> d.teller
        }
        return InitiativeSource.Offer.Say(
            line = InitiativeDecision.SILENCE_WORDS + " " + CuriosityAsk.tellLineFirst(teller),
            what = "вывод сна «${teller.text}»",
            onSent = { at -> marker.markAsked(teller.leader, at) },
            onAppended = { DreamPickup.afterTurn("", listOf(teller.leader.dream)) },
        )
    }
}
