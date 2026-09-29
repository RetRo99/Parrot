package com.retro99.statistics.domain

import com.retro99.books.domain.model.BookType
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import com.retro99.statistics.domain.model.StatisticsRange
import com.retro99.statistics.domain.model.TimeOfDay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatisticsOverviewCalculatorTest {

    private val timeZone = TimeZone.UTC
    private val minute = 60_000L

    // Tuesday
    private val today = LocalDate(2026, 9, 29)

    private fun session(day: LocalDate, minutes: Long, hour: Int = 12): ReadingSessionDomainModel {
        val start = day.atStartOfDayIn(timeZone).toEpochMilliseconds() + hour * 60 * minute
        return ReadingSessionDomainModel(
            id = 0,
            bookUuid = "book",
            bookTitle = "Book",
            bookType = BookType.EBOOK,
            startTime = start,
            endTime = start + minutes * minute,
            durationMs = minutes * minute,
            pagesRead = null,
            startProgression = null,
            endProgression = null,
            readingSpeedWpm = 0,
        )
    }

    private fun calculate(
        sessions: List<ReadingSessionDomainModel>,
        range: StatisticsRange,
        firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    ) = StatisticsOverviewCalculator.calculate(sessions, range, today, timeZone, firstDayOfWeek)

    @Test
    fun weekStartsOnTheLocalesFirstDay() {
        val sundayStart = calculate(emptyList(), StatisticsRange.WEEK, DayOfWeek.SUNDAY)
        assertEquals(LocalDate(2026, 9, 27), sundayStart.rangeStart)
        assertEquals(LocalDate(2026, 10, 3), sundayStart.rangeEnd)
        assertEquals(7, sundayStart.buckets.size)

        val mondayStart = calculate(emptyList(), StatisticsRange.WEEK, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 9, 28), mondayStart.rangeStart)
        assertEquals(LocalDate(2026, 10, 4), mondayStart.rangeEnd)
    }

    @Test
    fun weekTotalsOnlyCountSessionsInsideTheWeek() {
        val overview = calculate(
            listOf(
                session(LocalDate(2026, 9, 27), 33),
                session(LocalDate(2026, 9, 26), 50),
            ),
            StatisticsRange.WEEK,
        )

        assertEquals(33 * minute, overview.totalMs)
        assertEquals(1, overview.sessionCount)
        assertEquals(33 * minute, overview.buckets.first().durationMs)
        assertEquals(2, overview.totalSessions)
        assertEquals(83 * minute, overview.allTimeMs)
    }

    @Test
    fun monthHasOneBucketPerDay() {
        val overview = calculate(emptyList(), StatisticsRange.MONTH)

        assertEquals(30, overview.buckets.size)
        assertEquals(LocalDate(2026, 9, 1), overview.rangeStart)
        assertEquals(LocalDate(2026, 9, 30), overview.rangeEnd)
    }

    @Test
    fun yearHasOneBucketPerMonth() {
        val overview = calculate(
            listOf(
                session(LocalDate(2026, 1, 5), 10),
                session(LocalDate(2026, 1, 20), 20),
                session(LocalDate(2026, 9, 1), 5),
                session(LocalDate(2025, 12, 31), 99),
            ),
            StatisticsRange.YEAR,
        )

        assertEquals(12, overview.buckets.size)
        assertEquals(30 * minute, overview.buckets[0].durationMs)
        assertEquals(5 * minute, overview.buckets[8].durationMs)
        assertEquals(35 * minute, overview.totalMs)
    }

    @Test
    fun allTimeHasNoBucketsAndKnowsTheFirstSession() {
        val overview = calculate(
            listOf(session(LocalDate(2025, 3, 2), 10), session(LocalDate(2026, 9, 27), 30)),
            StatisticsRange.ALL_TIME,
        )

        assertEquals(emptyList(), overview.buckets)
        assertNull(overview.rangeStart)
        assertEquals(LocalDate(2025, 3, 2), overview.firstSessionDate)
        assertEquals(40 * minute, overview.totalMs)
        assertEquals(20 * minute, overview.averageSessionMs)
    }

    @Test
    fun streakCountsBackFromTodayOrYesterday() {
        val readToday = calculate(
            listOf(
                session(LocalDate(2026, 9, 29), 5),
                session(LocalDate(2026, 9, 28), 5),
                session(LocalDate(2026, 9, 27), 5),
            ),
            StatisticsRange.WEEK,
        )
        assertEquals(3, readToday.currentStreak)

        val readYesterday = calculate(
            listOf(session(LocalDate(2026, 9, 28), 5), session(LocalDate(2026, 9, 27), 5)),
            StatisticsRange.WEEK,
        )
        assertEquals(2, readYesterday.currentStreak)

        val lapsed = calculate(listOf(session(LocalDate(2026, 9, 27), 5)), StatisticsRange.WEEK)
        assertEquals(0, lapsed.currentStreak)
        assertEquals(1, lapsed.longestStreak)
    }

    @Test
    fun longestStreakReportsItsDates() {
        val overview = calculate(
            listOf(
                session(LocalDate(2026, 9, 1), 5),
                session(LocalDate(2026, 9, 2), 5),
                session(LocalDate(2026, 9, 3), 5),
                session(LocalDate(2026, 9, 10), 5),
                session(LocalDate(2026, 9, 11), 5),
            ),
            StatisticsRange.MONTH,
        )

        assertEquals(3, overview.longestStreak)
        assertEquals(LocalDate(2026, 9, 1), overview.longestStreakStart)
        assertEquals(LocalDate(2026, 9, 3), overview.longestStreakEnd)
    }

    @Test
    fun noSessionsGivesZeros() {
        val overview = calculate(emptyList(), StatisticsRange.WEEK)

        assertEquals(0L, overview.totalMs)
        assertEquals(0, overview.currentStreak)
        assertEquals(0, overview.longestStreak)
        assertNull(overview.firstSessionDate)
        assertEquals(0L, overview.averageSessionMs)
    }

    @Test
    fun rhythmNeedsSevenReadingDays() {
        val fewDays = calculate(
            (0L..5L).map { offset -> session(today.minus(offset.toInt(), DateTimeUnit.DAY), 10) },
            StatisticsRange.WEEK,
        )
        assertEquals(6, fewDays.rhythm.readDaysInWindow)
        assertNull(fewDays.rhythm.topWeekday)
        assertNull(fewDays.rhythm.topTimeOfDay)

        val enough = calculate(
            (0L..6L).map { offset -> session(today.minus(offset.toInt(), DateTimeUnit.DAY), 10) },
            StatisticsRange.WEEK,
        )
        assertEquals(true, enough.rhythm.hasEnoughData)
    }

    @Test
    fun rhythmFindsTopWeekdayAndTimeOfDay() {
        // Sundays are the long days, read in the evening.
        val sundays = listOf(
            LocalDate(2026, 9, 27),
            LocalDate(2026, 9, 20),
            LocalDate(2026, 9, 13),
        ).map { day -> session(day, 60, hour = 19) }
        val others = (1..5).map { offset ->
            session(LocalDate(2026, 9, 29).minus(offset - 1, DateTimeUnit.DAY), 10, hour = 9)
        }
        val overview = calculate(sundays + others, StatisticsRange.WEEK, DayOfWeek.MONDAY)

        assertEquals(DayOfWeek.SUNDAY, overview.rhythm.topWeekday)
        assertEquals(TimeOfDay.EVENING, overview.rhythm.topTimeOfDay)
        assertEquals(DayOfWeek.MONDAY, overview.rhythm.weekdayAverages.first().dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, overview.rhythm.weekdayAverages.last().dayOfWeek)
    }

    @Test
    fun sessionsSpanningMidnightSplitAcrossTimeOfDay() {
        // 23:30 for 60 minutes: 30 minutes of evening, 30 of night.
        val overview = calculate(
            listOf(session(LocalDate(2026, 9, 28), 60, hour = 23).let { base ->
                base.copy(startTime = base.startTime + 30 * minute)
            }),
            StatisticsRange.WEEK,
        )

        assertEquals(30 * minute, overview.rhythm.timeOfDayMs[TimeOfDay.EVENING])
        assertEquals(30 * minute, overview.rhythm.timeOfDayMs[TimeOfDay.NIGHT])
    }
}
