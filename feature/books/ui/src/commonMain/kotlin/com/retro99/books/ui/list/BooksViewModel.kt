package com.retro99.books.ui.list

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.BookAnalyticsEvent
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.base.result.AppResult
import com.retro99.base.result.log
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.usecase.DeleteImportedBookUseCase
import com.retro99.books.domain.usecase.GetBooksUseCase
import com.retro99.books.domain.usecase.GetImportedBooksUseCase
import com.retro99.books.domain.usecase.ObserveAllFavoritesUseCase
import com.retro99.books.domain.usecase.ToggleFavoriteUseCase
import com.retro99.books.ui.model.BookListItem
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.toUiModel
import com.retro99.reader.data.FileImportManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class BooksViewModel(
    @InjectedParam private val onNavigateToBookDetail: (book: BookUiModel) -> Unit,
    @InjectedParam private val onNavigateToImportedBook: (bookUuid: String) -> Unit,
    @Provided private val getBooksUseCase: GetBooksUseCase,
    @Provided private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    @Provided private val observeAllFavoritesUseCase: ObserveAllFavoritesUseCase,
    @Provided private val getImportedBooksUseCase: GetImportedBooksUseCase,
    @Provided private val deleteImportedBookUseCase: DeleteImportedBookUseCase,
    @Provided private val fileImportManager: FileImportManager,
    @Provided private val analytics: Analytics,
) : BaseViewModel<BooksListViewState, BooksListIntent>(BooksListViewState()) {

    val searchFieldState = TextFieldState()

    init {
        observeAllBooks()
        observeSearchQuery()
    }

    override fun onIntent(intent: BooksListIntent) {
        when (intent) {
            BooksListIntent.OnRefresh -> Unit // Flow automatically refreshes on start
            BooksListIntent.OnSearchToggled -> toggleSearch()
            is BooksListIntent.OnBookClicked -> onNavigateToBookDetail(intent.book)
            is BooksListIntent.OnFavoriteClicked -> toggleFavorite(intent.bookUuid)
            BooksListIntent.OnImportClicked -> Unit // Handled by UI with file picker
            is BooksListIntent.OnFileSelected -> importFile(intent.fileBytes, intent.fileName)
            is BooksListIntent.OnImportedBookClicked -> onNavigateToImportedBook(intent.bookUuid)
            is BooksListIntent.OnDeleteImportedBookClicked -> deleteImportedBook(intent.bookUuid)
        }
    }

    private fun toggleSearch() {
        val currentlyVisible = viewState.value.isSearchVisible
        // Only track when opening search, not closing
        if (!currentlyVisible) {
            analytics.logEvent(NavigationAnalyticsEvent.SearchOpened(source = "books_list"))
        }
        if (currentlyVisible) {
            // Clear search when hiding
            searchFieldState.edit { delete(0, length) }
        }
        updateState { it.copy(isSearchVisible = !currentlyVisible) }
    }

    private fun observeSearchQuery() {
        snapshotFlow { searchFieldState.text.toString() }
            .onEach { query ->
                updateState { it.copy(searchQuery = query) }
            }
            .launchIn(viewModelScope)
    }

    private fun toggleFavorite(bookUuid: String) {
        val isFavorite = viewState.value.books
            .filterIsInstance<BookListItem.StorytellerBook>()
            .find { it.uuid == bookUuid }
            ?.isFavorite ?: false
        // Log the new state (opposite of current)
        analytics.logEvent(
            BookAnalyticsEvent.FavoriteToggled(
                bookUuid = bookUuid,
                isFavorite = !isFavorite,
                source = "list",
            )
        )
        viewModelScope.launch {
            toggleFavoriteUseCase(bookUuid)
        }
    }

    private fun observeAllBooks() {
        combine(
            getBooksUseCase(),
            getImportedBooksUseCase(),
            observeAllFavoritesUseCase(),
        ) { booksResult, importedBooks, favoriteUuids ->
            Triple(booksResult, importedBooks, favoriteUuids)
        }
            .onStart {
                updateState { it.copy(isLoading = true, error = null) }
            }
            .onEach { (booksResult, importedBooks, favoriteUuids) ->
                val unifiedList = buildUnifiedBookList(
                    booksResult = booksResult,
                    importedBooks = importedBooks,
                    favoriteUuids = favoriteUuids,
                )

                booksResult
                    .onSuccess {
                        updateState {
                            it.copy(
                                books = unifiedList,
                                isLoading = false,
                                isRefreshing = false,
                                error = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        error.log(analytics, "BooksViewModel: Failed to load books")
                        updateState {
                            it.copy(
                                books = unifiedList,
                                isLoading = false,
                                isRefreshing = false,
                                error = error,
                            )
                        }
                    }
            }
            .launchIn(viewModelScope)
    }

    private fun buildUnifiedBookList(
        booksResult: AppResult<List<BookDomainModel.StorytellerBook>>,
        importedBooks: List<BookDomainModel.LocalBook>,
        favoriteUuids: Set<String>,
    ): List<BookListItem> {
        val storytellerBooks = booksResult.getOrElse { emptyList() }
            .map { book ->
                BookListItem.StorytellerBook(
                    book = book.toUiModel(),
                    isFavorite = book.uuid in favoriteUuids,
                )
            }
            .sortedByDescending { it.isFavorite }

        val localBooks = importedBooks.map { book ->
            BookListItem.LocalBook(book = book.toUiModel())
        }

        // Local books first, then Storyteller books (with favorites at top)
        return localBooks + storytellerBooks
    }

    private fun importFile(fileBytes: ByteArray, fileName: String) {
        viewModelScope.launch {
            updateState { it.copy(isImporting = true) }
            fileImportManager.importEpubFile(fileBytes, fileName)
                .onSuccess {
                    analytics.logEvent(BookAnalyticsEvent.BookImported(fileName))
                    updateState { it.copy(isImporting = false) }
                }
                .onFailure { error ->
                    error.log(analytics, "BooksViewModel: Failed to import file")
                    updateState { it.copy(isImporting = false, error = error) }
                }
        }
    }

    private fun deleteImportedBook(bookUuid: String) {
        viewModelScope.launch {
            deleteImportedBookUseCase(bookUuid)
        }
    }
}

