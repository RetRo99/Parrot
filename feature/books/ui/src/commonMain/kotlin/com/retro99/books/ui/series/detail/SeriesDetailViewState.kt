package com.retro99.books.ui.series.detail

import com.retro99.base.result.AppError
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.series.SeriesFailureUiModel

data class SeriesDetailRow(
    val key: String,
    val book: BookUiModel,
    val position: Double?,
    val progress: Double?,
)

data class SeriesDetailViewState(
    val seriesUuid: String = "",
    val seriesName: String = "",
    val isSearchVisible: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: AppError? = null,
    val rows: List<SeriesDetailRow> = emptyList(),
    val failedSources: List<SeriesFailureUiModel> = emptyList(),
    val finishedCount: Int = 0,
    val inProgressCount: Int = 0,
    val progress: Double = 0.0,
)
