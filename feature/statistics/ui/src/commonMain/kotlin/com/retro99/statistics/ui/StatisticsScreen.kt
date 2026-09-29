package com.retro99.statistics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberCard
import com.retro99.statistics.domain.model.StatisticsOverview
import com.retro99.statistics.domain.model.StatisticsPeriod
import com.retro99.statistics.domain.model.StatisticsRange
import com.retro99.statistics.ui.model.ReadingStatisticsUiModel
import com.retro99.translations.StringRes
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_back
import resources.translations.general_retry
import resources.translations.statistics_day_singular
import resources.translations.statistics_days
import resources.translations.statistics_glance_all_time
import resources.translations.statistics_glance_avg_session
import resources.translations.statistics_glance_books_read
import resources.translations.statistics_glance_sessions
import resources.translations.statistics_glance_this_month
import resources.translations.statistics_glance_today
import resources.translations.statistics_heading
import resources.translations.statistics_load_failed
import resources.translations.statistics_range_all_time
import resources.translations.statistics_range_all_time_since
import resources.translations.statistics_range_month
import resources.translations.statistics_range_no_reading
import resources.translations.statistics_range_this_week
import resources.translations.statistics_range_week
import resources.translations.statistics_range_year
import resources.translations.statistics_reading_time
import resources.translations.statistics_session_count
import resources.translations.statistics_session_count_one
import resources.translations.statistics_streak_card_subtitle
import resources.translations.statistics_streak_helper_done
import resources.translations.statistics_streak_helper_empty
import resources.translations.statistics_streak_helper_keep
import resources.translations.statistics_streak_helper_start

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

@Composable
private fun StatisticsScreenContent(
    viewState: StatisticsViewState,
    intentDispatcher: IntentDispatcher<StatisticsIntent>,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
) {
    Scaffold(
        modifier = modifier,
        containerColor = Ember.colors.bg,
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = viewState.isLoading && viewState.statistics != null,
            onRefresh = { intentDispatcher(StatisticsIntent.OnRefresh) },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            val stats = viewState.statistics
            when {
                stats != null -> StatisticsContent(
                    viewState = viewState,
                    stats = stats,
                    showBack = showBack,
                    intentDispatcher = intentDispatcher,
                )

                viewState.error != null -> StatisticsLoadErrorContent(
                    onRetry = { intentDispatcher(StatisticsIntent.OnRefresh) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        viewState.detailState?.let { detailState ->
            StatisticsDetailBottomSheet(
                detailState = detailState,
                onDismiss = { intentDispatcher(StatisticsIntent.OnDismissDetail) },
                onRetry = { intentDispatcher(StatisticsIntent.OnRetryDetail) },
            )
        }

        val overview = viewState.overview
        val streakSheetState = viewState.streakSheetState
        if (overview != null && streakSheetState != null) {
            StreakDetailBottomSheet(
                overview = overview,
                calendarMonth = streakSheetState.calendarMonth,
                onMonthShifted = { months ->
                    intentDispatcher(StatisticsIntent.OnStreakMonthShifted(months))
                },
                onDismiss = { intentDispatcher(StatisticsIntent.OnDismissDetail) },
            )
        }

        viewState.booksReadDetailState?.let { booksReadDetailState ->
            BooksReadDetailBottomSheet(
                booksReadDetailState = booksReadDetailState,
                onDismiss = { intentDispatcher(StatisticsIntent.OnDismissDetail) },
                onRetry = { intentDispatcher(StatisticsIntent.OnRetryDetail) },
            )
        }

        viewState.sessionsDetailState?.let { sessionsDetailState ->
            SessionsDetailBottomSheet(
                sessionsDetailState = sessionsDetailState,
                onDismiss = { intentDispatcher(StatisticsIntent.OnDismissDetail) },
                onRetry = { intentDispatcher(StatisticsIntent.OnRetryDetail) },
            )
        }
    }
}

@Composable
private fun StatisticsContent(
    viewState: StatisticsViewState,
    stats: ReadingStatisticsUiModel,
    showBack: Boolean,
    intentDispatcher: IntentDispatcher<StatisticsIntent>,
    modifier: Modifier = Modifier,
) {
    val overview = viewState.overview

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            StatisticsHeader(
                showBack = showBack,
                onBack = { intentDispatcher(StatisticsIntent.OnBackClicked) },
            )
        }

        if (viewState.error != null) {
            item {
                StatisticsLoadErrorContent(
                    onRetry = { intentDispatcher(StatisticsIntent.OnRefresh) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            PeriodSwitch(
                selected = viewState.range,
                onSelected = { range -> intentDispatcher(StatisticsIntent.OnRangeSelected(range)) },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        if (overview != null) {
            item {
                ReadingTimeCard(
                    overview = overview,
                    selectedBucketIndex = viewState.selectedBucketIndex,
                    onBucketSelected = { index ->
                        intentDispatcher(StatisticsIntent.OnBucketSelected(index))
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            item {
                StreakCard(
                    overview = overview,
                    onClick = { intentDispatcher(StatisticsIntent.OnCurrentStreakClicked) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            item {
                GlanceCard(
                    overview = overview,
                    booksRead = stats.totalBooksRead,
                    intentDispatcher = intentDispatcher,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            item {
                ReadingRhythmCard(
                    rhythm = overview.rhythm,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun StatisticsHeader(
    showBack: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (showBack) 12.dp else 24.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(StringRes.general_back),
                    tint = Ember.colors.ink,
                )
            }
        }
        Text(
            text = stringResource(StringRes.statistics_heading),
            style = Ember.type.screenTitle,
            color = Ember.colors.ink,
        )
    }
}

@Composable
private fun PeriodSwitch(
    selected: StatisticsRange,
    onSelected: (StatisticsRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.chip)
            .border(style.border, colors.chipBorder, CircleShape)
            .padding(3.dp),
    ) {
        StatisticsRange.entries.forEach { range ->
            val isSelected = range == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.accent else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelected(range) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(range.labelRes()),
                    style = Ember.type.meta.copy(
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = if (isSelected) colors.onAccent else colors.ink2,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun StatisticsRange.labelRes(): StringResource = when (this) {
    StatisticsRange.WEEK -> StringRes.statistics_range_week
    StatisticsRange.MONTH -> StringRes.statistics_range_month
    StatisticsRange.YEAR -> StringRes.statistics_range_year
    StatisticsRange.ALL_TIME -> StringRes.statistics_range_all_time
}

@Composable
private fun ReadingTimeCard(
    overview: StatisticsOverview,
    selectedBucketIndex: Int?,
    onBucketSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val type = Ember.type
    val hasNoReading = overview.totalSessions == 0

    EmberCard(modifier = modifier) {
        Text(
            text = stringResource(StringRes.statistics_reading_time).uppercase(),
            style = type.eyebrow.copy(fontSize = if (Ember.style.isEink) 13.sp else 11.sp),
            color = colors.accentText,
        )
        Row(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = formatReadingTime(overview.totalMs),
                style = type.screenTitle.copy(fontSize = 46.sp, lineHeight = 52.sp),
                color = colors.ink,
                modifier = Modifier.alignByBaseline(),
            )
            if (!hasNoReading) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (overview.sessionCount == 1) {
                        stringResource(StringRes.statistics_session_count_one)
                    } else {
                        stringResource(StringRes.statistics_session_count, overview.sessionCount)
                    },
                    style = type.meta.copy(fontSize = 14.sp),
                    color = colors.ink2,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Text(
            text = if (hasNoReading) {
                stringResource(StringRes.statistics_range_no_reading)
            } else {
                rangeLabel(overview)
            },
            style = type.meta.copy(fontSize = 14.sp),
            color = colors.ink2,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (overview.range != StatisticsRange.ALL_TIME) {
            ReadingBarChart(
                overview = overview,
                selectedIndex = selectedBucketIndex,
                onSelect = onBucketSelected,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}

@Composable
private fun rangeLabel(overview: StatisticsOverview): String {
    val start = overview.rangeStart
    val end = overview.rangeEnd
    return when (overview.range) {
        StatisticsRange.WEEK -> if (start != null && end != null) {
            stringResource(StringRes.statistics_range_this_week, start.shortLabel(), end.shortLabel())
        } else {
            ""
        }

        StatisticsRange.MONTH -> start?.let { "${it.month.fullName()} ${it.year}" }.orEmpty()
        StatisticsRange.YEAR -> start?.year?.toString().orEmpty()
        StatisticsRange.ALL_TIME -> overview.firstSessionDate?.let { first ->
            stringResource(StringRes.statistics_range_all_time_since, "${first.shortLabel()}, ${first.year}")
        } ?: stringResource(StringRes.statistics_range_all_time)
    }
}

@Composable
private fun StreakCard(
    overview: StatisticsOverview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val type = Ember.type
    val readToday = overview.today in overview.readDays
    val helper = when {
        overview.totalSessions == 0 -> stringResource(StringRes.statistics_streak_helper_empty)
        readToday -> stringResource(StringRes.statistics_streak_helper_done, overview.currentStreak)
        overview.currentStreak > 0 -> {
            stringResource(StringRes.statistics_streak_helper_keep, overview.currentStreak)
        }

        else -> stringResource(StringRes.statistics_streak_helper_start)
    }

    EmberCard(modifier = modifier, onClick = onClick, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.navActive),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocalFireDepartment,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (style.isEink) colors.onAccent else colors.accentText,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dayCount(overview.currentStreak),
                    style = type.cardTitle.copy(fontSize = 24.sp, lineHeight = 28.sp),
                    color = colors.ink,
                )
                Text(
                    text = stringResource(
                        StringRes.statistics_streak_card_subtitle,
                        dayCount(overview.longestStreak),
                    ),
                    style = type.meta,
                    color = colors.ink2,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.ink2,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            repeat(7) { offset ->
                val day = overview.currentWeekStart.plus(offset, DateTimeUnit.DAY)
                WeekStripDay(
                    initial = day.dayOfWeek.initial(),
                    isRead = day in overview.readDays,
                    isToday = day == overview.today,
                    isFuture = day > overview.today,
                )
            }
        }

        Text(
            text = helper,
            style = type.meta,
            color = colors.ink2,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun WeekStripDay(
    initial: String,
    isRead: Boolean,
    isToday: Boolean,
    isFuture: Boolean,
) {
    val colors = Ember.colors
    val style = Ember.style
    val ring = when {
        isToday -> Modifier.border(2.dp, colors.accent, CircleShape)
        !isFuture && !isRead && style.isEink -> Modifier.border(style.border, colors.line, CircleShape)
        else -> Modifier
    }
    val fill = when {
        isRead -> colors.accent
        isFuture -> Color.Transparent
        else -> colors.track
    }
    val dashed = if (isFuture) {
        Modifier.drawBehind {
            drawCircle(
                color = colors.ink2,
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                ),
            )
        }
    } else {
        Modifier
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(fill)
                .then(ring)
                .then(dashed),
            contentAlignment = Alignment.Center,
        ) {
            if (isRead) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.onAccent,
                )
            }
        }
        Text(
            text = initial,
            style = Ember.type.meta.copy(
                fontSize = 12.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (isToday) colors.ink else colors.ink2,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun GlanceCard(
    overview: StatisticsOverview,
    booksRead: Long,
    intentDispatcher: IntentDispatcher<StatisticsIntent>,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style

    EmberCard(modifier = modifier, contentPadding = 0.dp) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            GlanceCell(
                value = formatReadingTime(overview.todayMs),
                label = stringResource(StringRes.statistics_glance_today),
                onClick = { intentDispatcher(StatisticsIntent.OnPeriodClicked(StatisticsPeriod.TODAY)) },
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(thickness = style.border, color = colors.line)
            GlanceCell(
                value = formatReadingTime(overview.monthMs),
                label = stringResource(StringRes.statistics_glance_this_month),
                onClick = { intentDispatcher(StatisticsIntent.OnPeriodClicked(StatisticsPeriod.MONTH)) },
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(thickness = style.border, color = colors.line)
            GlanceCell(
                value = formatReadingTime(overview.allTimeMs),
                label = stringResource(StringRes.statistics_glance_all_time),
                onClick = { intentDispatcher(StatisticsIntent.OnPeriodClicked(StatisticsPeriod.TOTAL)) },
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(thickness = style.border, color = colors.line)
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            GlanceCell(
                value = booksRead.toString(),
                label = stringResource(StringRes.statistics_glance_books_read),
                onClick = { intentDispatcher(StatisticsIntent.OnBooksReadClicked) },
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(thickness = style.border, color = colors.line)
            GlanceCell(
                value = overview.totalSessions.toString(),
                label = stringResource(StringRes.statistics_glance_sessions),
                onClick = { intentDispatcher(StatisticsIntent.OnTotalSessionsClicked) },
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(thickness = style.border, color = colors.line)
            GlanceCell(
                value = formatReadingTime(overview.averageSessionMs),
                label = stringResource(StringRes.statistics_glance_avg_session),
                onClick = null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GlanceCell(
    value: String,
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val click = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .then(click)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = value,
            style = Ember.type.cardTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
            color = Ember.colors.ink,
            maxLines = 1,
        )
        Text(
            text = label,
            style = Ember.type.meta.copy(fontSize = 12.sp),
            color = Ember.colors.ink2,
            maxLines = 1,
        )
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
            style = Ember.type.meta.copy(fontSize = 16.sp),
            color = Ember.colors.ink,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(StringRes.general_retry), color = Ember.colors.accentText)
        }
    }
}

/** "1 day" instead of "1 days" when the count is a single day. */
@Composable
internal fun dayCount(count: Int): String = stringResource(
    if (count == 1) StringRes.statistics_day_singular else StringRes.statistics_days,
    count,
)
