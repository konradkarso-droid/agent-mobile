package com.uroboros.memory.desk

import android.content.Context
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.dream.ConclusionView
import com.uroboros.memory.dream.CuriosityAsk
import com.uroboros.memory.dream.CuriosityAskMarker
import com.uroboros.memory.dream.CuriosityGauge
import com.uroboros.memory.dream.DreamView

/**
 * Доска для экрана: собирает входы [Desk] из базы и отдаёт строку прибора и
 * раздел. Только чтение; при каждом вызове — заново, поэтому переживает
 * перезапуск и не расходится с тем, из чего собрана.
 *
 * Вопрос о сне решается тем же CuriosityAsk.decide, что у выхода «спросить»:
 * своих порогов у доски нет.
 */
class DeskView(private val context: Context) {

    private val db get() = MemoryDatabase.getInstance(context)
    private val conclusionView by lazy { ConclusionView(context) }
    private val gauge by lazy { CuriosityGauge(context) }
    private val marker by lazy { CuriosityAskMarker(context) }

    suspend fun project(now: Long = System.currentTimeMillis()): Desk.Projection {
        val last = db.dreamDao().lastNight()
        val conclusions = last?.let { night ->
            val rows = db.conclusionDao().lastNights(1).filter { it.nightAt == night.nightAt }
            val accepted = conclusionView.items(rows.filter { it.accepted })
            Desk.NightConclusions(
                tried = rows.size,
                accepted = accepted.filter { !it.silent }.map { it.row.text },
                acceptedSilent = accepted.count { it.silent },
            )
        }
        val question = when (val d = CuriosityAsk.decide(gauge.read(now), marker.awaiting())) {
            is CuriosityAsk.Decision.Refuse -> Desk.Question.Refuse(d.reason)
            is CuriosityAsk.Decision.Ask ->
                Desk.Question.Ask("сон «${DreamView.brief(d.leader.brief.kind, d.leader.brief.texts)}»")
        }
        return Desk.project(
            Desk.Inputs(
                night = last?.let { Desk.Night(it.nightAt, it.dreamTopics) },
                conclusions = conclusions,
                question = question,
                ageForModel = { ProvenanceLabels.ageForModel(it, now) },
                moment = { DreamView.moment(it) },
            ),
        )
    }

    suspend fun meter(): String = Desk.meter(project())

    suspend fun section(): String = Desk.section(project())
}
