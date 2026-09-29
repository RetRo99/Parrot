package com.retro99.statistics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.base.ui.platform.firstDayOfWeek
import com.retro99.statistics.domain.model.StatisticsOverview
import com.retro99.translations.StringRes
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringResource
import resources.translations.statistics_legend_read
import resources.translations.statistics_legend_today
import resources.translations.statistics_longest_streak
import resources.translations.statistics_streak_next_month
import resources.translations.statistics_streak_previous_month
import resources.translations.statistics_streak_sheet_title
import resources.translations.statistics_streak_tile_current
import resources.translations.statistics_streak_tile_longest

private const val DAYS_IN_WEEK = 7

/** Reading streak sheet: current and longest streak, and a month calendar of reading days. */
@Composable
fun StreakDetailBottomSheet(
    overview: StatisticsOverview,
    calendarMonth: LocalDate,
    onMonthShifted: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val firstDay = remember { firstDayOfWeek() }
    val currentMonth = LocalDate(overview.today.year, overview.today.month, 1)

    EmberBottomSheet(onDismiss = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(StringRes.statistics_streak_sheet_title),
                style = Ember.type.screenTitle.copy(fontSize = 26.sp),
                color = colors.ink,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StreakTile(
                    value = dayCount(overview.currentStreak),
                    label = stringResource(StringRes.statistics_streak_tile_current),
                    modifier = Modifier.weight(1f),
                )
                StreakTile(
                    value = dayCount(overview.longestStreak),
                    label = longestLabel(overview),
                    modifier = Modifier.weight(1f),
                )
            }

            MonthHeader(
                month = calendarMonth,
                canGoNext = calendarMonth < currentMonth,
                onShift = onMonthShifted,
            )

            MonthCalendar(
                month = calendarMonth,
                firstDay = firstDay,
                overview = overview,
            )

            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                LegendItem(
                    label = stringResource(StringRes.statistics_legend_read),
                    filled = true,
                )
                LegendItem(
                    label = stringResource(StringRes.statistics_legend_today),
                    filled = false,
                )
            }
        }
    }
}

@Composable
private fun longestLabel(overview: StatisticsOverview): String {
    val start = overview.longestStreakStart
    val end = overview.longestStreakEnd
    if (start == null || end == null) return stringResource(StringRes.statistics_longest_streak)
    val dates = when {
        start == end -> start.shortLabel()
        start.month == end.month && start.year == end.year -> "${start.shortLabel()} – ${end.dayOfMonth}"
        else -> "${start.shortLabel()} – ${end.shortLabel()}"
    }
    return stringResource(StringRes.statistics_streak_tile_longest, dates)
}

@Composable
private fun StreakTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val shape = RoundedCornerShape(16.dp)
    val outline = if (style.isEink) Modifier.border(style.border, colors.line, shape) else Modifier

    Column(
        modifier = modifier
            .then(outline)
            .clip(shape)
            .background(colors.bg)
            .padding(16.dp),
    ) {
        Text(
            text = value,
            style = Ember.type.cardTitle.copy(fontSize = 26.sp, lineHeight = 30.sp),
            color = colors.ink,
        )
        Text(
            text = label,
            style = Ember.type.meta,
            color = colors.ink2,
            maxLines = 2,
        )
    }
}

@Composable
private fun MonthHeader(
    month: LocalDate,
    canGoNext: Boolean,
    onShift: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonthButton(
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            description = stringResource(StringRes.statistics_streak_previous_month),
            enabled = true,
            onClick = { onShift(-1) },
        )
        Text(
            text = "${month.month.fullName()} ${month.year}",
            style = Ember.type.section,
            color = Ember.colors.ink,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        MonthButton(
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            description = stringResource(StringRes.statistics_streak_next_month),
            enabled = canGoNext,
            onClick = { onShift(1) },
        )
    }
}

@Composable
private fun MonthButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = Ember.colors
    val style = Ember.style

    Box(
        modifier = Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .border(style.border, colors.chipBorder, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = colors.ink,
        )
    }
}

@Composable
private fun MonthCalendar(
    month: LocalDate,
    firstDay: DayOfWeek,
    overview: StatisticsOverview,
) {
    val colors = Ember.colors
    val daysInMonth = (month.plus(1, DateTimeUnit.MONTH).toEpochDays() - month.toEpochDays()).toInt()
    val leadingBlanks = (month.dayOfWeek.isoDayNumber - firstDay.isoDayNumber + DAYS_IN_WEEK) %
        DAYS_IN_WEEK
    val weekdays = (0 until DAYS_IN_WEEK).map { offset ->
        DayOfWeek(((firstDay.isoDayNumber - 1 + offset) % DAYS_IN_WEEK) + 1)
    }

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            weekdays.forEach { weekday ->
                Text(
                    text = weekday.initial(),
                    style = Ember.type.label.copy(fontSize = 13.sp),
                    color = colors.ink2,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        val cellCount = leadingBlanks + daysInMonth
        val rows = (cellCount + DAYS_IN_WEEK - 1) / DAYS_IN_WEEK
        repeat(rows) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(DAYS_IN_WEEK) { column ->
                    val dayNumber = row * DAYS_IN_WEEK + column - leadingBlanks + 1
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (dayNumber in 1..daysInMonth) {
                            val day = LocalDate(month.year, month.month.number, dayNumber)
                            CalendarDay(
                                dayNumber = dayNumber,
                                isRead = day in overview.readDays,
                                isToday = day == overview.today,
                                isFuture = day > overview.today,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    dayNumber: Int,
    isRead: Boolean,
    isToday: Boolean,
    isFuture: Boolean,
) {
    val colors = Ember.colors

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (isRead) colors.accent else Color.Transparent)
            .then(if (isToday) Modifier.border(2.dp, colors.accent, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = dayNumber.toString(),
            style = Ember.type.meta.copy(
                fontSize = 15.sp,
                fontWeight = if (isRead || isToday) FontWeight.Bold else FontWeight.Normal,
            ),
            color = when {
                isRead -> colors.onAccent
                isFuture -> colors.ink2
                else -> colors.ink
            },
        )
    }
}

@Composable
private fun LegendItem(
    label: String,
    filled: Boolean,
) {
    val colors = Ember.colors

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .then(
                    if (filled) {
                        Modifier.background(colors.accent)
                    } else {
                        Modifier.border(2.dp, colors.accent, CircleShape)
                    },
                ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = Ember.type.meta, color = colors.ink)
    }
}
