package com.retro99.statistics.ui

import com.retro99.base.result.AppError
import com.retro99.statistics.domain.model.StatisticsOverview
import com.retro99.statistics.domain.model.StatisticsPeriod
import com.retro99.statistics.domain.model.StatisticsRange
import com.retro99.statistics.ui.model.BookReadingStatsUiModel
import com.retro99.statistics.ui.model.ReadingSessionUiModel
import com.retro99.statistics.ui.model.ReadingStatisticsUiModel
import kotlinx.datetime.LocalDate

data class StatisticsViewState(
    val statistics: ReadingStatisticsUiModel? = null,
    val isLoading: Boolean = true,
    val error: AppError? = null,
    val detailState: StatisticsDetailState? = null,
    val range: StatisticsRange = StatisticsRange.WEEK,
    val overview: StatisticsOverview? = null,
    /** Tapped chart bucket; null means the busiest one. */
    val selectedBucketIndex: Int? = null,
    val streakSheetState: StreakSheetState? = null,
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
 * State for the streak bottom sheet. [calendarMonth] is the first day of the month shown.
 */
data class StreakSheetState(
    val calendarMonth: LocalDate,
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
