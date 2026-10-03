package com.retro99.statistics.data

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.model.BookType
import com.retro99.statistics.data.source.StatisticsLocalSource
import com.retro99.statistics.domain.ActiveSessionTimer
import com.retro99.statistics.domain.AudiobookSessionTracker
import com.retro99.statistics.domain.model.DailyReadingTimeDomainModel
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import com.retro99.statistics.domain.usecase.SaveReadingSessionUseCase
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Exercises the repository wiring in real system time zones, not just the pure calculator. */
class StatisticsDataRepositoryCalendarTest {
    private fun inTimeZone(id: String, block: (TimeZone, LocalDate) -> Unit) {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))
            val zone = TimeZone.currentSystemDefault()
            block(zone, Clock.System.now().toLocalDateTime(zone).date)
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test
    fun todayQueryStartsAtLocalMidnightInBothPositiveAndNegativeOffsets() {
        listOf("America/Los_Angeles", "Asia/Kathmandu").forEach { id ->
            inTimeZone(id) { zone, today ->
                runTest {
                    val source = RecordingCalendarSource()
                    val repository = StatisticsDataRepository(source)
                    val before = Clock.System.now().toEpochMilliseconds()
                    repository.getTodayReadingTimeMs().getOrElse { error("Unexpected error: $it") }
                    val after = Clock.System.now().toEpochMilliseconds()
                    val range = source.ranges.single()
                    assertEquals(today.atStartOfDayIn(zone).toEpochMilliseconds(), range.first)
                    assertTrue(range.second in before..after)
                }
            }
        }
    }

    @Test
    fun rollingWeekAndMonthQueriesUseLocalCalendarBoundaries() {
        inTimeZone("America/New_York") { zone, today ->
            runTest {
                val source = RecordingCalendarSource()
                val repository = StatisticsDataRepository(source)
                repository.getWeekReadingTimeMs()
                repository.getMonthReadingTimeMs()
                assertEquals(
                    listOf(
                        today.minus(7, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds(),
                        today.minus(1, DateTimeUnit.MONTH).atStartOfDayIn(zone).toEpochMilliseconds(),
                    ),
                    source.ranges.map { it.first },
                )
            }
        }
    }

    @Test
    fun dailyTimeReadsRawSessionsFiltersByLocalMidnightAndOrdersBuckets() {
        inTimeZone("Asia/Kathmandu") { zone, today ->
            runTest {
                val yesterday = today.minus(1, DateTimeUnit.DAY)
                val yesterdayStart = yesterday.atStartOfDayIn(zone).toEpochMilliseconds()
                val todayStart = today.atStartOfDayIn(zone).toEpochMilliseconds()
                val source = RecordingCalendarSource(listOf(
                    session(todayStart, 30L), session(yesterdayStart, 10L),
                    session(yesterdayStart - 1L, 999L), session(yesterdayStart + 1L, 20L),
                ))
                val daily = StatisticsDataRepository(source).getDailyReadingTime(1)
                    .getOrElse { error("Unexpected error: $it") }
                assertEquals(
                    listOf(
                        DailyReadingTimeDomainModel(yesterdayStart, 30L),
                        DailyReadingTimeDomainModel(todayStart, 30L),
                    ),
                    daily,
                )
                assertEquals(1, source.allSessionQueries)
            }
        }
    }

    @Test
    fun streakReadsAllHistoryAndUsesLocalDaysInsteadOfUtcDayNumbers() {
        inTimeZone("America/Los_Angeles") { zone, today ->
            runTest {
                val todayStart = today.atStartOfDayIn(zone).toEpochMilliseconds()
                val yesterdayStart = today.minus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
                val oldDays = (1..3).map { LocalDate(2020, 1, it).atStartOfDayIn(zone).toEpochMilliseconds() }
                val source = RecordingCalendarSource(
                    oldDays.map { session(it) } + listOf(session(yesterdayStart), session(todayStart)),
                )
                val streak = StatisticsDataRepository(source).getReadingStreak()
                    .getOrElse { error("Unexpected error: $it") }
                assertEquals(2, streak.currentStreak)
                assertEquals(listOf(yesterdayStart, todayStart), streak.currentStreakDays)
                assertEquals(3, streak.longestStreak)
                assertEquals(oldDays, streak.longestStreakDays)
                assertEquals(todayStart, streak.lastReadingDay)
                assertEquals(1, source.allSessionQueries)
            }
        }
    }

    @Test
    fun audiobookPlaybackFlowsThroughSaveUseCaseIntoRepositoryWithoutDuplicates() = runTest {
        var elapsed = 0L
        val pending = mutableListOf<ReadingSessionDomainModel>()
        val tracker = AudiobookSessionTracker(
            saveSession = pending::add,
            nowMillis = { 1_000_000L + elapsed },
            createTimer = { ActiveSessionTimer { elapsed } },
        )
        val source = RecordingCalendarSource()
        val save = SaveReadingSessionUseCase(StatisticsDataRepository(source))
        tracker.setBook("audio-book", "Audiobook")
        tracker.setPlaying(true)
        elapsed = 3_000L
        tracker.setPlaying(false)
        elapsed = 99_000L
        tracker.finish()
        pending.forEach { session ->
            save(
                bookUuid = session.bookUuid, bookTitle = session.bookTitle, bookType = session.bookType,
                startTime = session.startTime, endTime = session.endTime, durationMs = session.durationMs,
                readingSpeedWpm = session.readingSpeedWpm,
            ).getOrElse { error("Unexpected error: $it") }
        }
        assertEquals(pending, source.sessions)
        assertEquals(1, source.sessions.size)
        assertEquals(BookType.AUDIOBOOK, source.sessions.single().bookType)
        assertEquals(3_000L, source.sessions.single().durationMs)
    }

    @Test
    fun negativeChartRangeIsRejectedBeforeReadingTheDatabase() = runTest {
        val source = RecordingCalendarSource()
        assertFailsWith<IllegalArgumentException> { StatisticsDataRepository(source).getDailyReadingTime(-1) }
        assertEquals(0, source.allSessionQueries)
    }
}

private fun session(start: Long, duration: Long = 60_000L) = ReadingSessionDomainModel(
    id = 0, bookUuid = "book", bookTitle = "Book", bookType = BookType.EBOOK,
    startTime = start, endTime = start + duration, durationMs = duration, pagesRead = null,
    startProgression = null, endProgression = null, readingSpeedWpm = 250,
)

private class RecordingCalendarSource(initialSessions: List<ReadingSessionDomainModel> = emptyList()) :
    StatisticsLocalSource by FakeStatisticsLocalSource() {
    val sessions = initialSessions.toMutableList()
    val ranges = mutableListOf<Pair<Long, Long>>()
    var allSessionQueries = 0

    override suspend fun insertSession(session: ReadingSessionDomainModel): CompletableResult {
        sessions += session
        return Ok(Unit)
    }

    override suspend fun getAllSessions(): AppResult<List<ReadingSessionDomainModel>> {
        allSessionQueries++
        return Ok(sessions.toList())
    }

    override suspend fun getTotalReadingTimeMsInDateRange(startTime: Long, endTime: Long): AppResult<Long> {
        ranges += startTime to endTime
        return Ok(sessions.filter { it.startTime in startTime..endTime }.sumOf { it.durationMs })
    }

    override suspend fun getDailyReadingTime(sinceTimestamp: Long): AppResult<List<DailyReadingTimeDomainModel>> =
        error("The repository must not use UTC SQL buckets for its local calendar")

    override suspend fun getReadingDays(sinceTimestamp: Long): AppResult<List<Long>> =
        error("The repository must not use UTC SQL day numbers for its local calendar")
}
