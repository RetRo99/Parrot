package com.retro99.books.ui.list

import com.retro99.base.result.AppError
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.model.BookHome
import com.retro99.books.ui.model.BookFilterState
import com.retro99.books.ui.model.BookListViewMode
import com.retro99.books.ui.model.BookProgressInfoUiModel
import com.retro99.books.ui.model.BookQuickFilter
import com.retro99.books.ui.model.BookSortConfig
import com.retro99.books.ui.model.BookSortOption
import com.retro99.books.ui.model.CloudBackupBook
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.SortDirection
import com.retro99.books.ui.model.availableHomes
import com.retro99.books.ui.model.cloudBackupBooks
import com.retro99.books.ui.model.filterByHome
import com.retro99.books.ui.model.isOnThisDevice
import com.retro99.books.ui.model.showHomeBadge

data class BooksListViewState(
    val books: List<BookUiModel> = emptyList(),
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val favoriteBookUuids: Set<String> = emptySet(),
    val bookProgressInfo: Map<String, BookProgressInfoUiModel> = emptyMap(),
    val filterState: BookFilterState = BookFilterState(),
    val sortConfig: BookSortConfig = BookSortConfig(),
    val viewMode: BookListViewMode = BookListViewMode.LIST,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isImporting: Boolean = false,
    val importedBookToOpen: String? = null,
    val supportsCloudBackup: Boolean = false,
    val showCloudBackupSelection: Boolean = false,
    val selectedCloudBackupBookIds: Set<String> = emptySet(),
    val cloudBackupRightsAttested: Boolean = false,
    val isAddingBooksToCloud: Boolean = false,
    val cloudStorageAvailableBytes: Long? = null,
    val isLoadingCloudStorage: Boolean = false,
    val cloudStorageUnavailable: Boolean = false,
    val uploadingBookIds: Set<String> = emptySet(),
    val activeCloudUploads: Map<String, List<BookFileTransfer>> = emptyMap(),
    val cloudBackupSnackbarBookCount: Int? = null,
    val pendingAutoBackupBookUuid: String? = null,
    val pendingAutoBackupMediaType: String? = null,
    val importBackupRightsAttested: Boolean = false,
    val isStartingImportBackup: Boolean = false,
    /** The "add your local books to Parrot Cloud" note is hidden forever once dismissed. */
    val cloudBackupNoteDismissed: Boolean = false,
    val error: AppError? = null,
    /** Pairs of books that may be the same across servers, waiting for review. */
    val linkSuggestionCount: Int = 0,
) {
    /** The "books may be the same" row shows only while something is waiting. */
    val showLinkSuggestionsBanner: Boolean
        get() = linkSuggestionCount > 0

    /** Exact books the Parrot Cloud selection sheet can enqueue. */
    val cloudBackupBooks: List<CloudBackupBook>
        get() = books.cloudBackupBooks(uploadingBookIds)

    /** Books that live only on this phone: what the Parrot Cloud note offers to add. */
    val localOnlyBookCount: Int
        get() = cloudBackupBooks.size

    val selectedCloudBackupBooks: List<CloudBackupBook>
        get() = cloudBackupBooks.filter { book -> book.id in selectedCloudBackupBookIds }

    val selectedCloudBackupBytes: Long
        get() = selectedCloudBackupBooks.sumOf(CloudBackupBook::sizeBytes)

    val cloudBackupOverQuota: Boolean
        get() = cloudStorageAvailableBytes?.let { selectedCloudBackupBytes > it } == true

    /** The Parrot Cloud note is useful only when signed in and something is still local-only. */
    val showCloudBackupNote: Boolean
        get() = supportsCloudBackup && !cloudBackupNoteDismissed && localOnlyBookCount > 0

    val showImportBackupAttestation: Boolean
        get() = pendingAutoBackupBookUuid != null

    val filteredBooks: List<BookUiModel>
        get() = books
            .applySearchFilter(searchQuery)
            .filterByHome(filterState.homeFilter)
            .applyQuickFilters(filterState.activeQuickFilters, favoriteBookUuids, bookProgressInfo)
            .applySorting(sortConfig)

    val showServerBadge: Boolean
        get() = books.showHomeBadge()

    /** The homes the source filter offers. */
    val availableHomes: List<BookHome>
        get() = books.availableHomes()

    private fun List<BookUiModel>.applySearchFilter(query: String): List<BookUiModel> {
        if (query.isBlank()) return this
        val lowerQuery = query.lowercase()
        return filter { book ->
            book.title.lowercase().contains(lowerQuery) ||
                    book.subtitle?.lowercase()?.contains(lowerQuery) == true ||
                    book.authors.any { it.lowercase().contains(lowerQuery) } ||
                    book.series.any { it.name.lowercase().contains(lowerQuery) } ||
                    book.tags.any { it.lowercase().contains(lowerQuery) } ||
                    // A linked book matches if any of its copies does.
                    book.linkedCopies.any { copy ->
                        copy.searchTerms.any { term -> term.lowercase().contains(lowerQuery) }
                    }
        }
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
                    BookQuickFilter.CACHED -> book.isOnThisDevice(progressInfo[book.uuid])
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
