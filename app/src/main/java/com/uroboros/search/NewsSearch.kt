package com.uroboros.search

import java.time.ZonedDateTime

internal data class SearchRoute(val publication: PublicationQuery, val source: WebFeedSource?) {
    companion object {
        private val aliases = webSources.flatMap { source -> source.aliases.map { it.lowercase() to source } }.toMap()
        private val name = aliases.keys.sortedByDescending(String::length).joinToString("|", transform = Regex::escape)
        private val named = Regex("""(?:^|\s+)(?:на|в|из|у)\s+($name)(?=$|[\s,.:!?])""", RegexOption.IGNORE_CASE)
        private val material = Regex("""^(?:материалы|статьи|публикации)\s+(?:об?|про|по)\s+""", RegexOption.IGNORE_CASE)

        fun parse(query: String, now: ZonedDateTime = ZonedDateTime.now()): SearchRoute {
            val match = named.find(query)
            val source = match?.groupValues?.get(1)?.lowercase()?.let(aliases::get)
            val subject = if (match == null) query else query.removeRange(match.range).trim(' ', ',')
                .replaceFirst(material, "")
            return SearchRoute(PublicationQuery.parse(subject, now), source)
        }

        fun startsWithSource(query: String): Boolean = named.find(query)?.range?.first == 0
    }
}

internal data class NewsHeadline(val source: WebFeedSource, val article: FeedArticle, val indexed: Boolean = false)

internal fun sourcesForNews(subject: String, named: WebFeedSource? = null): List<WebFeedSource> {
    if (named != null) return listOf(named)
    if (subject.isBlank()) return webSources
    return webSources.filter { it.general || it.topics.containsMatchIn(subject) }
}

internal fun newsWords(subject: String): String = subject
    .replace(Regex("""(?<![\p{L}\p{N}_])андроид[\p{L}]*(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE), "Android")
    .replace(Regex("""(?<![\p{L}\p{N}_])котлин[\p{L}]*(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE), "Kotlin")

internal fun selectHeadlines(
    entries: List<NewsHeadline>, words: SearchWords, window: PublicationWindow, limit: Int,
): List<NewsHeadline> {
    val scored = entries.asSequence().filter { it.article.title.isNotBlank() && window.contains(it.article.publishedAt) }
        .map { it to words.matchingGroups(newsWords(it.article.title)).size }
        .filter { words.terms.isEmpty() || it.second > 0 }
        .sortedWith(compareByDescending<Pair<NewsHeadline, Int>> { it.second }
            .thenByDescending { it.first.article.publishedAt }.thenBy { it.first.article.url })
        .map { it.first }.toList()
    val result = mutableListOf<NewsHeadline>()
    for (differentSource in listOf(true, false)) {
        for (item in scored) {
            if (result.size >= limit) return result
            if (differentSource && result.any { it.source == item.source }) continue
            if (result.none { sameHeadline(it.article, item.article) }) result += item
        }
    }
    return result
}

private fun sameHeadline(one: FeedArticle, two: FeedArticle): Boolean {
    if (one.url == two.url || one.title.equals(two.title, ignoreCase = true)) return true
    val left = searchTerms(one.title).toSet()
    val right = searchTerms(two.title).toSet()
    if (left.filter { it.all(Char::isDigit) }.toSet() != right.filter { it.all(Char::isDigit) }.toSet()) return false
    val common = left.intersect(right).size
    // Объявленная граница для почти одинаковых заголовков из разных лент.
    return common >= 4 && common * 4 >= (left + right).size * 3
}
