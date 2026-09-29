package com.retro99.statistics.domain

import com.retro99.statistics.domain.model.ReadingBucket
import com.retro99.statistics.domain.model.RHYTHM_WINDOW_DAYS
import com.retro99.statistics.domain.model.ReadingRhythm
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import com.retro99.statistics.domain.model.StatisticsOverview
import com.retro99.statistics.domain.model.StatisticsRange
import com.retro99.statistics.domain.model.TimeOfDay
import com.retro99.statistics.domain.model.WeekdayAverage
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private const val DAYS_IN_WEEK = 7
private const val MONTHS_IN_YEAR = 12
private const val HOURS_IN_DAY = 24

/**
 * Pure calculation of [StatisticsOverview] from reading sessions. A session belongs to the
 * local calendar day it started on.
 */
object StatisticsOverviewCalculator {

    fun calculate(
        sessions: List<ReadingSessionDomainModel>,
        range: StatisticsRange,
        today: LocalDate,
        timeZone: TimeZone,
        firstDayOfWeek: DayOfWeek,
    ): StatisticsOverview {
        val dated = sessions.map { session ->
            session to Instant.fromEpochMilliseconds(session.startTime)
                .toLocalDateTime(timeZone)
                .date
        }
        val msByDay = dated
            .groupBy({ entry -> entry.second }, { entry -> entry.first.durationMs })
            .mapValues { entry -> entry.value.sum() }

        val weekStart = startOfWeek(today, firstDayOfWeek)
        val monthStart = LocalDate(today.year, today.month, 1)
        val monthEnd = endOfMonth(monthStart)
        val yearStart = LocalDate(today.year, Month.JANUARY, 1)
        val yearEnd = LocalDate(today.year, Month.DECEMBER, 31)

        val rangeBounds = when (range) {
            StatisticsRange.WEEK -> weekStart to weekStart.plus(DAYS_IN_WEEK - 1, DateTimeUnit.DAY)
            StatisticsRange.MONTH -> monthStart to monthEnd
            StatisticsRange.YEAR -> yearStart to yearEnd
            StatisticsRange.ALL_TIME -> null
        }

        val buckets = when (range) {
            StatisticsRange.WEEK, StatisticsRange.MONTH -> {
                val (start, end) = checkNotNull(rangeBounds)
                generateSequence(start) { day -> day.plus(1, DateTimeUnit.DAY) }
                    .takeWhile { day -> day <= end }
                    .map { day -> ReadingBucket(day, day, msByDay[day] ?: 0L) }
                    .toList()
            }

            StatisticsRange.YEAR -> (1..MONTHS_IN_YEAR).map { monthNumber ->
                val start = LocalDate(today.year, monthNumber, 1)
                val end = endOfMonth(start)
                ReadingBucket(start, end, sumBetween(msByDay, start, end))
            }

            StatisticsRange.ALL_TIME -> emptyList()
        }

        val inRange = dated.filter { entry ->
            rangeBounds == null ||
                (entry.second >= rangeBounds.first && entry.second <= rangeBounds.second)
        }
        val totalMs = inRange.sumOf { entry -> entry.first.durationMs }
        val allTimeMs = dated.sumOf { entry -> entry.first.durationMs }

        val readDays = msByDay.filterValues { duration -> duration > 0L }.keys
        val streaks = calculateStreaks(readDays, today)

        return StatisticsOverview(
            range = range,
            rangeStart = rangeBounds?.first,
            rangeEnd = rangeBounds?.second,
            buckets = buckets,
            totalMs = totalMs,
            sessionCount = inRange.size,
            firstSessionDate = dated.minOfOrNull { entry -> entry.second },
            today = today,
            currentWeekStart = weekStart,
            todayMs = msByDay[today] ?: 0L,
            monthMs = sumBetween(msByDay, monthStart, monthEnd),
            allTimeMs = allTimeMs,
            totalSessions = dated.size,
            averageSessionMs = if (dated.isEmpty()) 0L else allTimeMs / dated.size,
            currentStreak = streaks.current,
            longestStreak = streaks.longest,
            longestStreakStart = streaks.longestStart,
            longestStreakEnd = streaks.longestEnd,
            readDays = readDays,
            rhythm = calculateRhythm(
                sessions = sessions,
                msByDay = msByDay,
                today = today,
                timeZone = timeZone,
                firstDayOfWeek = firstDayOfWeek,
            ),
        )
    }

    private fun calculateRhythm(
        sessions: List<ReadingSessionDomainModel>,
        msByDay: Map<LocalDate, Long>,
        today: LocalDate,
        timeZone: TimeZone,
        firstDayOfWeek: DayOfWeek,
    ): ReadingRhythm {
        val windowStart = today.minus(RHYTHM_WINDOW_DAYS - 1, DateTimeUnit.DAY)
        val windowDays = generateSequence(windowStart) { day -> day.plus(1, DateTimeUnit.DAY) }
            .takeWhile { day -> day <= today }
            .toList()
        val weekdayOrder = (0 until DAYS_IN_WEEK).map { offset ->
            DayOfWeek(((firstDayOfWeek.isoDayNumber - 1 + offset) % DAYS_IN_WEEK) + 1)
        }

        val weekdayAverages = weekdayOrder.map { weekday ->
            val days = windowDays.filter { day -> day.dayOfWeek == weekday }
            val total = days.sumOf { day -> msByDay[day] ?: 0L }
            WeekdayAverage(weekday, if (days.isEmpty()) 0L else total / days.size)
        }
        val maxAverage = weekdayAverages.maxOfOrNull { entry -> entry.averageMs } ?: 0L
        // Ties go to the weekday that occurred most recently.
        val topWeekday = weekdayAverages
            .filter { entry -> entry.averageMs == maxAverage && maxAverage > 0L }
            .minByOrNull { entry ->
                (today.dayOfWeek.isoDayNumber - entry.dayOfWeek.isoDayNumber + DAYS_IN_WEEK) %
                    DAYS_IN_WEEK
            }
            ?.dayOfWeek

        val timeOfDayMs = TimeOfDay.entries.associateWith { 0L }.toMutableMap()
        sessions.forEach { session ->
            val startDate = Instant.fromEpochMilliseconds(session.startTime)
                .toLocalDateTime(timeZone)
                .date
            if (startDate >= windowStart && startDate <= today) {
                splitAcrossTimeOfDay(session, timeZone).forEach { (part, ms) ->
                    timeOfDayMs[part] = (timeOfDayMs[part] ?: 0L) + ms
                }
            }
        }
        val maxTimeOfDay = timeOfDayMs.values.maxOrNull() ?: 0L
        val topTimeOfDay = TimeOfDay.entries.firstOrNull { part ->
            maxTimeOfDay > 0L && timeOfDayMs[part] == maxTimeOfDay
        }

        val readDaysInWindow = windowDays.count { day -> (msByDay[day] ?: 0L) > 0L }
        val rhythm = ReadingRhythm(
            weekdayAverages = weekdayAverages,
            topWeekday = topWeekday,
            timeOfDayMs = timeOfDayMs,
            topTimeOfDay = topTimeOfDay,
            readDaysInWindow = readDaysInWindow,
        )
        return if (rhythm.hasEnoughData) {
            rhythm
        } else {
            rhythm.copy(topWeekday = null, topTimeOfDay = null)
        }
    }

    /** Splits a session's duration over the parts of the day it spans, in local time. */
    private fun splitAcrossTimeOfDay(
        session: ReadingSessionDomainModel,
        timeZone: TimeZone,
    ): Map<TimeOfDay, Long> {
        val result = mutableMapOf<TimeOfDay, Long>()
        var cursor = session.startTime
        var remaining = session.durationMs
        while (remaining > 0L) {
            val local = Instant.fromEpochMilliseconds(cursor).toLocalDateTime(timeZone)
            val part = TimeOfDay.entries.first { candidate ->
                local.hour >= candidate.startHour && local.hour < candidate.endHour
            }
            val boundary = if (part.endHour == HOURS_IN_DAY) {
                LocalDateTime(local.date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
            } else {
                LocalDateTime(local.date, LocalTime(part.endHour, 0))
            }.toInstant(timeZone).toEpochMilliseconds()
            val take = if (boundary > cursor) minOf(remaining, boundary - cursor) else remaining
            result[part] = (result[part] ?: 0L) + take
            cursor += take
            remaining -= take
        }
        return result
    }

    fun startOfWeek(day: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
        val offset = (day.dayOfWeek.isoDayNumber - firstDayOfWeek.isoDayNumber + DAYS_IN_WEEK) %
            DAYS_IN_WEEK
        return day.minus(offset, DateTimeUnit.DAY)
    }

    private fun endOfMonth(monthStart: LocalDate): LocalDate =
        monthStart.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)

    private fun sumBetween(msByDay: Map<LocalDate, Long>, start: LocalDate, end: LocalDate): Long =
        msByDay.entries
            .filter { entry -> entry.key >= start && entry.key <= end }
            .sumOf { entry -> entry.value }

    private data class Streaks(
        val current: Int,
        val longest: Int,
        val longestStart: LocalDate?,
        val longestEnd: LocalDate?,
    )

    /**
     * The current streak counts back from today, or from yesterday when today has no reading
     * yet. Of several equally long streaks the most recent one is "the longest".
     */
    private fun calculateStreaks(readDays: Set<LocalDate>, today: LocalDate): Streaks {
        if (readDays.isEmpty()) return Streaks(0, 0, null, null)

        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val currentEnd = when {
            today in readDays -> today
            yesterday in readDays -> yesterday
            else -> null
        }
        var current = 0
        if (currentEnd != null) {
            var day: LocalDate = currentEnd
            while (day in readDays) {
                current++
                day = day.minus(1, DateTimeUnit.DAY)
            }
        }

        var longest = 0
        var longestStart: LocalDate? = null
        var longestEnd: LocalDate? = null
        var runStart = readDays.min()
        var previous: LocalDate? = null
        var runLength = 0
        readDays.sorted().forEach { day ->
            val continuesRun = previous?.plus(1, DateTimeUnit.DAY) == day
            if (continuesRun) {
                runLength++
            } else {
                runStart = day
                runLength = 1
            }
            previous = day
            if (runLength >= longest) {
                longest = runLength
                longestStart = runStart
                longestEnd = day
            }
        }
        return Streaks(current, longest, longestStart, longestEnd)
    }
}
