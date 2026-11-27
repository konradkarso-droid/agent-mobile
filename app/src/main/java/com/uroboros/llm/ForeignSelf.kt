package com.uroboros.llm

import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.SentenceKind
import com.uroboros.memory.Sentences
import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.ForeignSpeech
import com.uroboros.memory.nav.PersonForm
import com.uroboros.memory.nav.SemanticFeatures
import com.uroboros.util.TextFold

/**
 * Перехват подмены говорящего. Дословная копия проверяется по записям с
 * известным автором. Для перефраза сравниваются значимые слова, их словоформы
 * и векторы. Лицо определяется отдельно: смысловая близость сама его не задаёт.
 * Исправление выдаёт модель на следующей ступени AnswerLadder.
 */
object ForeignSelf {

    /** Короче — не фраза, а оборот («Я тут.»). Объявленное число, не подобранное. */
    const val MIN_WORDS = 3

    private val NOT_LETTER = Regex("[^\\p{L}]+")

    /** Фразы собеседника о себе из записей хода [recall] (описание объекта). */
    fun theirWords(recall: String?): List<String> {
        val head = ProvenanceLabels.forModel(SourceKind.USER_STATED.name)
        return recall?.lines().orEmpty()
            .filter { it.startsWith(head) }
            .flatMap { Sentences.split(EchoIntercept.quoteOf(it)) }
            .map { s -> ForeignSpeech.of(s).takeIf { it.kind == ForeignSpeech.Kind.DIRECT }?.voices?.firstOrNull()?.text ?: s }
            .map { it.trim() }
            .filter { s -> words(s).let { w -> w.size >= MIN_WORDS && w.any { it in PersonForm.FIRST_PRONOUNS } } }
            .distinct()
    }

    /** Предложение ответа [answer], присвоившее фразу из [theirs]; null — такого нет. */
    fun find(answer: String, theirs: List<String>): String? {
        if (theirs.isEmpty()) return null
        val keys = theirs.map { key(it) }.filter { it.isNotEmpty() }
        for (sentence in Sentences.split(answer)) {
            val folded = TextFold.fold(sentence)
            for (k in keys) {
                val at = folded.indexOf(k)
                if (at < 0) continue
                val before = folded.substring(0, at)
                if (before.any { it == '«' || it == '"' || it == '„' || it == '“' }) continue
                return sentence.trim()
            }
        }
        return null
    }

    /** Реплика здесь принадлежит текущему собеседнику, а не всему окну чата. */
    internal fun checker(
        speakerText: String,
        theirRecords: List<String>,
        semantic: SemanticFeatures? = SemanticFeatures.available(),
    ): (String) -> String? {
        val source = Sentences.split(speakerText).takeLast(8) + theirRecords.take(12)
        val passages by lazy { source.mapNotNull { passage(it, semantic, fromSpeaker = true) } }
        return { answer ->
            find(answer, theirRecords) ?: Sentences.split(answer).firstOrNull { sentence ->
                val candidate = passage(sentence, semantic, fromSpeaker = false) ?: return@firstOrNull false
                val matched = passages.filter { it.matches(candidate, semantic) }
                // Первое лицо собеседника должно стать вторым лицом в ответе и наоборот.
                matched.isNotEmpty() && matched.all { it.person == candidate.person }
            }
        }
    }

    private data class Passage(
        val person: PersonForm.Person,
        val negative: Boolean,
        val numbers: List<String>,
        val stems: Set<String>,
        val terms: List<SemanticFeatures.Word>,
    ) {
        fun matches(other: Passage, semantic: SemanticFeatures?): Boolean {
            if (negative != other.negative || numbers != other.numbers) return false
            if (stems == other.stems) return true
            if (semantic == null || terms.none { term -> other.terms.any { it.lemma == term.lemma } }) return false
            val available = other.terms.toMutableList()
            var aligned = 0
            var nounAligned = false
            for (term in terms) {
                val index = available.indexOfFirst { it.lemma == term.lemma }
                if (index >= 0) {
                    val same = available.removeAt(index)
                    nounAligned = nounAligned || (term.noun && same.noun)
                    aligned++
                }
            }
            for (term in terms.filterNot { term -> other.terms.any { it.lemma == term.lemma } }) {
                val best = available.maxByOrNull { semantic.score(term, it) } ?: break
                if (semantic.score(term, best) < MIN_SIMILARITY) continue
                available.remove(best)
                nounAligned = nounAligned || (term.noun && best.noun)
                aligned++
            }
            if (!nounAligned && terms.any { it.noun } && other.terms.any { it.noun }) return false
            return covers(aligned, terms.size, other.terms.size)
        }
    }

    private fun passage(sentence: String, semantic: SemanticFeatures?, fromSpeaker: Boolean): Passage? {
        val kind = SentenceKind.of(sentence).kind
        if (kind != SentenceKind.Kind.STATEMENT && kind != SentenceKind.Kind.QUESTION) return null
        if (sentence.any { it in QUOTE_MARKS } || ForeignSpeech.of(sentence).kind != ForeignSpeech.Kind.OWN) return null
        val tokens = words(sentence)
        if (tokens.any { it in SHARED_OR_CONTRAST }) return null
        val person = PersonForm.ofSentence(sentence).singleOrNull()?.takeIf {
            it == PersonForm.Person.FIRST || it == PersonForm.Person.SECOND
        } ?: return null
        // Вопрос о собеседнике не утверждает ничего о нём. Вопрос, явно
        // обращённый к агенту, задаёт адрес ответа, даже если факт неизвестен.
        if (fromSpeaker && kind == SentenceKind.Kind.QUESTION && person != PersonForm.Person.SECOND) return null
        val personal = PersonForm.FIRST_PRONOUNS + PersonForm.SECOND_PRONOUNS
        val terms = RiskTrigger.significantWords(sentence).values.flatten().filterNot { TextFold.fold(it) in personal }
        val stems = terms.flatMap { RiskTrigger.significantStems(it) }.toSet()
        if (stems.size < 2) return null
        return Passage(person, tokens.any { it in NEGATIONS }, NUMBERS.findAll(sentence).map { it.value }.toList(),
            stems, semantic?.words(terms).orEmpty())
    }

    private fun covers(matched: Int, left: Int, right: Int): Boolean =
        matched >= 2 && matched * 3 >= maxOf(left, right) * 2

    private const val MIN_SIMILARITY = 0.62
    private val QUOTE_MARKS = setOf('«', '»', '"', '“', '”', '„', '`')
    private val SHARED_OR_CONTRAST = setOf("тоже", "также", "оба", "обе", "вместе", "вдвоем", "но", "зато", "хотя")
    private val NEGATIONS = setOf("не", "ни", "нет", "никогда", "без")
    private val NUMBERS = Regex("""\d+(?:[.,]\d+)?""")

    private fun key(s: String): String = TextFold.fold(s).trim().trimEnd('.', '!', '?', '…', ' ')

    private fun words(s: String): List<String> = TextFold.fold(s).split(NOT_LETTER).filter { it.isNotEmpty() }
}
