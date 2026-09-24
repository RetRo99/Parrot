package com.retro99.books.ui.list

import com.retro99.base.result.AppError
import com.retro99.base.server.ServerType
import com.retro99.books.ui.model.BookFilterState
import com.retro99.books.ui.model.BookListViewMode
import com.retro99.books.ui.model.BookProgressInfoUiModel
import com.retro99.books.ui.model.BookQuickFilter
import com.retro99.books.ui.model.BookSortConfig
import com.retro99.books.ui.model.BookSortOption
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.SortDirection

data class BooksListViewState(
    val books: List<BookUiModel> = emptyList(),
    val searchQuery: String = "",
    val isSearchVisible: Boolean = false,
    val favoriteBookUuids: Set<String> = emptySet(),
    val bookProgressInfo: Map<String, BookProgressInfoUiModel> = emptyMap(),
    val filterState: BookFilterState = BookFilterState(),
    val sortConfig: BookSortConfig = BookSortConfig(),
    val viewMode: BookListViewMode = BookListViewMode.LIST,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isImporting: Boolean = false,
    val supportsCloudBackup: Boolean = false,
    val showBackupAllConfirmation: Boolean = false,
    val backupAllRightsAttested: Boolean = false,
    val isBackingUpAll: Boolean = false,
    val backupAllQueuedCount: Int? = null,
    val backupAllFailedCount: Int? = null,
    val backupAllError: String? = null,
    val pendingAutoBackupBookUuid: String? = null,
    val importBackupRightsAttested: Boolean = false,
    val isStartingImportBackup: Boolean = false,
    val error: AppError? = null,
) {
    val showImportBackupAttestation: Boolean
        get() = pendingAutoBackupBookUuid != null

    val filteredBooks: List<BookUiModel>
        get() = books
            .applySearchFilter(searchQuery)
            .applyServerTypeFilter(filterState.serverTypeFilter)
            .applyQuickFilters(filterState.activeQuickFilters, favoriteBookUuids, bookProgressInfo)
            .applySorting(sortConfig)

    val showServerBadge: Boolean
        get() = books.mapNotNull { it.serverType }.distinct().size > 1

    private fun List<BookUiModel>.applySearchFilter(query: String): List<BookUiModel> {
        if (query.isBlank()) return this
        val lowerQuery = query.lowercase()
        return filter { book ->
            book.title.lowercase().contains(lowerQuery) ||
                    book.authors.any { it.lowercase().contains(lowerQuery) } ||
                    book.series.any { it.name.lowercase().contains(lowerQuery) } ||
                    book.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    private fun List<BookUiModel>.applyServerTypeFilter(serverType: ServerType?): List<BookUiModel> {
        if (serverType == null) return this
        return filter { book -> book.serverType == serverType }
    }

    private fun List<BookUiModel>.applyQuickFilters(
        filters: Set<BookQuickFilter>,
        favoriteUuids: Set<String>,
        progressInfo: Map<String, BookProgressInfoUiModel>,
    ): List<BookUiModel> {
        if (filters.isEmpty()) return this
        return filter { book ->
            filters.all { filter ->
                when (filter) {
                    BookQuickFilter.FAVORITES -> book.uuid in favoriteUuids
                    BookQuickFilter.IN_PROGRESS -> (progressInfo[book.uuid]?.displayProgression ?: 0.0) > 0.0
                    BookQuickFilter.CACHED -> progressInfo[book.uuid]?.hasAnyCached == true
                    BookQuickFilter.HAS_EBOOK -> book.hasEbook
                    BookQuickFilter.HAS_AUDIOBOOK -> book.hasAudiobook || book.hasReadaloud
                    BookQuickFilter.HAS_READALOUD -> book.hasReadaloud
                    BookQuickFilter.IN_SERIES -> book.series.isNotEmpty()
                }
            }
        }
    }

    private fun List<BookUiModel>.applySorting(config: BookSortConfig): List<BookUiModel> {
        val comparator: Comparator<BookUiModel> = when (config.option) {
            BookSortOption.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            BookSortOption.AUTHOR -> compareBy(String.CASE_INSENSITIVE_ORDER) {
                it.authors.firstOrNull() ?: ""
            }

            BookSortOption.RATING -> compareBy(nullsLast()) { it.rating }
            BookSortOption.DATE_PUBLISHED -> compareBy(nullsLast()) { it.publicationDate }
            BookSortOption.DATE_ADDED -> compareBy(nullsLast()) { it.dateAdded }
        }
        return when (config.direction) {
            SortDirection.ASCENDING -> sortedWith(comparator)
            SortDirection.DESCENDING -> sortedWith(comparator.reversed())
        }
    }
}
