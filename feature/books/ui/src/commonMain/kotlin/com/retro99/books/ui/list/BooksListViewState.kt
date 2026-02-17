package com.retro99.books.ui.list

import com.retro99.base.result.AppError
import com.retro99.books.ui.model.BookListItem

data class BooksListViewState(
    val books: List<BookListItem> = emptyList(),
    val searchQuery: String = "",
    val isSearchVisible: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isImporting: Boolean = false,
    val error: AppError? = null,
) {
    val filteredBooks: List<BookListItem>
        get() = if (searchQuery.isBlank()) {
            books
        } else {
            val query = searchQuery.lowercase()
            books.filter { item ->
                item.title.lowercase().contains(query) ||
                        item.authors.any { it.lowercase().contains(query) } ||
                        (item is BookListItem.StorytellerBook &&
                                (item.book.series.any { it.name.lowercase().contains(query) } ||
                                        item.book.tags.any { it.lowercase().contains(query) }))
            }
        }
}

