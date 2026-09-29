package com.retro99.statistics.domain.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** Period shown by the statistics screen. Week, month and year are calendar periods. */
enum class StatisticsRange {
    WEEK,
    MONTH,
    YEAR,
    ALL_TIME,
}

/**
 * Reading time inside one chart bucket: a day for week and month, a calendar month for year.
 * [end] is inclusive.
 */
data class ReadingBucket(
    val start: LocalDate,
    val end: LocalDate,
    val durationMs: Long,
)

/**
 * Everything the statistics screen shows, computed in the device's time zone.
 * [rangeStart] and [rangeEnd] (inclusive) are null for [StatisticsRange.ALL_TIME], which
 * also has no [buckets].
 */
data class StatisticsOverview(
    val range: StatisticsRange,
    val rangeStart: LocalDate?,
    val rangeEnd: LocalDate?,
    val buckets: List<ReadingBucket>,
    val totalMs: Long,
    val sessionCount: Int,
    val firstSessionDate: LocalDate?,
    val today: LocalDate,
    val currentWeekStart: LocalDate,
    val todayMs: Long,
    val monthMs: Long,
    val allTimeMs: Long,
    val totalSessions: Int,
    val averageSessionMs: Long,
    val currentStreak: Int,
    val longestStreak: Int,
    val longestStreakStart: LocalDate?,
    val longestStreakEnd: LocalDate?,
    /** Every day with reading, in the device's time zone. */
    val readDays: Set<LocalDate>,
    val rhythm: ReadingRhythm,
)

/** Part of the day, in the device's time zone. */
enum class TimeOfDay(val startHour: Int, val endHour: Int) {
    MORNING(6, 12),
    AFTERNOON(12, 18),
    EVENING(18, 24),
    NIGHT(0, 6),
}

/** Average reading time on one weekday. */
data class WeekdayAverage(
    val dayOfWeek: DayOfWeek,
    val averageMs: Long,
)

/**
 * When the user reads, over the last [RHYTHM_WINDOW_DAYS] days: average time per weekday
 * (in locale week order) and the split of reading across the parts of the day.
 * [topWeekday] and [topTimeOfDay] are null until [hasEnoughData].
 */
data class ReadingRhythm(
    val weekdayAverages: List<WeekdayAverage>,
    val topWeekday: DayOfWeek?,
    val timeOfDayMs: Map<TimeOfDay, Long>,
    val topTimeOfDay: TimeOfDay?,
    val readDaysInWindow: Int,
) {
    val hasEnoughData: Boolean get() = readDaysInWindow >= MIN_READ_DAYS_FOR_RHYTHM
}

const val RHYTHM_WINDOW_DAYS = 90
const val MIN_READ_DAYS_FOR_RHYTHM = 7

