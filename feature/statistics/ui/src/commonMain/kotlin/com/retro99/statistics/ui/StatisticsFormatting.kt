package com.retro99.statistics.ui

import androidx.compose.runtime.Composable
import com.retro99.translations.StringRes
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import org.jetbrains.compose.resources.stringResource
import resources.translations.statistics_hours
import resources.translations.statistics_hours_minutes
import resources.translations.statistics_minutes

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L

/** "33m", "1h 12m" or "12h". */
@Composable
internal fun formatReadingTime(ms: Long): String {
    val totalMinutes = ms / MS_PER_MINUTE
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        hours > 0 && minutes > 0 -> stringResource(StringRes.statistics_hours_minutes, hours, minutes)
        hours > 0 -> stringResource(StringRes.statistics_hours, hours)
        else -> stringResource(StringRes.statistics_minutes, minutes)
    }
}

internal fun Month.shortName(): String = name.take(3).capitalized()

internal fun Month.fullName(): String = name.capitalized()

internal fun DayOfWeek.shortName(): String = name.take(3).capitalized()

internal fun DayOfWeek.fullName(): String = name.capitalized()

internal fun DayOfWeek.initial(): String = name.first().toString()

/** "Sep 27". */
internal fun LocalDate.shortLabel(): String = "${month.shortName()} $dayOfMonth"

/** "Sun, Sep 27". */
internal fun LocalDate.weekdayLabel(): String = "${dayOfWeek.shortName()}, ${shortLabel()}"

private fun String.capitalized(): String = lowercase().replaceFirstChar { char -> char.uppercase() }
