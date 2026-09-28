package com.retro99.statistics.ui

import com.retro99.base.result.AppError
import com.retro99.statistics.domain.model.StatisticsPeriod
import com.retro99.statistics.ui.model.BookReadingStatsUiModel
import com.retro99.statistics.ui.model.ReadingSessionUiModel
import com.retro99.statistics.ui.model.ReadingStatisticsUiModel

data class StatisticsViewState(
    val statistics: ReadingStatisticsUiModel? = null,
    val isLoading: Boolean = true,
    val error: AppError? = null,
    val detailState: StatisticsDetailState? = null,
    val streakDetailState: StreakDetailState? = null,
    val booksReadDetailState: BooksReadDetailState? = null,
    val sessionsDetailState: SessionsDetailState? = null,
)

/**
 * State for the statistics detail bottom sheet.
 */
data class StatisticsDetailState(
    val period: StatisticsPeriod,
    val books: List<BookReadingStatsUiModel>,
    val isLoading: Boolean = false,
    val error: AppError? = null,
    val isCancelled: Boolean = false,
)

/**
 * State for the streak detail bottom sheet.
 */
data class StreakDetailState(
    val streakType: StreakType,
    val days: List<Long>,
)

/**
 * State for the books read detail bottom sheet.
 */
data class BooksReadDetailState(
    val books: List<BookReadingStatsUiModel>,
    val isLoading: Boolean = false,
    val error: AppError? = null,
    val isCancelled: Boolean = false,
)

/**
 * State for the sessions detail bottom sheet.
 */
data class SessionsDetailState(
    val sessions: List<ReadingSessionUiModel>,
    val totalSessions: Long,
    val isLoading: Boolean = false,
    val error: AppError? = null,
    val isCancelled: Boolean = false,
)

/**
 * Type of streak being displayed.
 */
enum class StreakType {
    CURRENT,
    LONGEST,
}
