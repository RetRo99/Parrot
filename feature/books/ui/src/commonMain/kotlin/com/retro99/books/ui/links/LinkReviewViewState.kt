package com.retro99.books.ui.links

import com.retro99.base.result.AppError
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.ui.model.LinkSuggestionUiModel

data class LinkReviewViewState(
    val suggestions: List<LinkSuggestionUiModel> = emptyList(),
    val isLoading: Boolean = true,
    /** How many books "Link all confident matches" just linked. Null when it hasn't run. */
    val linkedCount: Int? = null,
    /** Set when a link failed because both books come from this source. */
    val sameSourceError: CopySource? = null,
    val error: AppError? = null,
    val bulkFailures: List<String> = emptyList(),
    val catalogueFailures: Map<String, AppError> = emptyMap(),
) {
    val confidentCount: Int
        get() = suggestions.count { suggestion -> suggestion.isConfident }
}
