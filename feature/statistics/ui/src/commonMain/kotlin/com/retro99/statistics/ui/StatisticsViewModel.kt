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
import com.retro99.statistics.domain.model.StatisticsRange
import com.retro99.statistics.domain.usecase.GetStatisticsOverviewUseCase
import com.retro99.base.ui.platform.firstDayOfWeek
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.reader.domain.recap.RecapEngineSelector
import com.retro99.reader.domain.recap.RecapRepository
import com.retro99.reader.domain.recap.RecapRetryResult
import com.retro99.reader.domain.recap.RecapSettings
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import com.retro99.statistics.domain.usecase.GetAllBooksReadUseCase
import com.retro99.statistics.domain.usecase.GetBooksForPeriodUseCase
import com.retro99.statistics.domain.usecase.GetReadingStatisticsUseCase
import com.retro99.statistics.domain.usecase.GetRecentSessionsUseCase
import com.retro99.statistics.ui.model.toBookUiModel
import com.retro99.statistics.ui.model.toSessionUiModel
import com.retro99.statistics.ui.model.toUiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    @Provided private val getStatisticsOverviewUseCase: GetStatisticsOverviewUseCase,
    @Provided private val preferences: Preferences,
    @Provided private val analytics: Analytics,
    @Provided private val recapRepository: RecapRepository,
    @Provided private val recapSettings: RecapSettings,
    @Provided private val recapEngineSelector: RecapEngineSelector,
) : BaseViewModel<StatisticsViewState, StatisticsIntent>(StatisticsViewState()) {

    private var statisticsLoadInProgress = false
    private var statisticsLoadFailed = false
    private var hasStatisticsLoadCompleted = false
    private var activeDetailRequest: StatisticsDetailLoadRequest? = null
    private var detailLoadJob: Job? = null

    private var overviewJob: Job? = null
    private var sessionRecapJob: Job? = null

    init {
        val storedRange = StatisticsRange.entries.firstOrNull { range ->
            range.name == preferences.getStringOrNull(PreferencesKey.StatisticsRange)
        }
        if (storedRange != null) {
            updateState { it.copy(range = storedRange) }
        }
        loadStatistics()
        loadOverview()
    }

    override fun onIntent(intent: StatisticsIntent) {
        when (intent) {
            StatisticsIntent.OnRefresh -> {
                loadStatistics()
                loadOverview()
            }
            is StatisticsIntent.OnRangeSelected -> selectRange(intent.range)
            is StatisticsIntent.OnBucketSelected -> {
                updateState { it.copy(selectedBucketIndex = intent.index) }
            }
            is StatisticsIntent.OnStreakMonthShifted -> shiftStreakMonth(intent.months)
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
            StatisticsIntent.OnCurrentStreakClicked,
            StatisticsIntent.OnLongestStreakClicked,
            -> showStreakSheet()
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
            is StatisticsIntent.OnSessionClicked -> showSessionDetail(intent.sessionId)
            StatisticsIntent.OnSessionDetailClosed -> closeSessionDetail()
            StatisticsIntent.OnRetryRecap -> retryRecap()
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
                        streakSheetState = null,
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

    private fun showStreakSheet() {
        val today = viewState.value.overview?.today ?: return
        analytics.logEvent(StatisticsAnalyticsEvent.StatisticsDetailShown(detailType = "streak"))
        updateState {
            it.copy(streakSheetState = StreakSheetState(LocalDate(today.year, today.month, 1)))
        }
    }

    private fun shiftStreakMonth(months: Int) {
        val overview = viewState.value.overview ?: return
        val sheet = viewState.value.streakSheetState ?: return
        val shifted = sheet.calendarMonth.plus(months, DateTimeUnit.MONTH)
        val currentMonth = LocalDate(overview.today.year, overview.today.month, 1)
        if (shifted > currentMonth) return
        updateState { it.copy(streakSheetState = StreakSheetState(shifted)) }
    }

    private fun selectRange(range: StatisticsRange) {
        if (range == viewState.value.range) return
        preferences.putString(PreferencesKey.StatisticsRange, range.name)
        analytics.logEvent(StatisticsAnalyticsEvent.StatisticsPeriodChanged(period = range.name))
        updateState { it.copy(range = range, selectedBucketIndex = null) }
        loadOverview()
    }

    /** Loads the chart, totals and streaks for the selected range. Quiet on failure. */
    private fun loadOverview() {
        overviewJob?.cancel()
        val range = viewState.value.range
        overviewJob = viewModelScope.launch {
            val result = try {
                getStatisticsOverviewUseCase(range, firstDayOfWeek())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Err(AppError.UnknownError(throwable))
            }
            result.fold(
                success = { overview ->
                    updateState { it.copy(overview = overview, selectedBucketIndex = null) }
                },
                failure = { error ->
                    updateState { it.copy(error = error, isLoading = false) }
                },
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
                        streakSheetState = null,
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
                        streakSheetState = null,
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

    /** Observes the stored recap only; generation is the job runner's job. */
    private fun showSessionDetail(sessionId: Long) {
        val sessions = viewState.value.sessionsDetailState ?: return
        val session = sessions.sessions.firstOrNull { it.id == sessionId } ?: return
        analytics.logEvent(StatisticsAnalyticsEvent.StatisticsDetailShown(detailType = "session"))
        updateSessionDetail { SessionDetailState(session) }
        sessionRecapJob?.cancel()
        val recap = session.recapSessionId?.let(recapRepository::observeRecap) ?: flowOf(null)
        sessionRecapJob = combine(
            recap,
            recapSettings.observeCloudRecapsEnabled(),
            recapEngineSelector.observeAvailable(),
        ) { stored, enabled, available ->
            stored.toSessionRecapUiState(cloudRecapsEnabled = enabled, engineAvailable = available)
        }
            .onEach { state ->
                updateSessionDetail { detail ->
                    // A refused retry only holds for the state it was refused in.
                    detail?.copy(
                        recap = state,
                        retryUnavailable = detail.retryUnavailable && detail.recap == state,
                    )
                }
            }
            .catch { error ->
                analytics.logException(error, sessionRecapContext(stage = "observe"))
                updateSessionDetail { it?.copy(recap = SessionRecapUiState.None(cloudRecapsEnabled = true)) }
            }
            .launchIn(viewModelScope)
    }

    private fun closeSessionDetail() {
        sessionRecapJob?.cancel()
        sessionRecapJob = null
        updateSessionDetail { null }
    }

    private fun retryRecap() {
        val detail = viewState.value.sessionsDetailState?.selected ?: return
        val recapSessionId = detail.session.recapSessionId ?: return
        if (detail.isRetrying) return
        updateSessionDetail { it?.copy(isRetrying = true, retryUnavailable = false) }
        viewModelScope.launch {
            val result = try {
                recapRepository.retry(recapSessionId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                analytics.logException(error, sessionRecapContext(stage = "retry"))
                // Unknown outcome: leave the button so the user can try again.
                null
            }
            updateSessionDetail { current ->
                // The user may have opened another session meanwhile.
                current?.takeIf { it.session.recapSessionId == recapSessionId }?.copy(
                    isRetrying = false,
                    retryUnavailable = result != null && result != RecapRetryResult.QUEUED,
                ) ?: current
            }
        }
    }

    private fun updateSessionDetail(transform: (SessionDetailState?) -> SessionDetailState?) {
        updateState { state ->
            val sessions = state.sessionsDetailState ?: return@updateState state
            state.copy(sessionsDetailState = sessions.copy(selected = transform(sessions.selected)))
        }
    }

    private fun sessionRecapContext(stage: String) = DiagnosticContext(
        screen = "statistics",
        action = "session_recap",
        operation = "session_recap",
        stage = stage,
        outcome = "failed",
        reasonCode = "recap_${stage}_failed",
    )

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
        sessionRecapJob?.cancel()
        sessionRecapJob = null
        updateState {
            it.copy(
                detailState = null,
                streakSheetState = null,
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
