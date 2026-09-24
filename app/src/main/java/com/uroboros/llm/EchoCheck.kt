package com.uroboros.llm

import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sentences

/**
 * Эхо в ответе агента: предложение, повторяющее уже сказанное, вместо ответа.
 * Чистая логика — без Android и без состояния, чтобы закрепляться тестами.
 *
 * ДВА ВИДА, У КАЖДОГО СВОЙ ИСТОЧНИК, И СЧЁТ У НИХ РАЗДЕЛЬНЫЙ:
 *  - повтор себя — предложение ответа повторяет предложение одного из прошлых
 *    ответов агента в ленте;
 *  - зеркало — предложение ответа собрано из слов реплики владельца этого же
 *    хода («А сейчас не вспомнишь?» → «Нет, сейчас не вспомнишь»).
 * Слитые в один счёт, они не дали бы увидеть, какой из них пошёл на убыль.
 *
 * ПО ПРЕДЛОЖЕНИЯМ, А НЕ ПО ОТВЕТУ ЦЕЛИКОМ. Эхо — одна фраза внутри ответа, и
 * мера схожести целых ответов на живых примерах выходила низкой — она бы
 * промолчала. Предложения режет [Sentences], слова меряет
 * [RiskTrigger.echoStems]: разбиение и основа слова у всего проекта одни.
 *
 * ЗЕРКАЛО СВЕРЯЕТСЯ С `question`, А НЕ С `userContent`. В реплике целиком лежат
 * процитированные записи памяти, и они раздули бы совпадение: пересказ записи
 * — законный ответ, а не эхо. Ход, начатый агентом (пустой `question`),
 * зеркала не имеет — отражать было нечего.
 *
 * СЧЁТ ИДЁТ ПО ЛЕНТЕ, из её ходов: так он переживает перезапуск вместе с
 * лентой и не хранит своего состояния.
 *
 * ЧЕГО НЕ УМЕЕТ. Сравнение по основам слов, а не по смыслу: перефраз без общих
 * слов не ловится; законный пересказ с общими словами («ты спросил про
 * рубанок — …») ловится ложно. Поэтому действие мягкое — пометка на следующем
 * ходе (см. [ConversationJournal.composeUserContent]), а не пересоздание ответа.
 */
object EchoCheck {

    // Три числа ниже — объявленные, не подобранные. Сравнение по основам, не по
    // смыслу; перепроверять по строке «Эхо:».

    /**
     * Сколько основ должно быть в предложении ответа, чтобы оно учитывалось.
     * Из одной основы («Помню.») повтор неотличим от короткого ответа по делу.
     */
    const val MIN_STEMS = 2

    /** Какая доля основ предложения должна найтись в том, с чем сверяем. */
    const val MIN_SHARE = 0.8

    /** Со сколькими прошлыми ответами агента сверяется повтор себя. */
    const val EARLIER_ANSWERS = 3

    /**
     * Что поймано в одном ответе: предложения ответа, как они стоят в нём, или
     * null — не поймано. Цитаты — только для человека на экране: в запрос к
     * модели они не идут (см. [ConversationJournal.composeUserContent]).
     */
    data class Result(val selfRepeat: String?, val mirror: String?) {
        val any: Boolean get() = selfRepeat != null || mirror != null
    }

    /**
     * Эхо в ответе последнего хода ленты, или null — ходов нет.
     *
     * @param turns ходы ленты от старых к новым.
     */
    fun ofLast(turns: List<ConversationJournal.Turn>): Result? {
        val last = turns.lastOrNull() ?: return null
        val earlier = turns.dropLast(1).takeLast(EARLIER_ANSWERS).map { it.agentContent }
        return check(last.agentContent, last.question, earlier)
    }

    /**
     * Эхо в одном ответе.
     *
     * @param question реплика владельца этого хода; пустая — ход начат агентом.
     * @param earlierAnswers прошлые ответы агента, с которыми сверяется повтор себя.
     */
    fun check(answer: String, question: String, earlierAnswers: List<String>): Result {
        val questionStems = if (question.isBlank()) emptySet() else RiskTrigger.echoStems(question)
        val earlierSentences = earlierAnswers
            .flatMap { Sentences.split(it) }
            .map { RiskTrigger.echoStems(it) }
            .filter { it.isNotEmpty() }

        var selfRepeat: String? = null
        var mirror: String? = null
        for (sentence in Sentences.split(answer)) {
            val stems = RiskTrigger.echoStems(sentence)
            if (stems.size < MIN_STEMS) continue
            if (mirror == null && questionStems.isNotEmpty() && covered(stems, questionStems)) {
                mirror = sentence
            }
            // Одно предложение одного прошлого ответа, а не все их слова вместе:
            // слова, рассыпанные по трём ответам, — это не повтор фразы.
            if (selfRepeat == null && earlierSentences.any { covered(stems, it) }) {
                selfRepeat = sentence
            }
        }
        return Result(selfRepeat, mirror)
    }

    /**
     * Строка прибора «Эхо:» к последнему ответу. Печатается всегда: «нет»
     * должно отличаться от «не считалось».
     */
    fun meter(result: Result?): String {
        if (result == null) return "Эхо: ответа ещё нет"
        return "Эхо: повтор себя — ${say(result.selfRepeat)} · зеркало — ${say(result.mirror)}"
    }

    private fun say(sentence: String?): String = if (sentence == null) "нет" else "«$sentence»"

    // Деление, а не умножение порога: частное 4/5 округляется в то же число,
    // что и запись 0.8, и ровно пороговая доля проходит.
    private fun covered(stems: Set<String>, source: Set<String>): Boolean =
        stems.count { it in source }.toDouble() / stems.size >= MIN_SHARE
}
