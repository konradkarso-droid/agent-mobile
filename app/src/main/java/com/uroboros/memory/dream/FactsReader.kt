package com.uroboros.memory.dream

import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.FactAging
import com.uroboros.memory.nav.PersonFacts

/**
 * Что ночь сказала о строках портретов — для портрета (nav.Portrait): отметки
 * «факт» ([FactMarkRow]) и устаревшие строки ([FactPairRow]). Одно чтение на
 * экран, телеграм и ночной шаг: прочитанные врозь, они разошлись бы при
 * первой правке правила.
 */
object FactsReader {

    /** [marks] — строка → факт ли она; [outdated] — устаревшие строки (FactAging.outdated). */
    data class Read(val marks: Map<PersonFacts.Key, Boolean>, val outdated: Set<PersonFacts.Key>)

    /** [records] — все записи памяти, уже прочитанные вызывающим. */
    suspend fun read(db: MemoryDatabase, records: List<Sticker>): Read {
        val marks = db.factMarkDao().all().associate {
            PersonFacts.Key(it.recordId, it.sentence) to (it.verdict == PersonFacts.Verdict.FACT.name)
        }
        val live = records.filter { !it.reviewPending && it.rejectedAt == null }.mapTo(HashSet()) { it.id }
        val outdates = db.factPairDao().all().filter { it.verdict == FactAging.Verdict.OUTDATES.name }.map {
            FactAging.Pair(PersonFacts.Key(it.oldRecordId, it.oldSentence), PersonFacts.Key(it.newRecordId, it.newSentence))
        }
        return Read(marks, FactAging.outdated(outdates, live))
    }
}
