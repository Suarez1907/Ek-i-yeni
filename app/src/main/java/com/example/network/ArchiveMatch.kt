package com.example.network

/**
 * Pure matching rules for Wayback/archive fallbacks.
 * A miss returns nothing. Unrelated homepage links are never substituted for a topic or search.
 */
object ArchiveMatch {
    fun selectTopicHref(links: List<Pair<String, String>>, query: String): String? {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) return null
        val usable = links.map { (text, href) -> text.trim().lowercase() to href.trim() }
            .filter { (text, href) -> text.isNotBlank() && href.isNotBlank() }
        usable.firstOrNull { (text, _) -> text == normalizedQuery }?.let { return it.second }
        return usable.firstOrNull { (text, _) ->
            text.contains(normalizedQuery) || normalizedQuery.contains(text)
        }?.second
    }

    fun matchingIndexes(itemTexts: List<String>, keywords: String): List<Int> {
        val cleanQuery = keywords.trim().lowercase()
        if (cleanQuery.isBlank()) return emptyList()
        return itemTexts.mapIndexedNotNull { index, text ->
            if (text.lowercase().contains(cleanQuery)) index else null
        }
    }
}
