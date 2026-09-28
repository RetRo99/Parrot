package com.retro99.statistics.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
import com.retro99.base.ui.compose.TextWrapper
import com.retro99.base.ui.compose.stringTextWrapper
import com.retro99.statistics.domain.model.StatisticsPeriod
import com.retro99.statistics.ui.model.ReadingStatisticsUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_back
import resources.translations.general_retry
import resources.translations.statistics_books_read
import resources.translations.statistics_current_streak
import resources.translations.statistics_day_singular
import resources.translations.statistics_days
import resources.translations.statistics_empty_hint
import resources.translations.statistics_longest_streak
import resources.translations.statistics_load_failed
import resources.translations.statistics_month
import resources.translations.statistics_title
import resources.translations.statistics_today
import resources.translations.statistics_total_sessions
import resources.translations.statistics_total_time
import resources.translations.statistics_week

@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    viewModel: StatisticsViewModel = koinViewModel { parametersOf(onBack) },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        when {
            viewState.isLoading && viewState.statistics == null -> LoadingScreen()
            else -> StatisticsScreenContent(
                viewState = viewState,
                intentDispatcher = intentDispatcher,
                showBack = showBack,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatisticsScreenContent(
    viewState: StatisticsViewState,
    intentDispatcher: IntentDispatcher<StatisticsIntent>,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(StringRes.statistics_title)) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = { intentDispatcher(StatisticsIntent.OnBackClicked) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(StringRes.general_back),
                            )
                        }
                    }
                },
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = viewState.isLoading && viewState.statistics != null,
            onRefresh = { intentDispatcher(StatisticsIntent.OnRefresh) },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when {
                viewState.statistics != null -> {
                    StatisticsContent(
                        stats = viewState.statistics,
                        hasLoadError = viewState.error != null,
                        onRetry = { intentDispatcher(StatisticsIntent.OnRefresh) },
                        onPeriodClick = { period ->
                            intentDispatcher(StatisticsIntent.OnPeriodClicked(period))
                        },
                        onCurrentStreakClick = {
                            intentDispatcher(StatisticsIntent.OnCurrentStreakClicked)
                        },
                        onLongestStreakClick = {
                            intentDispatcher(StatisticsIntent.OnLongestStreakClicked)
                        },
                        onBooksReadClick = {
                            intentDispatcher(StatisticsIntent.OnBooksReadClicked)
                        },
                        onTotalSessionsClick = {
                            intentDispatcher(StatisticsIntent.OnTotalSessionsClicked)
                        },
                    )
                }

                viewState.error != null -> StatisticsLoadErrorContent(
                    onRetry = { intentDispatcher(StatisticsIntent.OnRefresh) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Show detail bottom sheet when detailState is not null
        viewState.detailState?.let { detailState ->
            StatisticsDetailBottomSheet(
                detailState = detailState,
                onDismiss = { intentDispatcher(StatisticsIntent.OnDismissDetail) },
            )
        }

        // Show streak detail bottom sheet when streakDetailState is not null
        viewState.streakDetailState?.let { streakDetailState ->
            StreakDetailBottomSheet(
                streakDetailState = streakDetailState,
                onDismiss = { intentDispatcher(StatisticsIntent.OnDismissDetail) },
            )
        }

        // Show books read detail bottom sheet when booksReadDetailState is not null
        viewState.booksReadDetailState?.let { booksReadDetailState ->
            BooksReadDetailBottomSheet(
                booksReadDetailState = booksReadDetailState,
                onDismiss = { intentDispatcher(StatisticsIntent.OnDismissDetail) },
            )
        }

        // Show sessions detail bottom sheet when sessionsDetailState is not null
        viewState.sessionsDetailState?.let { sessionsDetailState ->
            SessionsDetailBottomSheet(
                sessionsDetailState = sessionsDetailState,
                onDismiss = { intentDispatcher(StatisticsIntent.OnDismissDetail) },
            )
        }
    }
}

@Composable
private fun StatisticsContent(
    stats: ReadingStatisticsUiModel,
    hasLoadError: Boolean,
    onRetry: () -> Unit,
    onPeriodClick: (StatisticsPeriod) -> Unit,
    onCurrentStreakClick: () -> Unit,
    onLongestStreakClick: () -> Unit,
    onBooksReadClick: () -> Unit,
    onTotalSessionsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (hasLoadError) {
            item {
                StatisticsLoadErrorContent(
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Nothing recorded yet: say so instead of showing a wall of zeros.
        if (stats.totalSessions == 0L && stats.totalBooksRead == 0L) {
            item {
                Text(
                    text = stringResource(StringRes.statistics_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
            }
        }

        // Time Statistics Section (the top app bar already carries the screen title)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    title = stringResource(StringRes.statistics_today),
                    value = stats.todayReadingTimeFormatted,
                    icon = Icons.Default.Schedule,
                    onClick = { onPeriodClick(StatisticsPeriod.TODAY) },
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = stringResource(StringRes.statistics_week),
                    value = stats.weekReadingTimeFormatted,
                    icon = Icons.Default.Schedule,
                    onClick = { onPeriodClick(StatisticsPeriod.WEEK) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    title = stringResource(StringRes.statistics_month),
                    value = stats.monthReadingTimeFormatted,
                    icon = Icons.Default.Schedule,
                    onClick = { onPeriodClick(StatisticsPeriod.MONTH) },
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = stringResource(StringRes.statistics_total_time),
                    value = stats.totalReadingTimeFormatted,
                    icon = Icons.Default.AutoStories,
                    onClick = { onPeriodClick(StatisticsPeriod.TOTAL) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Reading Stats Section
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    title = stringResource(StringRes.statistics_books_read),
                    value = TextWrapper.Text(stats.totalBooksRead.toString()),
                    icon = Icons.Default.MenuBook,
                    onClick = onBooksReadClick,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = stringResource(StringRes.statistics_total_sessions),
                    value = TextWrapper.Text(stats.totalSessions.toString()),
                    icon = Icons.Default.AutoStories,
                    onClick = onTotalSessionsClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Streak Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    title = stringResource(StringRes.statistics_current_streak),
                    value = dayCountResource(stats.currentStreak),
                    icon = Icons.Default.LocalFireDepartment,
                    onClick = onCurrentStreakClick,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = stringResource(StringRes.statistics_longest_streak),
                    value = dayCountResource(stats.longestStreak),
                    icon = Icons.Default.LocalFireDepartment,
                    onClick = onLongestStreakClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatisticsLoadErrorContent(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(StringRes.statistics_load_failed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(StringRes.general_retry))
        }
    }
}

/** "1 day" instead of "1 days" when the streak is a single day. */
private fun dayCountResource(count: Int): TextWrapper = TextWrapper.Resource(
    if (count == 1) StringRes.statistics_day_singular else StringRes.statistics_days,
    count,
)

@Composable
private fun StatCard(
    title: String,
    value: TextWrapper,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val cardShape = RoundedCornerShape(16.dp)
    val cardModifier = if (onClick != null) {
        modifier
            .clip(cardShape)
            .clickable(onClick = onClick)
    } else {
        modifier
    }

    Card(
        modifier = cardModifier,
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringTextWrapper(value),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
