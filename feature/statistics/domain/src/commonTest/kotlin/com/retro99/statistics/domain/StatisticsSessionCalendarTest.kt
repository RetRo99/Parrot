package com.retro99.statistics.domain

import com.retro99.books.domain.model.BookType
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StatisticsSessionCalendarTest {
    private fun session(start: String, duration: Long = 60_000L) = ReadingSessionDomainModel(
        id = 0, bookUuid = "a", bookTitle = "A", bookType = BookType.EBOOK,
        startTime = Instant.parse(start).toEpochMilliseconds(),
        endTime = Instant.parse(start).toEpochMilliseconds() + duration,
        durationMs = duration, pagesRead = null, startProgression = null,
        endProgression = null, readingSpeedWpm = 250,
    )

    @Test
    fun dailyTimeGroupsByLocalDateRatherThanUtcDate() {
        val zone = TimeZone.of("America/Los_Angeles")
        val sessions = listOf(session("2026-09-29T23:00:00Z"), session("2026-09-30T01:00:00Z"))
        val day = LocalDate(2026, 9, 29).atStartOfDayIn(zone).toEpochMilliseconds()
        val daily = StatisticsSessionCalendar.dailyTime(sessions, day, zone)
        assertEquals(1, daily.size)
        assertEquals(day, daily.single().dayStart)
        assertEquals(120_000L, daily.single().totalDurationMs)
        val streak = StatisticsSessionCalendar.streak(sessions, LocalDate(2026, 9, 29), zone)
        assertEquals(1, streak.currentStreak)
    }

    @Test
    fun consecutiveLocalDatesStayConsecutiveAcrossDaylightSaving() {
        val zone = TimeZone.of("America/New_York")
        val sessions = listOf(
            session("2026-03-08T04:30:00Z"), // March 7, 23:30 EST
            session("2026-03-09T03:30:00Z"), // March 8, 23:30 EDT
            session("2026-03-10T03:30:00Z"), // March 9, 23:30 EDT
        )
        val streak = StatisticsSessionCalendar.streak(sessions, LocalDate(2026, 3, 9), zone)
        assertEquals(3, streak.currentStreak)
        assertEquals(3, streak.longestStreak)
        assertEquals(
            (7..9).map { LocalDate(2026, 3, it).atStartOfDayIn(zone).toEpochMilliseconds() },
            streak.currentStreakDays,
        )
    }

    @Test
    fun longestStreakIncludesOldHistoryAndIgnoresZeroDurationSessions() {
        val sessions = listOf(
            session("2020-01-01T12:00:00Z"), session("2020-01-02T12:00:00Z"),
            session("2026-09-28T12:00:00Z", 0), session("2026-09-29T12:00:00Z"),
        )
        val streak = StatisticsSessionCalendar.streak(sessions, LocalDate(2026, 9, 29), TimeZone.UTC)
        assertEquals(1, streak.currentStreak)
        assertEquals(2, streak.longestStreak)
    }

    @Test
    fun positiveOffsetAndLocalMidnightFilteringMatchTheDashboard() {
        val zone = TimeZone.of("Europe/Ljubljana")
        val today = LocalDate(2026, 9, 30)
        val start = today.atStartOfDayIn(zone).toEpochMilliseconds()
        val sessions = listOf(
            session("2026-09-29T21:59:59Z"),
            session("2026-09-29T22:00:00Z"),
            session("2026-09-30T00:00:00Z"),
        )
        val daily = StatisticsSessionCalendar.dailyTime(sessions, start, zone)
        assertEquals(1, daily.size)
        assertEquals(start, daily.single().dayStart)
        assertEquals(120_000L, daily.single().totalDurationMs)
        val streak = StatisticsSessionCalendar.streak(sessions, today, zone)
        assertEquals(2, streak.currentStreak)
    }

    @Test
    fun fallBackKeepsBothOccurrencesOfTheRepeatedHourOnTheSameLocalDay() {
        val zone = TimeZone.of("America/New_York")
        val sessions = listOf(session("2026-11-01T05:30:00Z"), session("2026-11-01T06:30:00Z"))
        val daily = StatisticsSessionCalendar.dailyTime(sessions, 0L, zone)
        assertEquals(1, daily.size)
        assertEquals(
            LocalDate(2026, 11, 1).atStartOfDayIn(zone).toEpochMilliseconds(),
            daily.single().dayStart,
        )
        assertEquals(120_000L, daily.single().totalDurationMs)
    }

    @Test
    fun shuffledSessionsProduceChronologicalDailyBuckets() {
        val sessions = listOf(
            session("2026-09-30T12:00:00Z", 10L),
            session("2026-09-28T12:00:00Z", 20L),
            session("2026-09-29T12:00:00Z", 30L),
            session("2026-09-28T13:00:00Z", 40L),
        )
        val daily = StatisticsSessionCalendar.dailyTime(sessions, 0L, TimeZone.UTC)
        assertEquals(
            (28..30).map { LocalDate(2026, 9, it).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() },
            daily.map { it.dayStart },
        )
        assertEquals(listOf(60L, 30L, 10L), daily.map { it.totalDurationMs })
    }

    @Test
    fun midnightCrossingSessionBelongsToItsStartDay() {
        val sessions = listOf(session("2026-09-28T23:30:00Z", 3_600_000L))
        val daily = StatisticsSessionCalendar.dailyTime(sessions, 0L, TimeZone.UTC)
        assertEquals(
            LocalDate(2026, 9, 28).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            daily.single().dayStart,
        )
        assertEquals(3_600_000L, daily.single().totalDurationMs)
        val streak = StatisticsSessionCalendar.streak(sessions, LocalDate(2026, 9, 29), TimeZone.UTC)
        assertEquals(1, streak.currentStreak)
        assertEquals(1, streak.currentStreakDays.size)
    }

    @Test
    fun equalLongestStreaksChooseTheMostRecentAndLapsedCurrentStreakIsEmpty() {
        val sessions = listOf(
            session("2026-09-01T12:00:00Z"), session("2026-09-02T12:00:00Z"),
            session("2026-09-10T12:00:00Z"), session("2026-09-11T12:00:00Z"),
        )
        val streak = StatisticsSessionCalendar.streak(sessions, LocalDate(2026, 9, 29), TimeZone.UTC)
        assertEquals(0, streak.currentStreak)
        assertEquals(emptyList(), streak.currentStreakDays)
        assertEquals(2, streak.longestStreak)
        assertEquals(
            (10..11).map { LocalDate(2026, 9, it).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() },
            streak.longestStreakDays,
        )
        assertEquals(streak.longestStreakDays.last(), streak.lastReadingDay)
    }
}
