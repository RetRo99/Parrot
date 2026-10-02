package com.retro99.statistics.ui.model

import com.retro99.base.ui.compose.TextWrapper
import com.retro99.books.domain.model.BookType
import com.retro99.statistics.domain.model.BookReadingStatsDomainModel
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import com.retro99.statistics.domain.model.ReadingStatisticsDomainModel
import com.retro99.translations.StringRes
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import resources.translations.statistics_day_number
import resources.translations.statistics_hours_minutes
import resources.translations.statistics_minutes
import resources.translations.statistics_session_speed

data class ReadingStatisticsUiModel(
    val totalReadingTimeFormatted: TextWrapper,
    val todayReadingTimeFormatted: TextWrapper,
    val weekReadingTimeFormatted: TextWrapper,
    val monthReadingTimeFormatted: TextWrapper,
    val totalSessions: Long,
    val totalBooksRead: Long,
    val currentStreak: Int,
    val longestStreak: Int,
    val currentStreakDays: List<Long>,
    val longestStreakDays: List<Long>,
    val dailyReadingTime: List<DailyReadingTimeUiModel>,
    val mostReadBooks: List<BookReadingStatsUiModel>,
    val readingTimeByType: Map<BookType, Long>,
)

data class DailyReadingTimeUiModel(
    val date: TextWrapper,
    val readingTimeMs: Long,
    val readingTimeFormatted: TextWrapper,
)

data class BookReadingStatsUiModel(
    val bookUuid: String,
    val bookTitle: String,
    val coverUrl: String?,
    val totalTimeMs: Long,
    val totalTimeFormatted: TextWrapper,
    val sessionCount: Long,
)

data class ReadingSessionUiModel(
    val id: Long,
    val bookUuid: String,
    val bookTitle: String,
    val startTime: Long,
    val durationMs: Long,
    val durationFormatted: TextWrapper,
    val dateFormatted: String,
    val readingSpeedFormatted: TextWrapper,
    val endTimeFormatted: String = "",
    val readingSpeedWpm: Int = 0,
    val startProgression: Double? = null,
    val endProgression: Double? = null,
    /** Links to the recap of the same reader session; null when none was recorded. */
    val recapSessionId: String? = null,
)

fun ReadingStatisticsDomainModel.toUiModel(): ReadingStatisticsUiModel {
    return ReadingStatisticsUiModel(
        totalReadingTimeFormatted = formatDuration(totalReadingTimeMs),
        todayReadingTimeFormatted = formatDuration(todayReadingTimeMs),
        weekReadingTimeFormatted = formatDuration(weekReadingTimeMs),
        monthReadingTimeFormatted = formatDuration(monthReadingTimeMs),
        totalSessions = totalSessions,
        totalBooksRead = totalBooksRead,
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        currentStreakDays = currentStreakDays,
        longestStreakDays = longestStreakDays,
        dailyReadingTime = dailyReadingTime.map { daily ->
            DailyReadingTimeUiModel(
                date = formatDate(daily.dayStart),
                readingTimeMs = daily.totalDurationMs,
                readingTimeFormatted = formatDuration(daily.totalDurationMs),
            )
        },
        mostReadBooks = mostReadBooks.map { book ->
            BookReadingStatsUiModel(
                bookUuid = book.bookUuid,
                bookTitle = book.bookTitle,
                coverUrl = book.coverUrl,
                totalTimeMs = book.totalDurationMs,
                totalTimeFormatted = formatDuration(book.totalDurationMs),
                sessionCount = book.sessionCount,
            )
        },
        readingTimeByType = readingTimeByType,
    )
}

private fun formatDuration(ms: Long): TextWrapper {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60

    return when {
        hours > 0 -> TextWrapper.Resource(StringRes.statistics_hours_minutes, hours, minutes)
        minutes > 0 -> TextWrapper.Resource(StringRes.statistics_minutes, minutes)
        else -> TextWrapper.Resource(StringRes.statistics_minutes, 0)
    }
}

private fun formatDate(timestamp: Long): TextWrapper {
    val dayNumber = timestamp / (24 * 60 * 60 * 1000)
    return TextWrapper.Resource(StringRes.statistics_day_number, dayNumber)
}

fun BookReadingStatsDomainModel.toBookUiModel(): BookReadingStatsUiModel {
    return BookReadingStatsUiModel(
        bookUuid = bookUuid,
        bookTitle = bookTitle,
        coverUrl = coverUrl,
        totalTimeMs = totalDurationMs,
        totalTimeFormatted = formatDuration(totalDurationMs),
        sessionCount = sessionCount,
    )
}

fun ReadingSessionDomainModel.toSessionUiModel(): ReadingSessionUiModel {
    return ReadingSessionUiModel(
        id = id,
        bookUuid = bookUuid,
        bookTitle = bookTitle,
        startTime = startTime,
        durationMs = durationMs,
        durationFormatted = formatDuration(durationMs),
        dateFormatted = formatSessionDate(startTime),
        readingSpeedFormatted = formatReadingSpeed(readingSpeedWpm),
        endTimeFormatted = formatSessionTime(endTime),
        readingSpeedWpm = readingSpeedWpm,
        startProgression = startProgression,
        endProgression = endProgression,
        recapSessionId = recapSessionId,
    )
}

private fun formatReadingSpeed(wpm: Int): TextWrapper {
    return TextWrapper.Resource(StringRes.statistics_session_speed, wpm)
}

private fun formatSessionDate(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val month = localDateTime.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    val day = localDateTime.dayOfMonth
    return "$month $day, ${formatSessionTime(timestamp)}"
}

private fun formatSessionTime(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = localDateTime.hour
    val minute = localDateTime.minute.toString().padStart(2, '0')
    val amPm = if (hour < 12) "AM" else "PM"
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$displayHour:$minute $amPm"
}
