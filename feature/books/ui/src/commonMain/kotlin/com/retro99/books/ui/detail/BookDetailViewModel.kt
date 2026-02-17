package com.retro99.books.ui.detail

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.BookAnalyticsEvent
import com.retro99.base.result.log
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.usecase.GetBookByUuidUseCase
import com.retro99.books.domain.usecase.ObserveFavoriteUseCase
import com.retro99.books.domain.usecase.ToggleFavoriteUseCase
import com.retro99.books.ui.model.toUiModel
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.DownloadState
import com.retro99.reader.domain.usecase.CancelDownloadUseCase
import com.retro99.reader.domain.usecase.DeleteMediaCacheUseCase
import com.retro99.reader.domain.usecase.DownloadMediaUseCase
import com.retro99.reader.domain.usecase.ObserveDownloadStateUseCase
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class BookDetailViewModel(
    @InjectedParam private val bookUuid: String,
    @InjectedParam private val onNavigateToReader: (bookUuid: String, bookType: BookType) -> Unit,
    @InjectedParam private val onNavigateToSeriesDetail: (seriesUuid: String, seriesName: String) -> Unit,
    @InjectedParam private val onBack: () -> Unit,
    @Provided private val getBookByUuidUseCase: GetBookByUuidUseCase,
    @Provided private val downloadMediaUseCase: DownloadMediaUseCase,
    @Provided private val cancelDownloadUseCase: CancelDownloadUseCase,
    @Provided private val observeDownloadStateUseCase: ObserveDownloadStateUseCase,
    @Provided private val deleteMediaCacheUseCase: DeleteMediaCacheUseCase,
    @Provided private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    @Provided private val observeFavoriteUseCase: ObserveFavoriteUseCase,
    @Provided private val analytics: Analytics,
) : BaseViewModel<BookDetailViewState, BookDetailIntent>(
    BookDetailViewState(),
) {

    init {
        analytics.logEvent(
            BookAnalyticsEvent.BookDetailViewed(
                bookUuid = bookUuid,
                source = "direct",
            )
        )
        fetchBook()
        observeDownloadStates()
        observeFavoriteState()
    }

    override fun onIntent(intent: BookDetailIntent) {
        when (intent) {
            BookDetailIntent.OnBackClicked -> {
                onBack()
            }

            BookDetailIntent.OnRetryClicked -> {
                fetchBook()
            }

            is BookDetailIntent.OnTagClicked -> {
                // TODO: Navigate to tag filter
            }

            is BookDetailIntent.OnSeriesClicked -> {
                val series = viewState.value.book?.series?.find { it.uuid == intent.seriesUuid }
                if (series != null) {
                    onNavigateToSeriesDetail(series.uuid, series.name)
                }
            }

            BookDetailIntent.OnReadEbookClicked -> {
                handleReadClick(BookType.EBOOK)
            }

            BookDetailIntent.OnPlayAudiobookClicked -> {
                handleReadClick(BookType.AUDIOBOOK)
            }

            BookDetailIntent.OnReadReadaloudClicked -> {
                handleReadClick(BookType.READALOUD)
            }

            is BookDetailIntent.OnDownloadClicked -> {
                toggleDownload(intent.bookType)
            }

            is BookDetailIntent.OnDeleteCacheClicked -> {
                updateState { it.copy(deleteConfirmationBookType = intent.bookType) }
            }

            BookDetailIntent.OnDeleteCacheConfirmed -> {
                viewState.value.deleteConfirmationBookType?.let { bookType ->
                    deleteMediaCache(bookType)
                }
                updateState { it.copy(deleteConfirmationBookType = null) }
            }

            BookDetailIntent.OnDeleteCacheDismissed -> {
                updateState { it.copy(deleteConfirmationBookType = null) }
            }

            BookDetailIntent.OnFavoriteClicked -> {
                toggleFavorite()
            }
        }
    }

    private fun toggleFavorite() {
        val currentIsFavorite = viewState.value.isFavorite
        // Log the new state (opposite of current)
        analytics.logEvent(
            BookAnalyticsEvent.FavoriteToggled(
                bookUuid = bookUuid,
                isFavorite = !currentIsFavorite,
                source = "detail",
            )
        )
        viewModelScope.launch {
            toggleFavoriteUseCase(bookUuid)
        }
    }

    private fun observeFavoriteState() {
        observeFavoriteUseCase(bookUuid)
            .onEach { isFavorite ->
                updateState { it.copy(isFavorite = isFavorite) }
            }
            .launchIn(viewModelScope)
    }

    private fun handleReadClick(bookType: BookType) {
        analytics.logEvent(
            BookAnalyticsEvent.ReadButtonClicked(
                bookUuid = bookUuid,
                bookType = bookType.name.lowercase(),
            )
        )

        val currentState = viewState.value
        val downloadState = when (bookType) {
            BookType.EBOOK -> currentState.ebookDownloadState
            BookType.AUDIOBOOK -> currentState.audiobookDownloadState
            BookType.READALOUD -> currentState.readaloudDownloadState
            BookType.IMPORTED -> DownloadState.Cached // Imported books are always local
        }

        if (downloadState is DownloadState.Cached) {
            onNavigateToReader(bookUuid, bookType)
        }
        // If not cached, user should click download first
    }

    private fun fetchBook() {
        getBookByUuidUseCase(bookUuid)
            .onStart {
                updateState { it.copy(isLoading = true, error = null) }
            }
            .onEach { result ->
                result
                    .onSuccess { book ->
                        updateState {
                            it.copy(
                                book = book.toUiModel(),
                                isLoading = false,
                                error = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        error.log(analytics, "BookDetailViewModel: Failed to load book details")
                        updateState {
                            it.copy(
                                isLoading = false,
                                error = error,
                            )
                        }
                    }
            }
            .launchIn(viewModelScope)
    }

    private fun observeDownloadStates() {
        combine(
            observeDownloadStateUseCase(bookUuid, BookType.EBOOK),
            observeDownloadStateUseCase(bookUuid, BookType.AUDIOBOOK),
            observeDownloadStateUseCase(bookUuid, BookType.READALOUD),
        ) { ebookState, audiobookState, readaloudState ->
            Triple(ebookState, audiobookState, readaloudState)
        }
            .onEach { (ebookState, audiobookState, readaloudState) ->
                val previousState = viewState.value
                trackDownloadStateChange(
                    BookType.EBOOK,
                    previousState.ebookDownloadState,
                    ebookState,
                )
                trackDownloadStateChange(
                    BookType.AUDIOBOOK,
                    previousState.audiobookDownloadState,
                    audiobookState,
                )
                trackDownloadStateChange(
                    BookType.READALOUD,
                    previousState.readaloudDownloadState,
                    readaloudState,
                )
                updateState {
                    it.copy(
                        ebookDownloadState = ebookState,
                        audiobookDownloadState = audiobookState,
                        readaloudDownloadState = readaloudState,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun trackDownloadStateChange(
        bookType: BookType,
        previousState: DownloadState,
        newState: DownloadState,
    ) {
        // Only track transitions from Downloading state
        if (previousState !is DownloadState.Downloading) return

        when (newState) {
            is DownloadState.Cached -> {
                analytics.logEvent(
                    BookAnalyticsEvent.BookDownloadCompleted(
                        bookUuid = bookUuid,
                        downloadDurationMs = 0L, // Duration not tracked at this level
                    )
                )
            }

            is DownloadState.Failed -> {
                analytics.logEvent(
                    BookAnalyticsEvent.BookDownloadFailed(
                        bookUuid = bookUuid,
                        errorType = newState.error.message ?: "unknown",
                    )
                )
            }

            else -> { /* No tracking needed for other transitions */
            }
        }
    }

    private fun toggleDownload(bookType: BookType) {
        val book = viewState.value.book ?: return

        // Imported books don't need download management
        if (bookType == BookType.IMPORTED) return

        val currentState = when (bookType) {
            BookType.EBOOK -> viewState.value.ebookDownloadState
            BookType.AUDIOBOOK -> viewState.value.audiobookDownloadState
            BookType.READALOUD -> viewState.value.readaloudDownloadState
            BookType.IMPORTED -> return // Already handled above
        }

        // If currently downloading, cancel it
        if (currentState is DownloadState.Downloading) {
            analytics.logEvent(
                BookAnalyticsEvent.BookDownloadCancelled(bookUuid = bookUuid)
            )
            viewModelScope.launch {
                cancelDownloadUseCase(bookUuid, bookType)
            }
            return
        }

        // Otherwise, start the download
        val filePath = when (bookType) {
            BookType.EBOOK -> book.ebookFilepath
            BookType.AUDIOBOOK -> book.audiobookFilepath
            BookType.READALOUD -> book.readaloudFilepath
            BookType.IMPORTED -> return // Imported books are already local
        } ?: return

        analytics.logEvent(
            BookAnalyticsEvent.BookDownloadStarted(
                bookUuid = bookUuid,
                bookType = bookType.name.lowercase(),
            )
        )
        viewModelScope.launch {
            downloadMediaUseCase(bookUuid, bookType, filePath, book.title)
        }
    }

    private fun deleteMediaCache(bookType: BookType) {
        analytics.logEvent(
            BookAnalyticsEvent.BookCacheDeleted(
                bookUuid = bookUuid,
                bookType = bookType.name.lowercase(),
            )
        )
        viewModelScope.launch {
            deleteMediaCacheUseCase(bookUuid, bookType)
            // Download state will be updated via the observer
        }
    }
}

