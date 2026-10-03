package com.retro99.books.ui.series

import com.retro99.base.ui.BaseIntent
import com.retro99.books.ui.series.model.SeriesListUiModel

sealed interface SeriesListIntent : BaseIntent {
    data object OnScreenVisible : SeriesListIntent
    data object OnRefresh : SeriesListIntent
    data class OnSeriesClicked(val series: SeriesListUiModel) : SeriesListIntent
}
