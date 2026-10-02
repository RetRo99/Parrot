package com.retro99.statistics.ui

import com.retro99.base.ui.BaseIntent
import com.retro99.statistics.domain.model.StatisticsPeriod
import com.retro99.statistics.domain.model.StatisticsRange

sealed interface StatisticsIntent : BaseIntent {
    data object OnRefresh : StatisticsIntent
    data object OnBackClicked : StatisticsIntent
    data class OnPeriodClicked(val period: StatisticsPeriod) : StatisticsIntent
    data class OnRangeSelected(val range: StatisticsRange) : StatisticsIntent
    data class OnBucketSelected(val index: Int) : StatisticsIntent
    data class OnStreakMonthShifted(val months: Int) : StatisticsIntent
    data object OnCurrentStreakClicked : StatisticsIntent
    data object OnLongestStreakClicked : StatisticsIntent
    data object OnBooksReadClicked : StatisticsIntent
    data object OnTotalSessionsClicked : StatisticsIntent
    data object OnRetryDetail : StatisticsIntent
    data object OnDismissDetail : StatisticsIntent
    data class OnSessionClicked(val sessionId: Long) : StatisticsIntent
    data object OnSessionDetailClosed : StatisticsIntent
    data object OnRetryRecap : StatisticsIntent
}
