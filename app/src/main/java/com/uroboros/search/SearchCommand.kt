package com.uroboros.search

internal sealed interface SearchCommand {
    data class Find(val query: String) : SearchCommand
    data object Last : SearchCommand
    data object History : SearchCommand

    companion object {
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

        fun isOwnerDirect(
            privateChat: Boolean, owner: String?, sender: String?, bot: Boolean,
            forwarded: Boolean, edited: Boolean, senderChat: Boolean,
        ): Boolean = privateChat && owner != null && owner == sender && !bot && !forwarded && !edited && !senderChat
    }
}
