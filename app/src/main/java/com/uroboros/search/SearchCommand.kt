package com.uroboros.search

internal sealed interface SearchCommand {
    data class Find(val query: String) : SearchCommand
    data object Last : SearchCommand
    data object History : SearchCommand
    data object Refresh : SearchCommand
    data class Deferred(val query: String, val time: String) : SearchCommand

    sealed interface AutoRefresh : SearchCommand {
        data object Enable : AutoRefresh
        data object Disable : AutoRefresh
        data object Status : AutoRefresh
    }

    companion object {
        fun parse(text: String?, botName: String?): SearchCommand? {
            val words = text?.trim()?.split(Regex("\\s+"), limit = 2) ?: return null
            val name = words.first().substringBefore('@')
            val addressed = words.first().substringAfter('@', "")
            if ('@' in words.first() && (addressed.isEmpty() || !addressed.equals(botName, ignoreCase = true))) return null
            return when (name) {
                "/search_last" -> Last
                "/search_history" -> History
                "/search_refresh" -> Refresh
                "/search_auto_on" -> AutoRefresh.Enable.takeIf { words.size == 1 }
                "/search_auto_off" -> AutoRefresh.Disable.takeIf { words.size == 1 }
                "/search_auto_status" -> AutoRefresh.Status.takeIf { words.size == 1 }
                "/search" -> Find(words.getOrElse(1) { "" })
                else -> null
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
            """^$ADDRESS(?:$VERB$POLITE(?:\s+$ONLINE)?$SEPARATOR($LATER)|($LATER)$SEPARATOR$VERB$POLITE)(?=$|[\s,.!?;:])""",
            RegexOption.IGNORE_CASE,
        )
        private val trailingDelay = Regex(
            """(?:[,;—–]\s*($LATER)|\s+($RELATIVE_TIME))[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val leadingOnline = Regex("""^$ONLINE(?=$|[\s,:—–-])[\s,:—–-]*""", RegexOption.IGNORE_CASE)

        private val webRequest = Regex(
            """^$ADDRESS(?:(?:$IMPERATIVE|$ASK)$POLITE\s+$ONLINE|(?:по)?гугли(?:те)?)[\s,:—–-]+(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val lastRequest = Regex(
            """^$ADDRESS(?:и\s+|так[, ]+)?(?:покажи(?:те)?\s+(?:(?:выдачу|результаты)\s+(?:последнего\s+)?поиска|последний\s+(?:веб-)?поиск)|""" +
                """что\s+(?:ты\s+)?наш(?:[её]л|лось)(?:\s+(?:в\s+интернете|в\s+поиске))?|""" +
                """(?:дай|пришли|скинь|покажи)(?:те)?\s+ссылки(?:\s+(?:на\s+(?:новости|найденное|источники)|из\s+поиска))?)[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val historyRequest = Regex(
            """^$ADDRESS(?:покажи(?:те)?\s+историю\s+(?:веб-)?поиск(?:а|ов)|""" +
                """что\s+(?:ты\s+)?искал(?:\s+в\s+интернете)?)[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )

        private val refreshRequest = Regex(
            """^$ADDRESS(?:обнови(?:те)?|переобойди)\s+(?:источники(?:\s+(?:поиска|веб-поиска))?|(?:поисковый|локальный)\s+индекс)[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val enableAuto = Regex(
            """^$ADDRESS(?:обновляй(?:те)?\s+источники\s+автоматически|включи(?:те)?\s+автообновление\s+источников)[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val disableAuto = Regex(
            """^$ADDRESS(?:выключи(?:те)?|отключи(?:те)?|останови(?:те)?)\s+автообновление\s+источников[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val statusAuto = Regex(
            """^$ADDRESS(?:покажи(?:те)?\s+)?статус\s+автообновления(?:\s+источников)?[.!?]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val newsOrSourceRequest = Regex(
            """^${ADDRESS}(?:подбери(?:те)?|покажи(?:те)?|собери(?:те)?|найди(?:те)?)$POLITE$SEPARATOR(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val explicitNews = Regex(
            """^$ADDRESS((?:(?:свежие|последние)\s+новости|подборка\s+(?:(?:свежих|последних)\s+)?новостей).*)$""",
            RegexOption.IGNORE_CASE,
        )
        private val shortRequest = Regex(
            """^${ADDRESS}поищи(?:те)?$POLITE$SEPARATOR(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val localObject = Regex("""^(?:(?:у\s+себя)|(?:в|по|на)\s+(?:(?:моих|наших|своих)\s+)?(?:памяти|разговор|чате|телефоне|журнале|истории|доске|запис|файл|интернете|сети))""", RegexOption.IGNORE_CASE)

        fun fromOwnerWords(text: String?): SearchCommand? {
            val phrase = text?.trim() ?: return null
            leadingDelay.find(phrase)?.let { match ->
                val query = phrase.substring(match.range.last + 1).trimStart { it.isWhitespace() || it in ",:—–-" }
                val topic = query.replaceFirst(leadingOnline, "").takeUnless(localObject::containsMatchIn).orEmpty()
                return Deferred(topic, match.groupValues[1].ifEmpty { match.groupValues[2] })
            }
            if (refreshRequest.matches(phrase)) return Refresh
            val query = webRequest.matchEntire(phrase)?.groupValues?.get(1)?.trim()
                ?: shortRequest.matchEntire(phrase)?.groupValues?.get(1)?.trim()?.takeUnless(localObject::containsMatchIn)
                ?: newsOrSourceRequest.matchEntire(phrase)?.groupValues?.get(1)?.trim()?.takeIf {
                    PublicationQuery.isNewsRequest(it) || SearchRoute.startsWithSource(it)
                }
                ?: explicitNews.matchEntire(phrase)?.groupValues?.get(1)?.trim()
            if (query != null && query.any { it.isLetterOrDigit() }) {
                trailingDelay.find(query)?.let { match ->
                    return Deferred(query.substring(0, match.range.first).trimEnd(),
                        match.groupValues[1].ifEmpty { match.groupValues[2] })
                }
                return Find(query)
            }
            return when {
                lastRequest.matches(phrase) -> Last
                historyRequest.matches(phrase) -> History
                enableAuto.matches(phrase) -> AutoRefresh.Enable
                disableAuto.matches(phrase) -> AutoRefresh.Disable
                statusAuto.matches(phrase) -> AutoRefresh.Status
                else -> null
            }
        }

        fun isOwnerDirect(
            privateChat: Boolean, owner: String?, sender: String?, bot: Boolean,
            forwarded: Boolean, edited: Boolean, senderChat: Boolean, quoted: Boolean = false,
        ): Boolean = privateChat && owner != null && owner == sender && !bot && !forwarded && !edited && !senderChat && !quoted
    }
}
