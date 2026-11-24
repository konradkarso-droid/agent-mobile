    private fun diverse(matches: List<IndexedMatch>, limit: Int = SEARCH_LIMIT): List<IndexedMatch> {
        if (matches.size <= limit) return matches
        val selected = mutableListOf<IndexedMatch>()
        val counts = mutableMapOf<String, Int>()
        for (match in matches) {
            val host = runCatching { java.net.URI(match.page.url).host?.lowercase() }.getOrNull()
                ?: match.page.url
            if ((counts[host] ?: 0) >= 2) continue
            selected += match
            counts[host] = (counts[host] ?: 0) + 1
            if (selected.size == limit) break
        }
        if (selected.size < limit) {
            for (match in matches) {
                if (match in selected) continue
                selected += match
                if (selected.size == limit) break
            }
        }
        return selected
    }
