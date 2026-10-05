package com.retro99.books.ui.series

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.*
import com.retro99.base.ui.BaseViewModel
import com.retro99.base.result.AppError
import com.retro99.base.result.log
import com.retro99.books.ui.series.model.SeriesListUiModel
import com.retro99.books.ui.series.model.toListUiModel
import com.retro99.books.domain.model.BookWithProgressDomainModel
import com.retro99.reader.domain.usecase.ObserveSeriesBrowseUseCase
import com.retro99.reader.domain.usecase.ObserveAllBooksWithProgressUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.catch
import org.koin.core.annotation.*

@KoinViewModel
class SeriesListViewModel(
    @InjectedParam private val onNavigateToSeriesDetail: (SeriesListUiModel) -> Unit,
    @Provided private val observeBrowse: ObserveSeriesBrowseUseCase,
    @Provided private val observeProgress: ObserveAllBooksWithProgressUseCase,
    @Provided private val analytics: Analytics,
) : BaseViewModel<SeriesListViewState, SeriesListIntent>(SeriesListViewState()) {
    private var loadJob: Job? = null
    private var loadGeneration = 0
    init { load(false) }

    override fun onIntent(intent: SeriesListIntent) {
        when (intent) {
            SeriesListIntent.OnScreenVisible -> analytics.logFeatureUsage(
                ProductAnalyticsEvent.FeatureExposed(UsageFeature.SeriesBrowsing, "series", true))
            SeriesListIntent.OnRefresh -> load(true)
            is SeriesListIntent.OnSeriesClicked -> {
                analytics.logFeatureUsage(FeatureUsageAnalyticsEvent.DiscoverySelected(DiscoveryRoute.Series, DiscoveryDestination.Series))
                onNavigateToSeriesDetail(intent.series)
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
            updateState { it.copy(series = snapshot.series.map { series -> series.toListUiModel() },
                failedSources = snapshot.failures.map { SeriesFailureUiModel(it.serverId, snapshot.sourceNames[it.serverId] ?: "server") },
                hasConnectedServer = snapshot.connectedServerIds.isNotEmpty(),
                isLoading = false, isRefreshing = refreshing) }
            val pending = snapshot.series.flatMap { it.books }.map { it.book }
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
            val error = AppError.UnknownError(failure).log(analytics, "Series browse load failed")
            updateState { it.copy(error = error, isLoading = false, isRefreshing = false) }
        }.launchIn(viewModelScope)
    }
}
