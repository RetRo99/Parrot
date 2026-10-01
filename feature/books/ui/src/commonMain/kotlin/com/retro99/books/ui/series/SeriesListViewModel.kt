package com.retro99.books.ui.series

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.base.result.log
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.usecase.GetBooksUseCase
import com.retro99.books.domain.usecase.GetSeriesUseCase
import com.retro99.books.ui.series.model.SeriesListUiModel
import com.retro99.books.ui.series.model.toListUiModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class SeriesListViewModel(
    @InjectedParam private val onNavigateToSeriesDetail: (series: SeriesListUiModel) -> Unit,
    @Provided private val getSeriesUseCase: GetSeriesUseCase,
    @Provided private val getBooksUseCase: GetBooksUseCase,
    @Provided private val analytics: Analytics,
) : BaseViewModel<SeriesListViewState, SeriesListIntent>(SeriesListViewState()) {

    init {
        observeSeriesWithBooks()
    }

    override fun onIntent(intent: SeriesListIntent) {
        when (intent) {
            SeriesListIntent.OnRefresh -> observeSeriesWithBooks()
            is SeriesListIntent.OnSeriesClicked -> onNavigateToSeriesDetail(intent.series)
        }
    }

    private fun observeSeriesWithBooks() {
        combine(
            getSeriesUseCase(),
            getBooksUseCase(groupLinked = false),
        ) { seriesResult, booksResult ->
            coroutineBinding {
                val seriesList = seriesResult.bind()
                val books = booksResult.bind()
                seriesList.map { series -> series.toListUiModel(books) }
            }
        }
            .onStart {
                updateState { it.copy(isLoading = true, error = null) }
            }
            .onEach { result ->
                result
                    .onSuccess { seriesUiModels ->
                        updateState {
                            it.copy(
                                series = seriesUiModels,
                                isLoading = false,
                                isRefreshing = false,
                                error = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        error.log(analytics, "SeriesListViewModel: Failed to load series")
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
