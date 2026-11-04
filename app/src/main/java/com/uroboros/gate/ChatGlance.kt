package com.uroboros.gate

import com.uroboros.llm.ConversationJournal
import com.uroboros.llm.Glance
import com.uroboros.memory.desk.Promises

/**
 * Взгляд агента на приборы в чате телеграма: модель зовёт инструмент
 * (llm.Glance), код показывает доску этого чата, и модель отвечает вторым
 * проходом. Чистая логика: проходы модели и доску даёт зовущий
 * (gate.link.ChatLink).
 *
 * ЗАЧЕМ. Без приборов числа о себе в чате брались с потолка («записей 3» при
 * сотнях), а строка тела, стоявшая вместо приборов перед каждой репликой,
 * делала температуру темой почти каждого ответа. Теперь о теле и числах агент
 * узнаёт, только когда сам глянул, — как на экране.
 *
 * ПОРЯДОК ХОДА — тот же, что у экрана (llm.ConversationTurns, там же объяснено,
 * почему так): вызов обрывает выдачу; сказанное до вызова не идёт в ответ —
 * ответ хода даёт второй проход с доской; второй вызов подряд доску снова не
 * показывает, ответ режется по нему; после доски пусто — тот же проход ещё
 * ОДИН раз, и только если прошлый кончился сам или обрезан на вызове.
 * Здесь сверх экрана — проверка [Halt] перед каждым следующим проходом:
 * ответ в чате ждать не обязан, и проход, начатый в нагреве или после стопа,
 * незачем.
 *
 * ДОСКА ЧАТА ([desk]) — не доска экрана. Модель вправе пересказать собеседнику
 * любую её строку, а ответ в телеграм уходит необратимо, поэтому на ней только
 * то, что можно сказать любому в этом чате (решение владельца): тело, число
 * записей памяти (число, не содержание), скорость прошлого ответа в этом чате
 * и обещания только этого чата. Модели, контекста и ленты экрана нет: имя
 * модели помогло бы подбирать к ней уловки заранее, на своей копии весов, а
 * лента экрана — разговоры владельца. Что описание приборов в стене их обещает,
 * а здесь их нет, доска говорит словами ([NOT_HERE]), — иначе модель назвала
 * бы их наугад. Время ставит сама обёртка ответа инструмента (Glance.response).
 *
 * ЧЕГО НЕ УМЕЕТ: зовёт приборы модель — не позовёт, ответит из головы;
 * следующий ход доски не видит; скорость — прошлого ответа этого чата с
 * запуска службы, до первого ответа её нет; доска в ленту чата не ложится, и в
 * снимок ленты тоже (она после границы снимка, ChatFeed.stableMessages).
 */
object ChatGlance {

    /**
     * Итог одного прохода модели: [text] — выданное (при вызове — до него);
     * [called] — проход оборван на вызове приборов; [broken] — проход оборван
     * не нами и не кончился сам (сторож, сбой движка), словами; null — кончился
     * сам или обрезан на вызове.
     */
    data class Pass(val text: String, val called: Boolean, val broken: String? = null)

    /** Глядел ли агент на приборы в этом ходе — для строки события на экране врат. */
    enum class Look(val words: String) {
        NONE("не глядел на приборы"),
        LOOKED("глянул на приборы"),
        RETRIED("глянул на приборы; после доски было пусто — проход повторён"),
    }

    sealed class Outcome {
        data class Answer(val text: String, val look: Look) : Outcome()

        /** Ответа нет; [why] — почему, словами для экрана врат. */
        data class NoAnswer(val why: String, val look: Look) : Outcome()
    }

    /** Почему следующий проход не идёт (стоп, ночь, сторож); null — можно. */
    fun interface Halt {
        fun reason(): String?
    }

    /** Строки доски, которых в чате нет, хотя их обещает описание приборов в стене. */
    const val NOT_HERE = "Модель, контекст и заполнение ленты в телеграме не называю."

    /**
     * Доска чата — строки по порядку, без шапки и времени (их ставит
     * Glance.response). [body] — строка тела (safety.BodyLine); [records] —
     * число записей, null — не прочиталось; [speed] — ток/с прошлого ответа в
     * этом чате, null — не было; [promises] — строка обещаний этого чата
     * ([promisesLine]).
     */
    fun desk(body: String, records: Int?, speed: String?, promises: String): String = listOfNotNull(
        body,
        records?.let(Glance::recordsLine) ?: "Память: число записей сейчас не прочиталось.",
        speed?.let { "Прошлый ответ в этом чате: выдача $it ток/с" },
        promises,
        NOT_HERE,
    ).joinToString("\n")

    /**
     * Обещания этого чата для доски: [promised] — что нашлось (null — не
     * прочиталось); пустота и сбой сказаны словами, и по-разному: «нет
     * обещаний» и «не видны» для модели — разное.
     */
    fun promisesLine(promised: List<Promises.Promise>?, day: (Long) -> String): String = when {
        promised == null -> "Обещания этого чата мне сейчас не видны."
        else -> ChatPromises.chatText(promised, day) ?: "Обещаний в этом чате за ${Promises.DAYS} дн. не нашлось."
    }

    /**
     * Ход с возможным взглядом. [messages] — сообщения хода (ChatFeed.messages);
     * [pass] — один проход модели по сообщениям; [desk] — доска чата, строками
     * ([desk]); [halt] — проверка перед каждым следующим проходом.
     */
    suspend fun run(
        messages: List<Pair<String, String>>,
        pass: suspend (List<Pair<String, String>>) -> Pass,
        desk: suspend () -> String,
        halt: Halt,
    ): Outcome {
        val first = pass(messages)
        first.broken?.let { return Outcome.NoAnswer(it, Look.NONE) }
        if (!first.called) {
            val text = first.text.trim()
            return if (text.isEmpty()) Outcome.NoAnswer("модель не выдала ни знака", Look.NONE) else Outcome.Answer(text, Look.NONE)
        }
        halt.reason()?.let { return Outcome.NoAnswer("позвал приборы, второй проход не пошёл — $it", Look.LOOKED) }
        val withDesk = messages + listOf(
            ConversationJournal.ROLE_ASSISTANT to Glance.callMessage(first.text),
            ConversationJournal.ROLE_USER to Glance.response(desk()),
        )
        val second = pass(withDesk)
        second.broken?.let { return Outcome.NoAnswer("глянул на приборы, ответ оборван — $it", Look.LOOKED) }
        second.text.trim().takeIf { it.isNotEmpty() }?.let { return Outcome.Answer(it, Look.LOOKED) }
        halt.reason()?.let { return Outcome.NoAnswer("глянул на приборы, после доски пусто; повтор не пошёл — $it", Look.LOOKED) }
        val third = pass(withDesk)
        third.broken?.let { return Outcome.NoAnswer("глянул на приборы, после доски пусто; повтор оборван — $it", Look.RETRIED) }
        val text = third.text.trim()
        return if (text.isEmpty()) Outcome.NoAnswer("глянул на приборы, после доски пусто и после повтора", Look.RETRIED)
        else Outcome.Answer(text, Look.RETRIED)
    }
}
