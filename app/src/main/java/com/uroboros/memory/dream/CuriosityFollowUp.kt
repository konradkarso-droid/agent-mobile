package com.uroboros.memory.dream

import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.SentenceKind
import com.uroboros.memory.Sentences
import com.uroboros.memory.desk.Promises
import com.uroboros.memory.nav.ForeignSpeech
import com.uroboros.memory.nav.SemanticFeatures

/** Дополнительные поводы: в разговоре сверить старое обещание, после поиска предложить следующий ход. */
internal object CuriosityFollowUp {
    const val SCREEN = "screen"

    fun mayAsk(reply: String, previousAnswer: String?): Boolean =
        CuriosityAsk.replyRefusal(reply, previousAnswer) == null &&
            ForeignSpeech.ofRecord(reply).all { it.kind == ForeignSpeech.Kind.OWN } &&
            Sentences.split(reply).any { SentenceKind.of(it).kind == SentenceKind.Kind.STATEMENT }

    /**
     * Предмет должен совпасть с репликой и с тем, о чём просили в исходном ходе.
     * Близость слов позволяет предложить сверку. Фактическое исполнение из неё не следует.
     */
    fun promise(
        reply: String, previousAnswer: String?, promises: List<Promises.Promise>,
        now: Long, semantic: SemanticFeatures? = SemanticFeatures.available(), seen: (Promises.Promise) -> Boolean,
    ): Promises.Promise? {
        if (!mayAsk(reply, previousAnswer)) return null
        val topic = RiskTrigger.significantStems(reply)
        if (topic.size < 2) return null
        val replyWords by lazy { semantic?.words(topicWords(reply)).orEmpty() }
        return promises.firstOrNull { promise ->
            val asked = promise.asked ?: return@firstOrNull false
            val age = now - promise.at
            if (age !in MIN_AGE_MS..Promises.TERM_MS || promise.together) return@firstOrNull false
            val shared = RiskTrigger.significantStems(promise.sentence).intersect(RiskTrigger.significantStems(asked))
            if (seen(promise)) return@firstOrNull false
            if (topic.count { it in shared } >= 2) return@firstOrNull true
            if (semantic == null) return@firstOrNull false
            val original = semantic.words(topicWords(asked))
            val promised = semantic.words(topicWords(promise.sentence))
            // Две разные опоры темы. Один расплывчатый синоним недостаточен.
            val usedPromise = mutableSetOf<String>()
            val usedReply = mutableSetOf<String>()
            original.count { anchor ->
                val before = promised.firstOrNull {
                    it.lemma !in usedPromise && semantic.score(anchor, it) >= TOPIC_SIMILARITY
                } ?: return@count false
                val now = replyWords.firstOrNull {
                    it.lemma !in usedReply && semantic.score(anchor, it) >= TOPIC_SIMILARITY
                } ?: return@count false
                usedPromise.add(before.lemma)
                usedReply.add(now.lemma)
                true
            } >= 2
        }
    }

    private fun topicWords(text: String): List<String> = RiskTrigger.significantWords(text).values.flatten()
    private const val TOPIC_SIMILARITY = 0.56

    fun promiseLine(promise: Promises.Promise): String {
        val words = promise.sentence.replace(Regex("""[\r\n\[\]«»]"""), " ").trim()
            .take(200).trimEnd { it.isHighSurrogate() }
        return "По теме реплики на доске есть моё обещание «$words». " +
            "Если уместно, предложи сверить, где мы остановились. Об исполнении пока нет подтверждения."
    }

    /** Только предложение после завершённого поиска с наблюдаемым пробелом в выдаче. */
    fun afterSearch(usableSources: Boolean, hits: Int, sourceSpecified: Boolean): String? =
        if (!usableSources || hits > 0) null
        else if (sourceSpecified) "Можно расширить поиск по этой теме и посмотреть другие источники."
        else "Можно уточнить тему другими словами или указать конкретный источник."

    // Объявленная пауза: позднее возвращение к теме, а не повтор только что данного обещания.
    internal const val MIN_AGE_MS = 60 * 60_000L
}
