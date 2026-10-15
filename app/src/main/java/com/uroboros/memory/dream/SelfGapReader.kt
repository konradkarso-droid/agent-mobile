package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.llm.ConversationJournal
import com.uroboros.llm.JournalArchiveTurn
import com.uroboros.llm.JournalStore
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.nav.Clouds
import com.uroboros.memory.nav.Coordinates
import com.uroboros.memory.nav.LemmaHolder
import com.uroboros.memory.nav.OwnSpeech
import com.uroboros.memory.nav.PersonKey

/**
 * Чтение для пробела о себе ([SelfGap]) и облака агента — одно место на два
 * входа: ход на экране и «пишу первым» в теле агента. Разойдись они, экран и
 * тело считали бы пробел по-разному, а на приборе было бы видно только одно.
 *
 * Ничего не хранит: лента, архив и записи читаются при каждом вызове.
 */
class SelfGapReader(private val context: Context, private val store: JournalStore) {

    /** Ходы для [SelfGap.of]: архив, затем открытая лента. */
    fun turns(archive: List<JournalArchiveTurn>, ribbon: List<ConversationJournal.Turn>): List<SelfGap.Turn> =
        archive.map { SelfGap.Turn(it.question, it.userContent, it.agentContent, it.archiveIndex) } +
            ribbon.map { SelfGap.Turn(it.question, it.userContent, it.agentContent, SelfGap.RIBBON) }

    /**
     * Источники облаков ([Clouds]): записи памяти, кроме скрытых, отвергнутых
     * и тех, что ничего не утверждают; принятые выводы, темы снов и своя речь
     * о себе из архива ленты — об агенте. Что и почему снято — «ЧИСТКА» в
     * описании [Clouds].
     */
    suspend fun cloudSources(archive: List<JournalArchiveTurn>?): Clouds.Gathered {
        val db = MemoryDatabase.getInstance(context)
        val out = ArrayList<Clouds.Source>()
        val (kept, assertsNothing) = db.stickerDao().getAll()
            .filter { !it.reviewPending && it.rejectedAt == null }
            .partition { Clouds.keepsRecord(it.content) }
        kept.mapTo(out) { Clouds.fromRecord(it.content, it.source, it.createdAt) }
        db.conclusionDao().accepted().mapTo(out) { Clouds.ofAgent(it.text, it.nightAt) }
        for (night in db.dreamDao().nightsWithTopics()) {
            DreamTopic.load(night.dreamTopics).mapTo(out) { Clouds.ofAgent(it, night.nightAt) }
        }
        val own = OwnSpeech.said(archive.orEmpty().map { row ->
            OwnSpeech.Turn(
                answer = row.agentContent,
                question = row.question,
                records = store.recordTexts(row),
                at = Coordinates.turnTime(row.at, row.question, row.archivedAt) { null }.at,
            )
        })
        val (aboutSelf, notAboutSelf) = own.partition { Clouds.keepsOwn(it.sentence) }
        aboutSelf.mapNotNullTo(out) { said -> said.at?.let { Clouds.ofAgent(said.sentence, it) } }
        return Clouds.Gathered(out, assertsNothing.size, notAboutSelf.size)
    }

    /** Слова облака агента для строки выбора имени ([SelfGap.line]). */
    suspend fun cloudWords(archive: List<JournalArchiveTurn>?): List<String> =
        Clouds.words(
            Clouds.of(PersonKey.AGENT, cloudSources(archive).sources, System.currentTimeMillis(), LemmaHolder.loaded),
            SelfGap.CLOUD_WORDS, SelfGap.SELF_WORD,
        )
}
