package com.retro99.books.ui.series.detail

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.*
import com.retro99.base.ui.BaseViewModel
import com.retro99.base.result.AppError
import com.retro99.base.result.log
import com.retro99.books.domain.model.normalisedSeriesName
import com.retro99.books.domain.model.BookWithProgressDomainModel
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.toUiModel
import com.retro99.books.ui.series.SeriesFailureUiModel
import com.retro99.reader.domain.usecase.ObserveSeriesBrowseUseCase
import com.retro99.reader.domain.usecase.ObserveAllBooksWithProgressUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.catch
import org.koin.core.annotation.*

@KoinViewModel
class SeriesDetailViewModel(
    @InjectedParam private val seriesUuid: String,
    @InjectedParam private val seriesName: String,
    @InjectedParam private val onNavigateToBookDetail: (BookUiModel) -> Unit,
    @InjectedParam private val onBack: () -> Unit,
    @Provided private val observeBrowse: ObserveSeriesBrowseUseCase,
    @Provided private val observeProgress: ObserveAllBooksWithProgressUseCase,
    @Provided private val analytics: Analytics,
) : BaseViewModel<SeriesDetailViewState, SeriesDetailIntent>(SeriesDetailViewState(seriesUuid = seriesUuid, seriesName = seriesName)) {
    private var loadJob: Job? = null
    private var loadGeneration = 0
    init { load(false) }

    override fun onIntent(intent: SeriesDetailIntent) {
        when (intent) {
            SeriesDetailIntent.OnBackClicked -> onBack()
            SeriesDetailIntent.OnRefresh -> load(true)
            SeriesDetailIntent.OnSearchToggled -> updateState { it.copy(isSearchVisible = !it.isSearchVisible) }
            is SeriesDetailIntent.OnBookClicked -> {
                analytics.logFeatureUsage(FeatureUsageAnalyticsEvent.DiscoverySelected(DiscoveryRoute.Series, DiscoveryDestination.Book))
                onNavigateToBookDetail(intent.book)
            }
        }
    }

    private fun load(refresh: Boolean) {
        val generation = ++loadGeneration
        loadJob?.cancel()
        updateState { it.copy(isRefreshing = refresh, error = null) }
        val fetched = mutableSetOf<Pair<String, String>>()
        var refreshing = refresh
        loadJob = observeBrowse().onEach { snapshot ->
            val series = snapshot.series.find { normalisedSeriesName(it.name) == normalisedSeriesName(seriesName) }
            updateState { it.copy(
                rows = series?.books.orEmpty().map { row -> SeriesDetailRow(row.key, row.book.toUiModel(), row.position, row.progress) },
                failedSources = snapshot.failures.map { SeriesFailureUiModel(it.serverId, snapshot.sourceNames[it.serverId] ?: "server") },
                finishedCount = series?.finishedCount ?: 0,
                inProgressCount = series?.inProgressCount ?: 0,
                progress = series?.progress ?: 0.0,
                isLoading = false, isRefreshing = refreshing,
            ) }
            val pending = series?.books.orEmpty().map { it.book }
                .distinctBy { it.serverId to it.uuid }.filter { fetched.add(it.serverId to it.uuid) }
            if (pending.isNotEmpty() || refreshing) {
                try {
                    observeProgress.fetchRemoteProgress(pending.map { BookWithProgressDomainModel(it, null) })
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    val error = AppError.UnknownError(failure).log(analytics, "Series progress refresh failed")
                    if (generation == loadGeneration) updateState { it.copy(error = error) }
                } finally {
                    refreshing = false
                    if (generation == loadGeneration) updateState { it.copy(isRefreshing = false) }
                }
            }
        }.catch { failure ->
            val error = AppError.UnknownError(failure).log(analytics, "Series detail load failed")
            updateState { it.copy(error = error, isLoading = false, isRefreshing = false) }
        }.launchIn(viewModelScope)
    }
}
