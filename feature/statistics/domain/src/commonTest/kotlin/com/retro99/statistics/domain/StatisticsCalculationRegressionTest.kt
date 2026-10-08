package com.retro99.statistics.domain

import com.retro99.books.domain.model.BookType
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import com.retro99.statistics.domain.model.RHYTHM_WINDOW_DAYS
import com.retro99.statistics.domain.model.StatisticsRange
import com.retro99.statistics.domain.model.TimeOfDay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatisticsCalculationRegressionTest {
    @Test
    fun `generated histories conserve duration across ranges locales and time zones`() {
        val random = Random(20261008)
        val today = LocalDate(2026, 10, 8)
        for (zone in listOf(TimeZone.UTC, TimeZone.of("America/New_York"), TimeZone.of("Asia/Kathmandu"))) {
            val sessions = List(120) { index ->
                val day = today.minus(random.nextInt(0, 400), DateTimeUnit.DAY)
                session(day.atStartOfDayIn(zone).toEpochMilliseconds() + random.nextLong(0, 12 * HOUR), random.nextLong(1, 4 * HOUR))
                    .copy(id = index.toLong(), bookType = BookType.entries[index % BookType.entries.size])
            }
            val expectedTotal = sessions.sumOf { it.durationMs }
            for (firstDay in DayOfWeek.entries) {
                for (range in StatisticsRange.entries) {
                    val overview = StatisticsOverviewCalculator.calculate(sessions, range, today, zone, firstDay)
                    val context = "$zone / $firstDay / $range"
                    assertEquals(expectedTotal, overview.allTimeMs, context)
                    assertEquals(sessions.size, overview.totalSessions, context)
                    assertEquals(expectedTotal / sessions.size, overview.averageSessionMs, context)
                    assertTrue(overview.totalMs in 0..expectedTotal, context)
                    assertTrue(overview.buckets.all { it.durationMs >= 0 }, context)
                    if (range == StatisticsRange.ALL_TIME) {
                        assertEquals(expectedTotal, overview.totalMs, context)
                    } else {
                        assertEquals(overview.totalMs, overview.buckets.sumOf { it.durationMs }, context)
                        assertEquals(overview.buckets.size, overview.buckets.map { it.start }.distinct().size, context)
                    }
                    val recent = sessions.filter { it.startTime >= today.minus(RHYTHM_WINDOW_DAYS - 1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds() }
                    assertEquals(recent.sumOf { it.durationMs }, overview.rhythm.timeOfDayMs.values.sum(), context)
                }
            }
        }
    }

    @Test
    fun `spring DST gap counts elapsed time not nonexistent clock hours`() {
        val zone = TimeZone.of("America/New_York")
        val day = LocalDate(2026, 3, 8)
        val start = LocalDateTime(2026, 3, 8, 1, 30).toInstant(zone).toEpochMilliseconds()
        val overview = StatisticsOverviewCalculator.calculate(
            listOf(session(start, 5 * HOUR)), StatisticsRange.WEEK, day, zone, DayOfWeek.MONDAY,
        )
        assertEquals(210 * MINUTE, overview.rhythm.timeOfDayMs[TimeOfDay.NIGHT])
        assertEquals(90 * MINUTE, overview.rhythm.timeOfDayMs[TimeOfDay.MORNING])
        assertEquals(5 * HOUR, overview.rhythm.timeOfDayMs.values.sum())
    }

    @Test
    fun `fall DST repeated hour contributes once per real elapsed minute`() {
        val zone = TimeZone.of("America/New_York")
        val day = LocalDate(2026, 11, 1)
        val start = LocalDateTime(2026, 11, 1, 0, 30).toInstant(zone).toEpochMilliseconds()
        val overview = StatisticsOverviewCalculator.calculate(
            listOf(session(start, 7 * HOUR)), StatisticsRange.WEEK, day, zone, DayOfWeek.SUNDAY,
        )
        assertEquals(390 * MINUTE, overview.rhythm.timeOfDayMs[TimeOfDay.NIGHT])
        assertEquals(30 * MINUTE, overview.rhythm.timeOfDayMs[TimeOfDay.MORNING])
        assertEquals(7 * HOUR, overview.rhythm.timeOfDayMs.values.sum())
    }

    @Test
    fun `leap February has every day exactly once including its last day`() {
        val today = LocalDate(2024, 2, 29)
        val overview = StatisticsOverviewCalculator.calculate(
            listOf(session(today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(), MINUTE)),
            StatisticsRange.MONTH, today, TimeZone.UTC, DayOfWeek.MONDAY,
        )
        assertEquals(29, overview.buckets.size)
        assertEquals(today, overview.buckets.last().start)
        assertEquals(MINUTE, overview.buckets.last().durationMs)
        assertEquals(MINUTE, overview.monthMs)
    }

    @Test
    fun `chart calendar and streak calendar agree for the same history`() {
        val today = LocalDate(2026, 10, 8)
        val zone = TimeZone.of("Asia/Kathmandu")
        val sessions = (0..6).map { offset ->
            session(today.minus(offset, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds(), (offset + 1) * MINUTE)
        }.reversed()
        val overview = StatisticsOverviewCalculator.calculate(sessions, StatisticsRange.MONTH, today, zone, DayOfWeek.MONDAY)
        val streak = StatisticsSessionCalendar.streak(sessions, today, zone)
        val daily = StatisticsSessionCalendar.dailyTime(sessions, 0L, zone)
        assertEquals(overview.currentStreak, streak.currentStreak)
        assertEquals(overview.longestStreak, streak.longestStreak)
        assertEquals(overview.allTimeMs, daily.sumOf { it.totalDurationMs })
        assertEquals(overview.readDays.size, daily.size)
    }

    private fun session(start: Long, duration: Long) = ReadingSessionDomainModel(
        id = 0, bookUuid = "book", bookTitle = "Book", bookType = BookType.EBOOK,
        startTime = start, endTime = start + duration, durationMs = duration, pagesRead = null,
        startProgression = null, endProgression = null, readingSpeedWpm = 250,
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
