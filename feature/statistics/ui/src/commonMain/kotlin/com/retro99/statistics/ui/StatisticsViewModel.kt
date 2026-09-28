package com.retro99.statistics.ui

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.StatisticsAnalyticsEvent
import com.retro99.base.result.AppError
import com.retro99.base.result.log
import com.retro99.base.ui.BaseViewModel
import com.retro99.statistics.domain.model.StatisticsPeriod
import com.retro99.statistics.domain.usecase.GetAllBooksReadUseCase
import com.retro99.statistics.domain.usecase.GetBooksForPeriodUseCase
import com.retro99.statistics.domain.usecase.GetReadingStatisticsUseCase
import com.retro99.statistics.domain.usecase.GetRecentSessionsUseCase
import com.retro99.statistics.ui.model.toBookUiModel
import com.retro99.statistics.ui.model.toSessionUiModel
import com.retro99.statistics.ui.model.toUiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@KoinViewModel
class StatisticsViewModel(
    @InjectedParam private val onBack: () -> Unit,
    @Provided private val getReadingStatisticsUseCase: GetReadingStatisticsUseCase,
    @Provided private val getBooksForPeriodUseCase: GetBooksForPeriodUseCase,
    @Provided private val getAllBooksReadUseCase: GetAllBooksReadUseCase,
    @Provided private val getRecentSessionsUseCase: GetRecentSessionsUseCase,
    @Provided private val analytics: Analytics,
) : BaseViewModel<StatisticsViewState, StatisticsIntent>(StatisticsViewState()) {

    private var statisticsLoadInProgress = false
    private var statisticsLoadFailed = false
    private var hasStatisticsLoadCompleted = false

    init {
        analytics.logEvent(StatisticsAnalyticsEvent.StatisticsViewed)
        loadStatistics()
    }

    override fun onIntent(intent: StatisticsIntent) {
        when (intent) {
            StatisticsIntent.OnRefresh -> loadStatistics()
            StatisticsIntent.OnBackClicked -> onBack()
            is StatisticsIntent.OnPeriodClicked -> {
                analytics.logEvent(StatisticsAnalyticsEvent.StatisticsPeriodChanged(period = intent.period.name))
                loadBooksForPeriod(intent.period)
            }
            StatisticsIntent.OnCurrentStreakClicked -> {
                analytics.logEvent(StatisticsAnalyticsEvent.StatisticsDetailShown(detailType = "current_streak"))
                showCurrentStreak()
            }
            StatisticsIntent.OnLongestStreakClicked -> {
                analytics.logEvent(StatisticsAnalyticsEvent.StatisticsDetailShown(detailType = "longest_streak"))
                showLongestStreak()
            }
            StatisticsIntent.OnBooksReadClicked -> {
                analytics.logEvent(StatisticsAnalyticsEvent.StatisticsDetailShown(detailType = "books_read"))
                showBooksRead()
            }
            StatisticsIntent.OnTotalSessionsClicked -> {
                analytics.logEvent(StatisticsAnalyticsEvent.StatisticsDetailShown(detailType = "recent_sessions"))
                showRecentSessions()
            }
            StatisticsIntent.OnDismissDetail -> dismissDetail()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun loadStatistics() {
        if (statisticsLoadInProgress) return

        val isRetry = statisticsLoadFailed
        val action = when {
            isRetry -> "retry_statistics"
            hasStatisticsLoadCompleted -> "refresh_statistics"
            else -> "load_statistics"
        }
        val request = StatisticsLoadRequest(
            action = action,
            isRetry = isRetry,
            correlationId = Uuid.random().toString(),
        )
        statisticsLoadInProgress = true
        analytics.logEvent(
            StatisticsAnalyticsEvent.StatisticsLoadAttempted(
                action = action,
                isRetry = isRetry,
            ),
        )
        analytics.logBreadcrumb(request.context(stage = "started", outcome = "started"))
        updateState { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val result = try {
                getReadingStatisticsUseCase().first()
            } catch (cancellation: CancellationException) {
                statisticsLoadInProgress = false
                throw cancellation
            } catch (throwable: Throwable) {
                Err(AppError.UnknownError(throwable))
            }

            result.fold(
                success = { statistics ->
                    statisticsLoadFailed = false
                    updateState {
                        it.copy(
                            statistics = statistics.toUiModel(),
                            isLoading = false,
                            error = null,
                        )
                    }
                    analytics.logEvent(
                        StatisticsAnalyticsEvent.StatisticsLoadSucceeded(
                            action = action,
                            isRetry = isRetry,
                        ),
                    )
                    analytics.logBreadcrumb(
                        request.context(stage = "terminal", outcome = "succeeded"),
                    )
                },
                failure = { error ->
                    statisticsLoadFailed = true
                    val reasonCode = error.statisticsReasonCode()
                    val context = request.context(
                        stage = "terminal",
                        outcome = "failed",
                        reasonCode = reasonCode,
                    )
                    analytics.logEvent(
                        StatisticsAnalyticsEvent.StatisticsLoadFailed(
                            action = action,
                            isRetry = isRetry,
                            reasonCode = reasonCode,
                        ),
                    )
                    analytics.logBreadcrumb(context)
                    error.log(analytics, context)
                    updateState {
                        it.copy(
                            isLoading = false,
                            error = error,
                        )
                    }
                },
            )
            statisticsLoadInProgress = false
            hasStatisticsLoadCompleted = true
        }
    }

    private fun loadBooksForPeriod(period: StatisticsPeriod) {
        // Show loading state immediately
        updateState {
            it.copy(
                detailState = StatisticsDetailState(
                    period = period,
                    books = emptyList(),
                    isLoading = true,
                )
            )
        }

        viewModelScope.launch {
            getBooksForPeriodUseCase(period)
                .onSuccess { books ->
                    updateState {
                        it.copy(
                            detailState = StatisticsDetailState(
                                period = period,
                                books = books.map { book -> book.toBookUiModel() },
                                isLoading = false,
                            )
                        )
                    }
                }
                .onFailure { error ->
                    error.log(analytics, "StatisticsViewModel: Failed to load books for period")
                    updateState { it.copy(detailState = null) }
                }
        }
    }

    private fun showCurrentStreak() {
        val currentStreakDays = viewState.value.statistics?.currentStreakDays ?: return
        updateState {
            it.copy(
                streakDetailState = StreakDetailState(
                    streakType = StreakType.CURRENT,
                    days = currentStreakDays,
                )
            )
        }
    }

    private fun showLongestStreak() {
        val longestStreakDays = viewState.value.statistics?.longestStreakDays ?: return
        updateState {
            it.copy(
                streakDetailState = StreakDetailState(
                    streakType = StreakType.LONGEST,
                    days = longestStreakDays,
                )
            )
        }
    }

    private fun showBooksRead() {
        updateState {
            it.copy(
                booksReadDetailState = BooksReadDetailState(
                    books = emptyList(),
                    isLoading = true,
                )
            )
        }

        viewModelScope.launch {
            getAllBooksReadUseCase()
                .onSuccess { books ->
                    updateState {
                        it.copy(
                            booksReadDetailState = BooksReadDetailState(
                                books = books.map { book -> book.toBookUiModel() },
                                isLoading = false,
                            )
                        )
                    }
                }
                .onFailure { error ->
                    error.log(analytics, "StatisticsViewModel: Failed to load books read")
                    updateState { it.copy(booksReadDetailState = null) }
                }
        }
    }

    private fun showRecentSessions() {
        val totalSessions = viewState.value.statistics?.totalSessions ?: 0L
        updateState {
            it.copy(
                sessionsDetailState = SessionsDetailState(
                    sessions = emptyList(),
                    totalSessions = totalSessions,
                    isLoading = true,
                )
            )
        }

        viewModelScope.launch {
            getRecentSessionsUseCase()
                .onSuccess { sessions ->
                    updateState {
                        it.copy(
                            sessionsDetailState = SessionsDetailState(
                                sessions = sessions.map { session -> session.toSessionUiModel() },
                                totalSessions = totalSessions,
                                isLoading = false,
                            )
                        )
                    }
                }
                .onFailure { error ->
                    error.log(analytics, "StatisticsViewModel: Failed to load recent sessions")
                    updateState { it.copy(sessionsDetailState = null) }
                }
        }
    }

    private fun dismissDetail() {
        updateState {
            it.copy(
                detailState = null,
                streakDetailState = null,
                booksReadDetailState = null,
                sessionsDetailState = null,
            )
        }
    }
}

private data class StatisticsLoadRequest(
    val action: String,
    val isRetry: Boolean,
    val correlationId: String,
) {
    fun context(
        stage: String,
        outcome: String,
        reasonCode: String? = null,
    ) = DiagnosticContext(
        screen = "statistics",
        action = action,
        operation = "statistics_load",
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        correlationId = correlationId,
    )
}

private fun AppError.statisticsReasonCode(): String = when (this) {
    is AppError.DatabaseError -> "database_error"
    is AppError.UnknownError -> "unexpected_error"
    else -> "statistics_load_error"
}
