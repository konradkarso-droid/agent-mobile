package com.uroboros.llm

import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.Sentences
import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.ForeignSpeech
import com.uroboros.memory.nav.PersonForm
import com.uroboros.util.TextFold

/**
 * Чужое «я» в ответе: агент произносит от себя фразу собеседника о нём самом,
 * дословно взятую из записей этого хода («Я состою в клубе фехтования» —
 * слова владельца, а ответ агента). Чистая логика; ловит лестница ответа
 * (AnswerLadder, перехват).
 *
 * ПОЧЕМУ ПЕРЕХВАТ, А НЕ ПЕРЕВОРОТ КОДОМ. Правило по словам ошибается чаще,
 * чем агент: из найденных по похожести ответов почти все были верными («теперь
 * я знаю: ты Иван»). Переписанный кодом ответ — текст кода, а не агента, и в
 * ленте он лёг бы не тем, что выдала модель. Поэтому ловится только дословная
 * копия, а исправляет агент сам — второй попыткой, где штраф DRY не даёт
 * повторить ту же фразу.
 *
 * ЧТО СЧИТАЕТСЯ ФРАЗОЙ СОБЕСЕДНИКА ([theirWords]) — предложения с
 * местоимением первого лица из строк записей хода, подписанных «Твои слова»
 * (ProvenanceLabels.forModel), то есть сказанного самим собеседником. Подпись
 * «Имя:» перед словами другого человека из телеграма снимается. Строки своей
 * речи агента («Я говорил…») и записи группы («В этом чате…») сюда не идут:
 * там «я» — не собеседник этого хода или их несколько.
 *
 * ЧТО СЧИТАЕТСЯ КОПИЕЙ ([find]) — предложение ответа содержит фразу
 * собеседника целиком (регистр, «ё» и знак в конце не важны), фраза не короче
 * [MIN_WORDS] слов, и перед ней в предложении ответа нет кавычки: «Ты говорил:
 * «Я работаю по субботам»» — честная цитата, а не присвоение.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - пересказ своими словами («я тоже фехтую») не ловит — только дословную
 *    копию;
 *  - цитату без кавычек («Ты говорил, я работаю по субботам») примет за
 *    присвоение;
 *  - путаницу лиц без записи («тебе снилось» о своём сне) не видит вовсе:
 *    сверять не с чем.
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

    private fun key(s: String): String = TextFold.fold(s).trim().trimEnd('.', '!', '?', '…', ' ')

    private fun words(s: String): List<String> = TextFold.fold(s).split(NOT_LETTER).filter { it.isNotEmpty() }
}
