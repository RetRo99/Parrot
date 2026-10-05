package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.links.LinkCandidate
import com.retro99.books.domain.model.links.LinkSuggestion
import com.retro99.books.domain.model.links.SuggestionReason

/** Two books that may be the same, as one row of the review screen. */
data class LinkSuggestionUiModel(
    val pairKey: String,
    val first: SuggestedBookUiModel,
    val second: SuggestedBookUiModel,
    val score: Int,
    val reason: SuggestionReason,
    val isConfident: Boolean,
    val identifierLabel: String? = null,
)

data class SuggestedBookUiModel(
    val title: String,
    val authors: List<String>,
    val home: BookHome,
)

fun LinkSuggestion.toUiModel() = LinkSuggestionUiModel(
    pairKey = pairKey,
    first = first.toUiModel(),
    second = second.toUiModel(),
    score = score,
    reason = reason,
    isConfident = isConfident,
    identifierLabel = when {
        sharedIsbn != null -> "Same ISBN"
        sharedAsin != null -> "Same ASIN"
        else -> null
    },
)

private fun LinkCandidate.toUiModel() = SuggestedBookUiModel(
    title = title,
    authors = authors,
    home = home,
)
