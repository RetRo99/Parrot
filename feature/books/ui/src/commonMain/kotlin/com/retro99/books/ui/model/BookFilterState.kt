package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookHome
import kotlinx.serialization.Serializable

/**
 * Saved with the list settings. A value saved before [homeFilter] existed carried
 * `serverTypeFilter`, which is ignored on decode, so it comes back as no filter.
 */
@Serializable
data class BookFilterState(
    val activeQuickFilters: Set<BookQuickFilter> = emptySet(),
    val homeFilter: BookHome? = null,
) {
    val hasActiveFilters: Boolean
        get() = activeQuickFilters.isNotEmpty() || homeFilter != null

    val activeFilterCount: Int
        get() = activeQuickFilters.size + (if (homeFilter != null) 1 else 0)
}
