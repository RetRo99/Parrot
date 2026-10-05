package com.retro99.books.ui.series

import com.retro99.base.result.AppError
import com.retro99.books.ui.series.model.SeriesListUiModel

data class SeriesFailureUiModel(val serverId: String, val name: String)

data class SeriesListViewState(
    val series: List<SeriesListUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: AppError? = null,
    val failedSources: List<SeriesFailureUiModel> = emptyList(),
    val hasConnectedServer: Boolean = false,
)
