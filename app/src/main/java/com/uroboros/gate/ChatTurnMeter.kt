package com.uroboros.gate

import com.uroboros.llm.AnswerLadder
import java.util.Locale

/**
 * Строки экрана врат о том, во что обошёлся ответ в чате: сколько шла
 * генерация и сколько запроса движок посчитал заново, а сколько взял готовым.
 *
 * ЗАЧЕМ. Без этих чисел «начало запроса взято готовым» и «посчитано всё заново»
 * выглядят одинаково — просто ответом, только разной долготы. Сверять по ним:
 * растёт ли время ответа с длиной чата и что даёт окно скачками (ChatFeed).
 *
 * ЧЕГО НЕ УМЕЕТ: время — генерации, без ожидания очереди, замка хода и
 * отправки в телеграм; числа обсчёта — последнего запроса движка, поэтому
 * собирать строку можно только сразу после генерации ответа, под замком хода
 * (это держит тот, кто зовёт).
 *
 * Чистый объект.
 */
object ChatTurnMeter {

    /**
     * Хвост строки «Ответ (…)»: когда ответил, за сколько, сколько знаков; к нему
     * — [ladder], что было на лестнице ответа ([ladderNote]). Время — всех
     * проходов хода.
     */
    fun answered(clock: String, tookMs: Long, chars: Int, ladder: String? = null): String =
        "ответил $clock за ${sec(tookMs)} с, $chars зн." + (ladder?.let { " · $it" } ?: "")

    /**
     * Что было на лестнице ответа (llm.AnswerLadder) словами: глядел ли на
     * приборы — сам или доску показал код по слову вопроса (раздельно: иначе не
     * видно, зовёт ли модель приборы сама), был ли перехват и что ушло. [sentFirst] — годного ответа не
     * нашлось, и ушёл первый (в телеграме молчания из-за перехвата нет).
     */
    fun ladderNote(r: AnswerLadder.Result, sentFirst: Boolean): String = listOfNotNull(
        when {
            r.deskByCode != null && r.glanceRetried -> "доску показал код (слово «${r.deskByCode}»); после доски было пусто — проход повторён"
            r.deskByCode != null -> "доску показал код (слово «${r.deskByCode}»)"
            r.glanceRetried -> "глянул на приборы; после доски было пусто — проход повторён"
            r.glanced -> "глянул на приборы"
            else -> "не глядел на приборы"
        },
        r.intercept?.let { i ->
            "перехват: " + causeWords(i.cause) + " → " + when {
                sentFirst -> "годного нет, ушёл первый ответ"
                i.third?.chosen != null -> "ушёл вариант из трёх"
                i.replaced -> "ушла вторая попытка"
                else -> "вторая не лучше, ушёл первый ответ"
            }
        },
    ).joinToString(" · ")

    private fun causeWords(c: AnswerLadder.Cause): String = when (c) {
        is AnswerLadder.Cause.Repeat -> "повтор «${c.sentence}»"
        is AnswerLadder.Cause.Mirror -> "зеркало «${c.sentence}»"
        is AnswerLadder.Cause.Damage -> "порча «${c.words.joinToString(", ")}»"
    }

    /**
     * Строка «Обсчёт (…)»: [promptTokens] новых токенов запроса за
     * [promptUs] мкс (сверх взятого готовым); [reuse] — что движок взял
     * готовым, словами (LlmEngine.promptReuseReport). [clock] — когда ответ.
     */
    fun evalLine(chatName: String, clock: String, promptTokens: Long, promptUs: Long, reuse: String): String {
        val eval = when {
            promptTokens <= 0 -> "новых токенов нет — всё взято готовым"
            promptUs <= 0 -> "$promptTokens новых ток."
            else -> {
                val s = promptUs / 1_000_000.0
                "$promptTokens новых ток. за ${sec(promptUs / 1000)} с (${one(promptTokens / s)} ток/с)"
            }
        }
        return "Обсчёт ($chatName, $clock): $eval · $reuse"
    }

    /** Строка «Обсчёт (…)», когда генерация до ответа не дошла и чисел этого хода нет. */
    fun noEvalLine(chatName: String, clock: String): String =
        "Обсчёт ($chatName, $clock): не известно — генерация не дошла до ответа"

    private fun sec(ms: Long): String = one(ms / 1000.0)

    private fun one(x: Double): String = String.format(Locale.ROOT, "%.1f", x)
}
