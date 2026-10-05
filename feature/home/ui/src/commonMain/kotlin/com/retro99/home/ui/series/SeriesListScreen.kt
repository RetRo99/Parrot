package com.retro99.home.ui.series

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retro99.books.ui.series.model.SeriesListUiModel
import com.retro99.books.ui.series.SeriesListScreen as BooksSeriesListScreen

/**
 * Series list screen that delegates to the books module implementation.
 */
@Composable
fun SeriesListScreen(
    onNavigateToSeriesDetail: (series: SeriesListUiModel) -> Unit = {},
    modifier: Modifier = Modifier,
    onConnectServer: () -> Unit = {},
) {
    BooksSeriesListScreen(
        onNavigateToSeriesDetail = onNavigateToSeriesDetail,
        modifier = modifier,
        onConnectServer = onConnectServer,
    )
}
