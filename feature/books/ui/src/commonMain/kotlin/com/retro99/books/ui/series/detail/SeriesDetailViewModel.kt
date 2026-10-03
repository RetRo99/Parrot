package com.retro99.books.ui.series.detail

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.FeatureUsageAnalyticsEvent
import com.retro99.analytics.api.DiscoveryRoute
import com.retro99.analytics.api.DiscoveryDestination
import com.retro99.analytics.api.logFeatureUsage
import com.retro99.analytics.api.BookAnalyticsEvent
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.base.result.log
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.usecase.GetBooksBySeriesUseCase
import com.retro99.books.domain.usecase.ObserveAllFavoritesUseCase
import com.retro99.books.domain.usecase.ToggleFavoriteUseCase
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.toUiModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class SeriesDetailViewModel(
    @InjectedParam private val seriesUuid: String,
    @InjectedParam private val seriesName: String,
    @InjectedParam private val onNavigateToBookDetail: (book: BookUiModel) -> Unit,
    @InjectedParam private val onBack: () -> Unit,
    @Provided private val getBooksBySeriesUseCase: GetBooksBySeriesUseCase,
    @Provided private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    @Provided private val observeAllFavoritesUseCase: ObserveAllFavoritesUseCase,
    @Provided private val analytics: Analytics,
) : BaseViewModel<SeriesDetailViewState, SeriesDetailIntent>(
    SeriesDetailViewState(
        seriesUuid = seriesUuid,
        seriesName = seriesName,
    ),
) {

    val searchFieldState = TextFieldState()

    init {
        observeBooks()
        observeFavorites()
        observeSearchQuery()
    }

    override fun onIntent(intent: SeriesDetailIntent) {
        when (intent) {
            SeriesDetailIntent.OnBackClicked -> onBack()
            SeriesDetailIntent.OnRefresh -> observeBooks()
            SeriesDetailIntent.OnSearchToggled -> toggleSearch()
            is SeriesDetailIntent.OnBookClicked -> {
                analytics.logFeatureUsage(FeatureUsageAnalyticsEvent.DiscoverySelected(DiscoveryRoute.Series, DiscoveryDestination.Book))
                onNavigateToBookDetail(intent.book)
            }
            is SeriesDetailIntent.OnFavoriteClicked -> toggleFavorite(intent.bookUuid)
        }
    }

    private fun toggleSearch() {
        val currentlyVisible = viewState.value.isSearchVisible
        // Only track when opening search, not closing
        if (!currentlyVisible) {
            analytics.logEvent(NavigationAnalyticsEvent.SearchOpened(source = "series_detail"))
        }
        if (currentlyVisible) {
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
        val currentIsFavorite = viewState.value.favoriteBookUuids.contains(bookUuid)
        analytics.logEvent(
            BookAnalyticsEvent.FavoriteToggled(
                bookUuid = bookUuid,
                isFavorite = !currentIsFavorite,
                source = "series_detail",
            ),
        )
        viewModelScope.launch {
            toggleFavoriteUseCase(bookUuid)
        }
    }

    private fun observeFavorites() {
        observeAllFavoritesUseCase()
            .onEach { favoriteUuids ->
                updateState { it.copy(favoriteBookUuids = favoriteUuids) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeBooks() {
        getBooksBySeriesUseCase(seriesName)
            .onStart {
                updateState { it.copy(isLoading = true, error = null) }
            }
            .onEach { result ->
                result
                    .onSuccess { books ->
                        updateState {
                            it.copy(
                                books = books.map { book -> book.toUiModel() },
                                isLoading = false,
                                isRefreshing = false,
                                error = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        error.log(analytics, "SeriesDetailViewModel: Failed to load series books")
                        updateState {
                            it.copy(
                                isLoading = false,
                                isRefreshing = false,
                                error = error,
                            )
                        }
                    }
            }
            .launchIn(viewModelScope)
    }
}
