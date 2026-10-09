package com.uroboros.initiative

/**
 * Несколько поводов заговорить первым: берётся первый, кому есть что сказать,
 * в порядке списка. Молчат все — молчание называет причины каждого, иначе на
 * приборе было бы видно только последнего.
 */
class FirstOfSources(private val sources: List<InitiativeSource>) : InitiativeSource {

    override suspend fun offer(): InitiativeSource.Offer {
        val reasons = ArrayList<String>(sources.size)
        for (s in sources) {
            when (val o = s.offer()) {
                is InitiativeSource.Offer.Say -> return o
                is InitiativeSource.Offer.Silent -> reasons += o.reason
            }
        }
        return InitiativeSource.Offer.Silent(reasons.joinToString("; "))
    }
}
