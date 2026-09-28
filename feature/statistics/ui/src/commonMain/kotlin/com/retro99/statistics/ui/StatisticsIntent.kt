package com.retro99.statistics.ui

import com.retro99.base.ui.BaseIntent
import com.retro99.statistics.domain.model.StatisticsPeriod

sealed interface StatisticsIntent : BaseIntent {
    data object OnRefresh : StatisticsIntent
    data object OnBackClicked : StatisticsIntent
    data class OnPeriodClicked(val period: StatisticsPeriod) : StatisticsIntent
    data object OnCurrentStreakClicked : StatisticsIntent
    data object OnLongestStreakClicked : StatisticsIntent
    data object OnBooksReadClicked : StatisticsIntent
    data object OnTotalSessionsClicked : StatisticsIntent
    data object OnRetryDetail : StatisticsIntent
    data object OnDismissDetail : StatisticsIntent
}
