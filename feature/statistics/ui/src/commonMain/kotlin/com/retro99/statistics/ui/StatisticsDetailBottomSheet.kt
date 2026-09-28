package com.retro99.statistics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.CoilImage
import com.retro99.base.ui.compose.stringTextWrapper
import com.retro99.statistics.domain.model.StatisticsPeriod
import com.retro99.statistics.ui.model.BookReadingStatsUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.statistics_detail_month_title
import resources.translations.statistics_detail_no_books
import resources.translations.statistics_detail_session
import resources.translations.statistics_detail_sessions
import resources.translations.statistics_detail_today_title
import resources.translations.statistics_detail_total_title
import resources.translations.statistics_detail_week_title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsDetailBottomSheet(
    detailState: StatisticsDetailState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = getTitleForPeriod(detailState.period),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            when {
                detailState.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                detailState.error != null || detailState.isCancelled -> {
                    StatisticsDetailLoadErrorContent(onRetry = onRetry)
                }
                detailState.books.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(StringRes.statistics_detail_no_books),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(detailState.books) { index, book ->
                            BookStatItem(book = book)
                            if (index < detailState.books.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookStatItem(
    book: BookReadingStatsUiModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (book.coverUrl != null) {
            CoilImage(
                data = book.coverUrl,
                cacheKey = "${book.bookUuid}_stats",
                modifier = Modifier
                    .width(40.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop,
                contentDescription = book.bookTitle,
            )
        } else {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = book.bookTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = getSessionsText(book.sessionCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringTextWrapper(book.totalTimeFormatted),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun getTitleForPeriod(period: StatisticsPeriod): String {
    return when (period) {
        StatisticsPeriod.TODAY -> stringResource(StringRes.statistics_detail_today_title)
        StatisticsPeriod.WEEK -> stringResource(StringRes.statistics_detail_week_title)
        StatisticsPeriod.MONTH -> stringResource(StringRes.statistics_detail_month_title)
        StatisticsPeriod.TOTAL -> stringResource(StringRes.statistics_detail_total_title)
    }
}

@Composable
private fun getSessionsText(sessionCount: Long): String {
    return if (sessionCount == 1L) {
        stringResource(StringRes.statistics_detail_session)
    } else {
        stringResource(StringRes.statistics_detail_sessions, sessionCount)
    }
}
