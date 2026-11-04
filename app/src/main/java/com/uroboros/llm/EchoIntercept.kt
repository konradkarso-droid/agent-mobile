package com.uroboros.llm

import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.Sentences

/**
 * Перехват повтора: готовый ответ агента повторяет его же прошлый ответ —
 * решить, нужна ли вторая попытка и без каких ходов её собирать.
 * Чистая логика — без Android и без состояния, чтобы закрепляться тестами.
 *
 * ПОЧЕМУ ВТОРАЯ ПОПЫТКА БЕЗ ОБРАЗЦА, А НЕ ПРОСТО ЗАНОВО. Малая модель
 * копирует ближайший готовый текст — свой прошлый ответ. Та же лента с другим
 * зерном даёт ту же копию; служебная строка «ты повторился» рождает эхо самой
 * строки (см. [ConversationJournal.composeUserContent]). Меняется то, что
 * модель видит: ход, чей ответ скопирован, выкидывается из запроса целиком
 * ([ConversationJournal.messagesFor], `without`). Сама лента не меняется.
 *
 * ОБРАЗЕЦ — И ЗАПИСЬ СВОЕЙ РЕЧИ. Прошлый ответ агента приходит не только
 * ходом ленты, но и записью памяти этого хода «Я говорил …: «…»»
 * ([ownSpeech]). Рядом с записью реплики владельца, на которую он тогда
 * отвечал, такая запись складывается в готовый кусок прошлого разговора, и
 * модель проигрывает свою реплику заново слово в слово. Мера та же, сверка —
 * с текстом в кавычках; вторая попытка — без скопированной строки
 * вспоминания ([recallWithout]). Строки сверяются только своей речи: пересказ
 * записи о мире или слов владельца — законный ответ (см. [EchoCheck]).
 *
 * МЕРА — ПРИБОРА ЭХА ([EchoCheck]): те же предложения, основы, доля и те же
 * три прошлых ответа. Своей меры здесь нет намеренно: строка «Эхо:» на
 * экране и решение о перехвате должны говорить об одном.
 *
 * ПЕРЕХВАТ — ТОЛЬКО КОГДА ПОВТОР НЕСЁТ ОТВЕТ. Повторённые предложения должны
 * нести не меньше [EchoCheck.INTERCEPT_SHARE] основ ответа: короткое
 * подтверждение уже сказанного внутри ответа по делу — не копия ответа, и
 * вторая попытка стоит дорого (у модели с Mamba-слоями — пересчёт всей ленты).
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - мера — основы слов, а не смысл. Законный пересказ своего прошлого ответа
 *    с теми же словами будет перехвачен; перефраз без общих слов — нет;
 *  - копия записи своей речи ловится, только если запись подана на этом
 *    ходе: в ленту прошлых ходов записи не подаются вовсе;
 *  - образец ищется только среди трёх последних ходов, поэтому выкидывается
 *    всегда близкое к концу ленты и пересчёт короткий. Копия ответа,
 *    стоящего дальше, не ловится вовсе (как и строкой «Эхо:»);
 *  - не лечит ход, где модели нечего сказать иначе: без образца она находит
 *    другой готовый текст. Поэтому вторая попытка одна, а что делать, если
 *    и она повтор, решает вызывающий;
 *  - предложение кончается знаком конца и пробелом (см. [Sentences]);
 *    последнее предложение законченным становится только с концом ответа,
 *    поэтому проверяется готовый ответ, а не пишущийся.
 */
object EchoIntercept {

    /**
     * Нужна вторая попытка. [sentence] — первое предложение ответа, найденное
     * повтором (для прибора на экране); [without] — номера ходов ленты, в
     * чьих ответах найдены повторённые предложения, — их нет в запросе
     * второй попытки; [withoutRecall] — строки вспоминания своей речи, в чьих
     * кавычках найдены повторённые предложения, — их нет во вспоминании второй
     * попытки. Хотя бы одно из двух не пусто: повтор всегда где-то найден.
     */
    data class Retry(val sentence: String, val without: Set<Int>, val withoutRecall: Set<String> = emptySet())

    /**
     * Повтор себя в готовом ответе, или null — перехватывать нечего.
     *
     * @param answer готовый ответ, ещё не легший в ленту.
     * @param question реплика владельца этого хода (для меры, как у [EchoCheck.check]).
     * @param history ходы ленты ДО этого ответа, от старых к новым.
     * @param ownSpeech строки вспоминания этого хода со своей речью ([ownSpeech]).
     */
    fun decide(
        answer: String,
        question: String,
        history: List<ConversationJournal.Turn>,
        ownSpeech: List<String> = emptyList(),
    ): Retry? {
        val recent = history.indices.toList().takeLast(EchoCheck.EARLIER_ANSWERS)
        val quoted = ownSpeech.map { it to quoteOf(it) }
        if (recent.isEmpty() && quoted.isEmpty()) return null
        val earlier = recent.map { history[it].agentContent } + quoted.map { it.second }
        if (EchoCheck.check(answer, question, earlier).selfRepeat == null) return null
        // Ответ режется так же, как в EchoCheck.check, и каждое предложение
        // меряется той же мерой: образцом считается ход, где нашлось хоть одно
        // повторённое предложение, а не только первое.
        val repeated = Sentences.split(answer).filter {
            EchoCheck.check(it, question, earlier).selfRepeat != null
        }
        val without = recent.filter { index ->
            repeated.any { EchoCheck.check(it, question, listOf(history[index].agentContent)).selfRepeat != null }
        }.toSet()
        val withoutRecall = quoted.filter { (_, quote) ->
            repeated.any { EchoCheck.check(it, question, listOf(quote)).selfRepeat != null }
        }.map { it.first }.toSet()
        // Пусто быть не может: повтор найден в одном из этих же текстов. Если
        // разрезка разошлась с EchoCheck.check — перехватывать не на чем.
        if (repeated.isEmpty() || (without.isEmpty() && withoutRecall.isEmpty())) return null
        if (EchoCheck.share(answer, repeated) < EchoCheck.INTERCEPT_SHARE) return null
        return Retry(repeated.first(), without, withoutRecall)
    }

    /**
     * Строки вспоминания [recall] со своей речью агента — записи, поданные
     * формой ProvenanceLabels.OWN_SPEECH_FOR_MODEL. Порядок — как во вспоминании.
     */
    fun ownSpeech(recall: String?): List<String> =
        recall?.lines()?.filter { it.startsWith(ProvenanceLabels.OWN_SPEECH_FOR_MODEL) }.orEmpty()

    /** Вспоминание без строк [lines]; null — от него ничего не осталось. */
    fun recallWithout(recall: String?, lines: Set<String>): String? {
        if (recall == null || lines.isEmpty()) return recall
        return recall.lines().filterNot { it in lines }.joinToString("\n").takeIf { it.isNotBlank() }
    }

    /** Сказанное в записи своей речи — текст в кавычках, без подписи «Я говорил …». */
    fun quoteOf(line: String): String {
        val open = line.indexOf('«')
        val close = line.lastIndexOf('»')
        return if (open >= 0 && close > open) line.substring(open + 1, close) else line
    }
}
