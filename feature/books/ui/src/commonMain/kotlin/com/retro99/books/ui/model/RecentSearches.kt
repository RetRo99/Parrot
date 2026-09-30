package com.retro99.books.ui.model

import kotlinx.serialization.Serializable

@Serializable
data class RecentSearches(
    val queries: List<String> = emptyList(),
) {
    /** Puts [query] first, dropping duplicates (ignoring case) and keeping at most [MAX] items. */
    fun with(query: String): RecentSearches {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return this
        val rest = queries.filterNot { existing -> existing.equals(trimmed, ignoreCase = true) }
        return RecentSearches((listOf(trimmed) + rest).take(MAX))
    }

    companion object {
        const val MAX = 5
    }
}
