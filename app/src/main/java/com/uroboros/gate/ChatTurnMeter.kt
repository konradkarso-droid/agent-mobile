package com.uroboros.gate

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

    /** Хвост строки «Ответ (…)»: когда ответил, за сколько, сколько знаков. */
    fun answered(clock: String, tookMs: Long, chars: Int): String =
        "ответил $clock за ${sec(tookMs)} с, $chars зн."

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
