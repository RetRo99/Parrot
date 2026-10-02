package com.retro99.statistics.domain

import com.retro99.statistics.domain.model.DailyReadingTimeDomainModel
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import com.retro99.statistics.domain.model.ReadingStreakDomainModel
import com.retro99.statistics.domain.model.StatisticsRange
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Uses the same local start-day attribution as the dashboard, including across DST. */
object StatisticsSessionCalendar {
    fun dailyTime(
        sessions: List<ReadingSessionDomainModel>,
        since: Long,
        timeZone: TimeZone,
    ): List<DailyReadingTimeDomainModel> = sessions
        .filter { it.startTime >= since }
        .groupBy { Instant.fromEpochMilliseconds(it.startTime).toLocalDateTime(timeZone).date }
        .entries.sortedBy { it.key }
        .map { (day, sessionsOnDay) ->
            DailyReadingTimeDomainModel(
                day.atStartOfDayIn(timeZone).toEpochMilliseconds(),
                sessionsOnDay.sumOf { it.durationMs },
            )
        }

    fun streak(
        sessions: List<ReadingSessionDomainModel>,
        today: LocalDate,
        timeZone: TimeZone,
    ): ReadingStreakDomainModel {
        val overview = StatisticsOverviewCalculator.calculate(
            sessions, StatisticsRange.ALL_TIME, today, timeZone, DayOfWeek.MONDAY,
        )
        val lastDay = overview.readDays.maxOrNull()
        val currentEnd = when {
            today in overview.readDays -> today
            today.minus(1, DateTimeUnit.DAY) in overview.readDays -> today.minus(1, DateTimeUnit.DAY)
            else -> null
        }
        fun timestamps(start: LocalDate?, count: Int): List<Long> =
            if (start == null) emptyList() else (0 until count).map { offset ->
                start.plus(offset, DateTimeUnit.DAY).atStartOfDayIn(timeZone).toEpochMilliseconds()
            }
        return ReadingStreakDomainModel(
            currentStreak = overview.currentStreak,
            longestStreak = overview.longestStreak,
            lastReadingDay = lastDay?.atStartOfDayIn(timeZone)?.toEpochMilliseconds(),
            currentStreakDays = timestamps(
                currentEnd?.minus(overview.currentStreak - 1, DateTimeUnit.DAY),
                overview.currentStreak,
            ),
            longestStreakDays = timestamps(overview.longestStreakStart, overview.longestStreak),
        )
    }
}
