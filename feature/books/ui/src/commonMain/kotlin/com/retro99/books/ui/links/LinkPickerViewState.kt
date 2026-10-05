package com.retro99.books.ui.links

import com.retro99.base.result.AppError
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.ui.model.BookUiModel

data class LinkPickerViewState(
    /** Books from other sources that the current book can be linked to. */
    val books: List<BookUiModel> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    /** Set when linking failed because both books come from this source. */
    val sameSourceError: CopySource? = null,
    val error: AppError? = null,
    val currentBook: BookUiModel? = null,
    val pendingBook: BookUiModel? = null,
    val isLinking: Boolean = false,
    val linkFailureMessage: String? = null,
    val catalogueFailures: Map<String, AppError> = emptyMap(),
) {
    val filteredBooks: List<BookUiModel>
        get() {
            val query = searchQuery.trim()
            if (query.isEmpty()) return books
            return books.filter { book ->
                book.title.contains(query, ignoreCase = true) ||
                    book.authors.any { author -> author.contains(query, ignoreCase = true) }
            }
        }
}
