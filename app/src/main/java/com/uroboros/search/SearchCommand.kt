package com.uroboros.search

internal sealed interface SearchCommand {
    data class Find(val query: String) : SearchCommand
    data object Last : SearchCommand
    data object History : SearchCommand
    data object Deferred : SearchCommand

    companion object {
        const val DEFERRED_MESSAGE = "Веб-поиск по времени пока недоступен. Запрос в сеть не отправлен."

        fun parse(text: String?, botName: String?): SearchCommand? {
            val words = text?.trim()?.split(Regex("\\s+"), limit = 2) ?: return null
            val name = words.first().substringBefore('@')
            if (name !in setOf("/search", "/search_last", "/search_history")) return null
            val addressed = words.first().substringAfter('@', "")
            if ('@' in words.first() && (addressed.isEmpty() || !addressed.equals(botName, ignoreCase = true))) return null
            return when (name) {
                "/search_last" -> Last
                "/search_history" -> History
                else -> Find(words.getOrElse(1) { "" })
            }
        }

        // Явное поручение владельца открывает поиск. Обычные вопросы, цитаты
        // и слова о чужих действиях остаются в разговоре.
        private const val ADDRESS = """(?:(?:пожалуйста|привет|агент|уроборос)[,!:]\s*){0,2}"""
        private const val IMPERATIVE = """(?:найди(?:те)?|поищи(?:те)?|посмотри(?:те)?|проверь(?:те)?)"""
        private const val ASK = """(?:може(?:шь|те)\s+(?:найти|поискать|посмотреть|проверить))"""
        private const val ONLINE = """(?:в\s+(?:интернете|сети)|онлайн)"""
        private const val VERB = """(?:$IMPERATIVE|$ASK|(?:по)?гугли(?:те)?)"""
        private const val POLITE = """(?:[\s,]+пожалуйста,?)?"""
        private const val SEPARATOR = """[\s,:—–-]+"""
        private const val NUMBER = """(?:\d+|одну|один|два|две|три|четыре|пять|десять|пятнадцать|двадцать|тридцать)"""
        private const val UNIT = """(?:секунд[уы]?|минут[уы]?|час(?:а|ов)?|день|дня|дней)"""
        private const val RELATIVE_TIME = """через\s+(?:пол\s*часа|(?:$NUMBER\s+)?$UNIT)"""
        private const val PART_OF_DAY = """(?:сегодня\s+)?(?:вечером|утром|дн[её]м|ночью)"""
        private const val AT_TIME = """(?:(?:сегодня|завтра)\s+)?(?:в|к)\s+(?:\d{1,2}:\d{2}|$NUMBER\s+час(?:а|ов)?)"""
        private const val LATER = """(?:$RELATIVE_TIME|$PART_OF_DAY|$AT_TIME|позже|потом|послезавтра|завтра)"""

        // Ищем явную отсрочку в начале поручения и после отделённой паузой
        // темы. Относительное «через час» в конце тоже считаем отсрочкой.
        // «Погода вечером» в самой теме остаётся поиском сейчас.
        private val leadingDelay = Regex(
            """^$ADDRESS(?:$VERB$POLITE(?:\s+$ONLINE)?$SEPARATOR$LATER|$LATER$SEPARATOR$VERB)(?=$|[\s,.!?;:])""",
            RegexOption.IGNORE_CASE,
        )
        private val trailingDelay = Regex(
            """(?:[,;—–]\s*$LATER|\s+$RELATIVE_TIME)[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )

        private val webRequest = Regex(
            """^$ADDRESS(?:(?:$IMPERATIVE|$ASK)$POLITE\s+$ONLINE|(?:по)?гугли(?:те)?)[\s,:—–-]+(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val lastRequest = Regex(
            """^$ADDRESS(?:покажи(?:те)?\s+(?:(?:выдачу|результаты)\s+(?:последнего\s+)?поиска|последний\s+(?:веб-)?поиск)|""" +
                """что\s+(?:ты\s+)?наш[её]л\s+(?:в\s+интернете|в\s+поиске))[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val historyRequest = Regex(
            """^""" + ADDRESS + """покажи(?:те)?\s+историю\s+(?:веб-)?поиск(?:а|ов)[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )

        fun fromOwnerWords(text: String?): SearchCommand? {
            val phrase = text?.trim() ?: return null
            if (leadingDelay.containsMatchIn(phrase)) return Deferred
            val query = webRequest.matchEntire(phrase)?.groupValues?.get(1)?.trim()
            if (query != null && query.any { it.isLetterOrDigit() }) {
                return if (trailingDelay.containsMatchIn(query)) Deferred else Find(query)
            }
            return when {
                lastRequest.matches(phrase) -> Last
                historyRequest.matches(phrase) -> History
                else -> null
            }
        }

        fun isOwnerDirect(
            privateChat: Boolean, owner: String?, sender: String?, bot: Boolean,
            forwarded: Boolean, edited: Boolean, senderChat: Boolean, quoted: Boolean = false,
        ): Boolean = privateChat && owner != null && owner == sender && !bot && !forwarded && !edited && !senderChat && !quoted
    }
}
