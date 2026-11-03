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
 * Чтение и предложение паспорта ([Passport]): своя речь агента из архива и
 * ленты, слова владельца из памяти, строки паспорта в базе. Правило — в
 * [Passport]; здесь только чтение и вставка предложенного в очередь.
 *
 * Ничего не хранит: читает при каждом вызове.
 */
class PassportReader(private val context: Context, private val mediator: TrustedMediator) {

    /**
     * Предложить недостающие строки паспорта и вернуть строку прибора.
     * [archive] null — архив ленты не прочитался: имени не ищется.
     */
    suspend fun proposeAndMeter(archive: List<JournalArchiveTurn>?, ribbon: List<ConversationJournal.Turn>): String =
        // Два показа подряд (раскрытие шторки и конец хода) иначе предложили бы одну строку дважды.
        lock.withLock { proposeLocked(archive, ribbon) }

    private suspend fun proposeLocked(archive: List<JournalArchiveTurn>?, ribbon: List<ConversationJournal.Turn>): String {
        val dao = MemoryDatabase.getInstance(context).stickerDao()
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
        for (text in proposed) mediator.proposePassportLine(text)
        val after = existing + proposed.map { Passport.Existing(it, pending = true, rejected = false) }
        return Passport.meterLine(namings, owner, nickname, after) +
            (if (archive == null) " · архив ленты не прочитался — имя искалось только в открытой ленте" else "")
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
