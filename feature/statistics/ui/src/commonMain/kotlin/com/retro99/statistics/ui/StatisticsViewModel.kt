package com.retro99.statistics.ui

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.fold
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
import kotlinx.coroutines.Job
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
    private var activeDetailRequest: StatisticsDetailLoadRequest? = null
    private var detailLoadJob: Job? = null

    init {
        loadStatistics()
    }

    override fun onIntent(intent: StatisticsIntent) {
        when (intent) {
            StatisticsIntent.OnRefresh -> loadStatistics()
            StatisticsIntent.OnBackClicked -> {
                cancelActiveDetailRequest("navigation_back")
                onBack()
            }
            is StatisticsIntent.OnPeriodClicked -> {
                if (activeDetailRequest == null) {
                    analytics.logEvent(
                        StatisticsAnalyticsEvent.StatisticsPeriodChanged(period = intent.period.name),
                    )
                    loadBooksForPeriod(intent.period)
                }
            }
            StatisticsIntent.OnCurrentStreakClicked -> {
                if (viewState.value.statistics != null) {
                    analytics.logEvent(
                        StatisticsAnalyticsEvent.StatisticsDetailShown(detailType = "current_streak"),
                    )
                    showCurrentStreak()
                }
            }
            StatisticsIntent.OnLongestStreakClicked -> {
                if (viewState.value.statistics != null) {
                    analytics.logEvent(
                        StatisticsAnalyticsEvent.StatisticsDetailShown(detailType = "longest_streak"),
                    )
                    showLongestStreak()
                }
            }
            StatisticsIntent.OnBooksReadClicked -> {
                if (activeDetailRequest == null) {
                    analytics.logEvent(StatisticsAnalyticsEvent.StatisticsDetailShown(detailType = "books_read"))
                    showBooksRead()
                }
            }
            StatisticsIntent.OnTotalSessionsClicked -> {
                if (activeDetailRequest == null) {
                    analytics.logEvent(
                        StatisticsAnalyticsEvent.StatisticsDetailShown(detailType = "recent_sessions"),
                    )
                    showRecentSessions()
                }
            }
            StatisticsIntent.OnRetryDetail -> retryDetailLoad()
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

    private fun loadBooksForPeriod(period: StatisticsPeriod, isRetry: Boolean = false) {
        loadDetail(
            detailType = "period_books",
            action = "load_period_books",
            period = period,
            isRetry = isRetry,
            query = { getBooksForPeriodUseCase(period) },
            showLoading = {
                updateState {
                    it.copy(
                        detailState = StatisticsDetailState(period, emptyList(), isLoading = true),
                        streakDetailState = null,
                        booksReadDetailState = null,
                        sessionsDetailState = null,
                    )
                }
            },
            showSuccess = { books ->
                updateState {
                    it.copy(
                        detailState = StatisticsDetailState(
                            period = period,
                            books = books.map { book -> book.toBookUiModel() },
                        ),
                    )
                }
            },
            showFailure = { error ->
                updateState {
                    it.copy(detailState = it.detailState?.copy(isLoading = false, error = error))
                }
            },
            showCancelled = {
                updateState {
                    it.copy(detailState = it.detailState?.copy(isLoading = false, isCancelled = true))
                }
            },
        )
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

    private fun showBooksRead(isRetry: Boolean = false) {
        loadDetail(
            detailType = "books_read",
            action = "load_books_read",
            isRetry = isRetry,
            query = { getAllBooksReadUseCase() },
            showLoading = {
                updateState {
                    it.copy(
                        detailState = null,
                        streakDetailState = null,
                        booksReadDetailState = BooksReadDetailState(emptyList(), isLoading = true),
                        sessionsDetailState = null,
                    )
                }
            },
            showSuccess = { books ->
                updateState {
                    it.copy(
                        booksReadDetailState = BooksReadDetailState(
                            books = books.map { book -> book.toBookUiModel() },
                        ),
                    )
                }
            },
            showFailure = { error ->
                updateState {
                    it.copy(booksReadDetailState = it.booksReadDetailState?.copy(isLoading = false, error = error))
                }
            },
            showCancelled = {
                updateState {
                    it.copy(booksReadDetailState = it.booksReadDetailState?.copy(isLoading = false, isCancelled = true))
                }
            },
        )
    }

    private fun showRecentSessions(isRetry: Boolean = false) {
        val totalSessions = viewState.value.statistics?.totalSessions ?: 0L
        loadDetail(
            detailType = "recent_sessions",
            action = "load_recent_sessions",
            isRetry = isRetry,
            query = { getRecentSessionsUseCase() },
            showLoading = {
                updateState {
                    it.copy(
                        detailState = null,
                        streakDetailState = null,
                        booksReadDetailState = null,
                        sessionsDetailState = SessionsDetailState(
                            sessions = emptyList(),
                            totalSessions = totalSessions,
                            isLoading = true,
                        ),
                    )
                }
            },
            showSuccess = { sessions ->
                updateState {
                    it.copy(
                        sessionsDetailState = SessionsDetailState(
                            sessions = sessions.map { session -> session.toSessionUiModel() },
                            totalSessions = totalSessions,
                        ),
                    )
                }
            },
            showFailure = { error ->
                updateState {
                    it.copy(sessionsDetailState = it.sessionsDetailState?.copy(isLoading = false, error = error))
                }
            },
            showCancelled = {
                updateState {
                    it.copy(sessionsDetailState = it.sessionsDetailState?.copy(isLoading = false, isCancelled = true))
                }
            },
        )
    }

    private fun retryDetailLoad() {
        if (activeDetailRequest != null) return
        val state = viewState.value
        state.detailState?.takeIf { it.error != null || it.isCancelled }?.let {
            loadBooksForPeriod(it.period, isRetry = true)
            return
        }
        if (state.booksReadDetailState?.let { it.error != null || it.isCancelled } == true) {
            showBooksRead(isRetry = true)
            return
        }
        if (state.sessionsDetailState?.let { it.error != null || it.isCancelled } == true) {
            showRecentSessions(isRetry = true)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun <T> loadDetail(
        detailType: String,
        action: String,
        period: StatisticsPeriod? = null,
        isRetry: Boolean = false,
        query: suspend () -> com.retro99.base.result.AppResult<T>,
        showLoading: () -> Unit,
        showSuccess: (T) -> Unit,
        showFailure: (AppError) -> Unit,
        showCancelled: () -> Unit,
    ) {
        if (activeDetailRequest != null) return
        val request = StatisticsDetailLoadRequest(
            detailType = detailType,
            action = action,
            period = period?.name,
            isRetry = isRetry,
            correlationId = Uuid.random().toString(),
        )
        activeDetailRequest = request
        showLoading()
        analytics.logEvent(
            StatisticsAnalyticsEvent.StatisticsDetailLoadAttempted(
                action = request.action,
                detailType = request.detailType,
                period = request.period,
                isRetry = request.isRetry,
            ),
        )
        analytics.logBreadcrumb(request.context(stage = "started", outcome = "started"))

        detailLoadJob = viewModelScope.launch {
            val result = try {
                query()
            } catch (cancellation: CancellationException) {
                finishDetailCancellation(request, "query_cancelled", showCancelled)
                throw cancellation
            } catch (throwable: Exception) {
                Err(AppError.UnknownError(throwable))
            }

            if (activeDetailRequest != request) return@launch
            activeDetailRequest = null
            detailLoadJob = null
            result.fold(
                success = { value ->
                    showSuccess(value)
                    analytics.logEvent(
                        StatisticsAnalyticsEvent.StatisticsDetailLoadSucceeded(
                            action = request.action,
                            detailType = request.detailType,
                            period = request.period,
                            isRetry = request.isRetry,
                        ),
                    )
                    analytics.logBreadcrumb(request.context(stage = "terminal", outcome = "succeeded"))
                },
                failure = { error ->
                    val reasonCode = error.statisticsReasonCode()
                    val context = request.context(
                        stage = "terminal",
                        outcome = "failed",
                        reasonCode = reasonCode,
                    )
                    analytics.logEvent(
                        StatisticsAnalyticsEvent.StatisticsDetailLoadFailed(
                            action = request.action,
                            detailType = request.detailType,
                            period = request.period,
                            isRetry = request.isRetry,
                            reasonCode = reasonCode,
                        ),
                    )
                    analytics.logBreadcrumb(context)
                    error.log(analytics, context)
                    showFailure(error)
                },
            )
        }
    }

    private fun finishDetailCancellation(
        request: StatisticsDetailLoadRequest,
        reasonCode: String,
        showCancelled: () -> Unit,
    ) {
        if (activeDetailRequest != request) return
        activeDetailRequest = null
        detailLoadJob = null
        analytics.logEvent(
            StatisticsAnalyticsEvent.StatisticsDetailLoadCancelled(
                action = request.action,
                detailType = request.detailType,
                period = request.period,
                isRetry = request.isRetry,
                reasonCode = reasonCode,
            ),
        )
        analytics.logBreadcrumb(
            request.context(stage = "terminal", outcome = "cancelled", reasonCode = reasonCode),
        )
        showCancelled()
    }

    private fun dismissDetail() {
        cancelActiveDetailRequest("detail_dismissed")
        updateState {
            it.copy(
                detailState = null,
                streakDetailState = null,
                booksReadDetailState = null,
                sessionsDetailState = null,
            )
        }
    }

    private fun cancelActiveDetailRequest(reasonCode: String) {
        val request = activeDetailRequest ?: return
        activeDetailRequest = null
        detailLoadJob?.cancel()
        detailLoadJob = null
        analytics.logEvent(
            StatisticsAnalyticsEvent.StatisticsDetailLoadCancelled(
                action = request.action,
                detailType = request.detailType,
                period = request.period,
                isRetry = request.isRetry,
                reasonCode = reasonCode,
            ),
        )
        analytics.logBreadcrumb(
            request.context(stage = "terminal", outcome = "cancelled", reasonCode = reasonCode),
        )
    }
}

private data class StatisticsDetailLoadRequest(
    val detailType: String,
    val action: String,
    val period: String?,
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
        operation = "statistics_detail_load",
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        correlationId = correlationId,
    )
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
