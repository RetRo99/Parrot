package com.retro99.statistics.domain.model

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
)
