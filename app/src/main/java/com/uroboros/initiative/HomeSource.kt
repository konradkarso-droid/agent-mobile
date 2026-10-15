package com.uroboros.initiative

import android.content.Context
import com.uroboros.safety.HomeEventStore
import com.uroboros.safety.HomeEvents
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Источник «расскажу о доме»: что агент пережил на своём железе без владельца
 * (safety.HomeEvents, «РАССКАЗ»), пока владелец молчит. Правило и строка —
 * HomeEvents.tell, без своих порогов; первым по порядку источников —
 * безопасность устройства выше всего (ARCHITECTURE.md §0).
 *
 * События отмечаются рассказанными, когда строка ушла в движок.
 */
class HomeSource(context: Context) : InitiativeSource {

    private val store = HomeEventStore(context)

    override suspend fun offer(): InitiativeSource.Offer {
        val tell = HomeEvents.tell(store.read(), store.toldUpTo(), ::moment)
            ?: return InitiativeSource.Offer.Silent("дом — несказанного нет")
        return InitiativeSource.Offer.Say(
            line = InitiativeDecision.SILENCE_WORDS + " " + tell.line,
            what = "дом: ${tell.what}",
            onSent = { at -> store.markTold(at) },
            onAppended = {},
        )
    }

    private fun moment(millis: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(millis))
}
