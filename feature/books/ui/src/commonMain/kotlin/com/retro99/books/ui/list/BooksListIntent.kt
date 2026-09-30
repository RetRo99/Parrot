package com.retro99.books.ui.list

import com.retro99.base.server.ServerType
import com.retro99.base.ui.BaseIntent
import com.retro99.books.ui.model.BookListViewMode
import com.retro99.books.ui.model.BookQuickFilter
import com.retro99.books.ui.model.BookSortConfig
import com.retro99.books.ui.model.BookUiModel
import io.github.vinceglb.filekit.core.PlatformFile

sealed interface BooksListIntent : BaseIntent {
    data object OnRefresh : BooksListIntent
    data object OnSearchActivated : BooksListIntent
    data object OnSearchKeyboardDismissed : BooksListIntent
    data object OnSearchClosed : BooksListIntent
    data class OnSearchQueryChanged(val query: String) : BooksListIntent
    data class OnSearchSubmitted(val query: String) : BooksListIntent
    data class OnRecentSearchSelected(val query: String, val run: Boolean) : BooksListIntent
    data object OnRecentSearchesCleared : BooksListIntent
    data class OnBookClicked(val book: BookUiModel) : BooksListIntent
    data class OnFavoriteClicked(val bookUuid: String) : BooksListIntent
    data class OnImportBook(val file: PlatformFile) : BooksListIntent
    data object OnBackupAllClicked : BooksListIntent
    data class OnBackupAllAttestationChanged(val attested: Boolean) : BooksListIntent
    data object OnBackupAllConfirmed : BooksListIntent
    data object OnBackupAllDismissed : BooksListIntent
    data object OnBackupAllResultDismissed : BooksListIntent
    data class OnImportBackupAttestationChanged(val attested: Boolean) : BooksListIntent
    data object OnImportBackupConfirmed : BooksListIntent
    data object OnImportBackupDismissed : BooksListIntent

    data class OnQuickFilterToggled(val filter: BookQuickFilter) : BooksListIntent
    data class OnServerTypeFilterChanged(val serverType: ServerType?) : BooksListIntent
    data object OnClearAllFilters : BooksListIntent
    data object OnClearQuickFilters : BooksListIntent

    data class OnSortChanged(val sortConfig: BookSortConfig) : BooksListIntent

    data class OnViewModeChanged(val viewMode: BookListViewMode) : BooksListIntent
}
