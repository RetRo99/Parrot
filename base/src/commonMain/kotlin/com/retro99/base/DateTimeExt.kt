package com.retro99.base

import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

fun now() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

fun nowMillis() = Clock.System.now().toEpochMilliseconds()

/**
 * Formats the current time according to the user's locale preferences.
 * This respects the user's 12-hour (AM/PM) or 24-hour time format setting.
 *
 * @return A formatted time string (e.g., "14:35" or "2:35 PM" depending on locale)
 */
expect fun formatCurrentTime(): String

/** Native medium date style, respecting the current locale. */
expect fun formatMediumDate(year: Int, month: Int, day: Int): String

sealed interface CalendarDateLabel {
    data object Today : CalendarDateLabel
    data object Yesterday : CalendarDateLabel
    data class Medium(val text: String) : CalendarDateLabel
}

/** Calendar days in the local zone, not elapsed 24-hour periods (including DST). */
fun calendarDateLabel(
    raw: String?,
    nowEpochMillis: Long = nowMillis(),
    timeZoneId: String = TimeZone.currentSystemDefault().id,
): CalendarDateLabel? {
    val value = raw?.trim()?.takeIf { text -> text.isNotEmpty() } ?: return null
    val zone = TimeZone.of(timeZoneId)
    val date = runCatching {
        if (value.length == 10) LocalDate.parse(value)
        else runCatching { Instant.parse(value).toLocalDateTime(zone).date }
            .getOrElse { LocalDateTime.parse(value).date }
    }.getOrNull() ?: return null
    val today = Instant.fromEpochMilliseconds(nowEpochMillis).toLocalDateTime(zone).date
    return when (today.toEpochDays() - date.toEpochDays()) {
        0L -> CalendarDateLabel.Today
        1L -> CalendarDateLabel.Yesterday
        else -> CalendarDateLabel.Medium(formatMediumDate(date.year, date.monthNumber, date.dayOfMonth))
    }
}
