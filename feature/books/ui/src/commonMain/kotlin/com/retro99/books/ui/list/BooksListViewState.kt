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
import com.retro99.server.api.library.SourceBookKey

data class BooksListViewState(
    val books: List<BookUiModel> = emptyList(),
    val searchQuery: String = "",
    val isSearchVisible: Boolean = false,
    val isMergeSelectionMode: Boolean = false,
    val selectedMergeGroupIds: Set<String> = emptySet(),
    val mergeMetadataOptions: List<MergeMetadataSourceOption> = emptyList(),
    val preferredMergeMetadataSourceKey: SourceBookKey? = null,
    val isResolvingMergeSelection: Boolean = false,
    val showMergeConfirmation: Boolean = false,
    val isMergingGroups: Boolean = false,
    val mergeSelectionError: Boolean = false,
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

    val canMergeSelectedGroups: Boolean
        get() = isMergeSelectionMode && selectedMergeGroupIds.size >= 2 &&
            !isResolvingMergeSelection && !isMergingGroups

    val filteredBooks: List<BookUiModel>
        get() = books
            .applySearchFilter(searchQuery)
            .applyServerTypeFilter(filterState.serverTypeFilter)
            .applyQuickFilters(filterState.activeQuickFilters, favoriteBookUuids, bookProgressInfo)
            .applySorting(sortConfig)

    val showServerBadge: Boolean
        get() = books.flatMap { book -> book.groupServerTypes }.distinct().size > 1

    private fun List<BookUiModel>.applySearchFilter(query: String): List<BookUiModel> {
        if (query.isBlank()) return this
        val lowerQuery = query.lowercase()
        return filter { book ->
            (listOf(book.title) + book.alternateTitles).any { title ->
                title.lowercase().contains(lowerQuery)
            } ||
                    book.authors.any { author -> author.lowercase().contains(lowerQuery) } ||
                    book.series.any { series -> series.name.lowercase().contains(lowerQuery) } ||
                    book.tags.any { tag -> tag.lowercase().contains(lowerQuery) }
        }
    }

    private fun List<BookUiModel>.applyServerTypeFilter(serverType: ServerType?): List<BookUiModel> {
        if (serverType == null) return this
        return filter { book -> serverType.identifier in book.groupServerTypes }
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
                    BookQuickFilter.FAVORITES ->
                        book.groupMemberUuids.any { bookUuid -> bookUuid in favoriteUuids }
                    BookQuickFilter.IN_PROGRESS -> book.groupProgress(progressInfo)
                        ?.displayProgression?.let { progression -> progression > 0.0 } == true
                    BookQuickFilter.CACHED -> book.groupProgress(progressInfo)
                        ?.hasAnyCached == true
                    BookQuickFilter.HAS_EBOOK -> book.hasEbook
                    BookQuickFilter.HAS_AUDIOBOOK -> book.hasAudiobook || book.hasReadaloud
                    BookQuickFilter.HAS_READALOUD -> book.hasReadaloud
                    BookQuickFilter.IN_SERIES -> book.series.isNotEmpty()
                }
            }
        }
    }

    private fun BookUiModel.groupProgress(
        progressInfo: Map<String, BookProgressInfoUiModel>,
    ): BookProgressInfoUiModel? = unifiedGroupId?.let { groupId -> progressInfo[groupId] }
        ?: groupMemberUuids.firstNotNullOfOrNull { bookUuid -> progressInfo[bookUuid] }

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
