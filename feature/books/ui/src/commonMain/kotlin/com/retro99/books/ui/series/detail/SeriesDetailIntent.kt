package com.retro99.books.ui.series.detail

import com.retro99.base.ui.BaseIntent
import com.retro99.books.ui.model.BookUiModel

sealed interface SeriesDetailIntent : BaseIntent {
    data object OnBackClicked : SeriesDetailIntent
    data object OnRefresh : SeriesDetailIntent
    data object OnSearchToggled : SeriesDetailIntent
    data class OnBookClicked(val book: BookUiModel) : SeriesDetailIntent
}
