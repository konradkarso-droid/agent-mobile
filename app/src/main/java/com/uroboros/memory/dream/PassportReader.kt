package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.llm.ConversationJournal
import com.uroboros.llm.JournalArchiveTurn
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.Sticker
import com.uroboros.memory.TrustedMediator
import com.uroboros.memory.nav.Coordinates
import com.uroboros.memory.nav.ForeignSpeech
import com.uroboros.memory.nav.NameClaim
import com.uroboros.memory.nav.PersonForm
import com.uroboros.memory.nav.PersonKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Чтение и укладка паспорта ([Passport]): своя речь агента из архива и
 * ленты, слова владельца из памяти, строки паспорта в базе. Правило — в
 * [Passport]; здесь только чтение и вставка.
 *
 * Ничего не хранит: читает при каждом вызове.
 */
class PassportReader(private val context: Context, private val mediator: TrustedMediator) {

    /** Строка прибора и сколько строк встало в стену на этом вызове (тогда стену надо перечитать). */
    data class Outcome(val line: String, val changed: Int)

    /**
     * Положить недостающие строки паспорта и вернуть строку прибора.
     * [archive] null — архив ленты не прочитался: имени не ищется.
     */
    suspend fun placeAndMeter(archive: List<JournalArchiveTurn>?, ribbon: List<ConversationJournal.Turn>): Outcome =
        // Два показа подряд (раскрытие шторки и конец хода) иначе положили бы одну строку дважды.
        lock.withLock { placeLocked(archive, ribbon) }

    private suspend fun placeLocked(archive: List<JournalArchiveTurn>?, ribbon: List<ConversationJournal.Turn>): Outcome {
        val dao = MemoryDatabase.getInstance(context).stickerDao()
        // Строки, ждавшие решения человека с прежнего порядка, — принять: иначе
        // они держали бы свой вид паспорта занятым и в стену не вставали.
        val woke = mediator.acceptWaitingPassport()
        val said = archive.orEmpty().map { Passport.Said(it.agentContent, it.question) } +
            ribbon.map { Passport.Said(it.agentContent, it.question) }
        val namings = Passport.namings(said)
        val records = dao.getAll()
        val owner = NameClaim.of(records).found?.name
        val nickname = Passport.nickname(ownerSentences(records))
        val existing = dao.passportAll().map {
            Passport.Existing(it.content, pending = it.reviewPending, rejected = it.rejectedAt != null)
        }
        val proposed = Passport.proposals(Passport.chosenName(namings), owner, nickname, existing)
        val placed = proposed.map { text -> mediator.placePassportLine(text) to text }
        val after = existing + proposed.map { Passport.Existing(it, pending = false, rejected = false) }
        val line = Passport.meterLine(namings, owner, nickname, after) + Passport.placedNote(placed, woke) +
            (if (archive == null) " · архив ленты не прочитался — имя искалось только в открытой ленте" else "")
        return Outcome(line, placed.size + woke)
    }

    private companion object {
        /** Один на процесс: экран пересоздаётся, а база одна. */
        val lock = Mutex()
    }

    /** Свои предложения владельца в его записях — как их видит nav.NameClaim. */
    private fun ownerSentences(records: List<Sticker>): List<String> =
        records.filter {
            Coordinates.speakerOf(it.source) == PersonKey.OWNER && !it.reviewPending && it.rejectedAt == null
        }.sortedBy { it.createdAt }.flatMap { r ->
            val mine = ForeignSpeech.ownSentences(r.content)
            PersonForm.of(r.content).sentences.withIndex()
                .filter { (i, _) -> mine.getOrElse(i) { true } }
                .map { it.value.sentence }
        }
}
