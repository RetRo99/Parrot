package com.retro99.login.ui.login

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.fold
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AuthAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.result.AppError
import com.retro99.base.server.ServerType
import com.retro99.base.ui.BaseViewModel
import com.retro99.login.domain.usecase.LoginUseCase
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@KoinViewModel
class LoginViewModel(
    @Provided private val loginUseCase: LoginUseCase,
    @Provided private val analytics: Analytics,
    @InjectedParam private val onSignInSuccess: () -> Unit,
    @InjectedParam private val onBackClick: () -> Unit,
) : BaseViewModel<LoginViewState, LoginIntent>(LoginViewState()) {

    val urlState = TextFieldState(initialText = "https://")
    val usernameState = TextFieldState()
    val passwordState = TextFieldState()
    private var lastFailedLogin: Pair<String, String>? = null
    private val loginSubmissionGate = LoginSubmissionGate()
    private val activeLoginAttempts = mutableMapOf<String, LoginAttempt>()

    init {
        observeTextFieldChanges()
        updateFormState(
            url = urlState.text.toString(),
            username = usernameState.text.toString(),
            password = passwordState.text.toString(),
        )
    }

    private fun observeTextFieldChanges() {
        snapshotFlow {
            Triple(
                urlState.text.toString(),
                usernameState.text.toString(),
                passwordState.text.toString(),
            )
        }.onEach { (url, username, password) ->
            updateFormState(url, username, password)
        }.launchIn(viewModelScope)
    }

    private fun updateFormState(
        url: String,
        username: String,
        password: String,
    ) {
        updateState { currentState ->
            val urlError = validateUrl(url)

            val allFieldsNotEmpty =
                url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
            val noErrors = urlError == null

            currentState.copy(
                urlError = urlError,
                isSignInEnabled = allFieldsNotEmpty && noErrors && !currentState.isLoading,
                isOAuthSignInEnabled = currentState.isOAuthVisible && isValidServerUrl(url) && !currentState.isLoading,
            )
        }
    }

    override fun onIntent(intent: LoginIntent) {
        when (intent) {
            LoginIntent.OnSignInClicked -> handleSignInClicked()
            LoginIntent.OnOAuthSignInClicked -> handleOAuthSignInClicked()
            LoginIntent.OnBackClicked -> onBackClick()
            is LoginIntent.OnServerTypeSelected -> handleServerTypeSelected(intent.serverType)
        }
    }

    private fun handleServerTypeSelected(serverType: ServerType) {
        updateState { currentState ->
            currentState.copy(selectedServerType = serverType)
        }
        updateFormState(
            url = urlState.text.toString(),
            username = usernameState.text.toString(),
            password = passwordState.text.toString(),
        )
    }

    private fun handleSignInClicked() {
        if (!loginSubmissionGate.tryStart()) return
        val url = urlState.text.toString().trim()
        val serverType = viewState.value.selectedServerType
        val attempt = beginLoginAttempt(serverType, authMethod = "credentials")

        updateState {
            it.copy(
                isLoading = true,
                isOAuthInProgress = false,
                isSignInEnabled = false,
                isOAuthSignInEnabled = false,
                loginError = null,
            )
        }

        viewModelScope.launch {
            val username = usernameState.text.toString()
            val password = passwordState.text.toString()

            performLoginSafely { loginUseCase(serverType, url, username, password) }.fold(
                success = {
                    completeLogin(attempt)
                    onSignInSuccess()
                },
                failure = { error ->
                    failLoginAttempt(
                        error = error,
                        attempt = attempt,
                    )
                    updateAfterLoginFailure(error.message)
                },
            )
        }
    }

    private fun handleOAuthSignInClicked() {
        if (!loginSubmissionGate.tryStart()) return
        val url = urlState.text.toString().trim()
        val serverType = viewState.value.selectedServerType
        val attempt = beginLoginAttempt(serverType, authMethod = "oauth")

        updateState {
            it.copy(
                isLoading = true,
                isOAuthInProgress = true,
                isSignInEnabled = false,
                isOAuthSignInEnabled = false,
                loginError = null,
            )
        }

        viewModelScope.launch {
            performLoginSafely { loginUseCase.withOAuth(serverType, url) }.fold(
                success = {
                    completeLogin(attempt)
                    onSignInSuccess()
                },
                failure = { error ->
                    failLoginAttempt(
                        error = error,
                        attempt = attempt,
                    )
                    updateAfterLoginFailure(error.message)
                },
            )
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun beginLoginAttempt(serverType: ServerType, authMethod: String): LoginAttempt {
        val attempt = LoginAttempt(
            serverType = serverType,
            authMethod = authMethod,
            startedAt = TimeSource.Monotonic.markNow(),
            correlationId = Uuid.random().toString(),
        )
        val isRetry = lastFailedLogin == (serverType.identifier to authMethod)
        activeLoginAttempts[attempt.correlationId] = attempt
        analytics.logEvent(
            AuthAnalyticsEvent.LoginAttempted(
                serverType = serverType.identifier,
                authMethod = authMethod,
                isRetry = isRetry,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "login",
                action = "sign_in",
                operation = "${authMethod}_login",
                stage = "started",
                outcome = "started",
                serverType = serverType.identifier,
                correlationId = attempt.correlationId,
            ),
        )
        return attempt
    }

    private fun completeLogin(attempt: LoginAttempt) {
        activeLoginAttempts.remove(attempt.correlationId)
        lastFailedLogin = null
        val durationMs = attempt.startedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
        clearLoginAnalyticsIdentity(analytics)
        analytics.logEvent(
            AuthAnalyticsEvent.LoginSucceeded(
                serverType = attempt.serverType.identifier,
                authMethod = attempt.authMethod,
                durationMs = durationMs,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "login",
                action = "sign_in",
                operation = "${attempt.authMethod}_login",
                stage = "credentials_persisted",
                outcome = "succeeded",
                serverType = attempt.serverType.identifier,
                correlationId = attempt.correlationId,
            ),
        )
    }

    private fun failLoginAttempt(
        error: AppError,
        attempt: LoginAttempt,
    ) {
        loginSubmissionGate.finish()
        activeLoginAttempts.remove(attempt.correlationId)
        lastFailedLogin = attempt.serverType.identifier to attempt.authMethod
        val durationMs = attempt.startedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
        val isCancelled = (error as? AppError.AuthError)?.isCancellation == true
        if (isCancelled) {
            val reasonCode = if (attempt.authMethod == "oauth") "oauth_cancelled" else "login_cancelled"
            analytics.logEvent(
                AuthAnalyticsEvent.LoginCancelled(
                    serverType = attempt.serverType.identifier,
                    authMethod = attempt.authMethod,
                    reasonCode = reasonCode,
                    durationMs = durationMs,
                ),
            )
            analytics.logBreadcrumb(
                DiagnosticContext(
                    screen = "login",
                    action = "sign_in",
                    operation = "${attempt.authMethod}_login",
                    stage = if (attempt.authMethod == "oauth") "oauth_callback" else "authentication",
                    outcome = "cancelled",
                    reasonCode = reasonCode,
                    serverType = attempt.serverType.identifier,
                    correlationId = attempt.correlationId,
                ),
            )
        } else {
            val errorType = loginErrorType(error)
            analytics.logEvent(
                AuthAnalyticsEvent.LoginFailed(
                    serverType = attempt.serverType.identifier,
                    authMethod = attempt.authMethod,
                    errorType = errorType,
                    durationMs = durationMs,
                ),
            )
            analytics.logBreadcrumb(
                DiagnosticContext(
                    screen = "login",
                    action = "sign_in",
                    operation = "${attempt.authMethod}_login",
                    stage = loginFailureDiagnosticStage(error),
                    outcome = "failed",
                    reasonCode = loginFailureDiagnosticReasonCode(error),
                    serverType = attempt.serverType.identifier,
                    correlationId = attempt.correlationId,
                ),
            )
        }
        reportUnexpectedLoginFailure(
            analytics = analytics,
            error = error,
            serverType = attempt.serverType,
            authMethod = attempt.authMethod,
            correlationId = attempt.correlationId,
        )
    }

    override fun onCleared() {
        activeLoginAttempts.values.forEach { attempt ->
            val durationMs = attempt.startedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
            analytics.logEvent(
                AuthAnalyticsEvent.LoginAbandoned(
                    serverType = attempt.serverType.identifier,
                    authMethod = attempt.authMethod,
                    reasonCode = "left_login_screen",
                    durationMs = durationMs,
                ),
            )
            analytics.logBreadcrumb(
                DiagnosticContext(
                    screen = "login",
                    action = "sign_in",
                    operation = "${attempt.authMethod}_login",
                    stage = "screen_exit",
                    outcome = "abandoned",
                    reasonCode = "left_login_screen",
                    serverType = attempt.serverType.identifier,
                    correlationId = attempt.correlationId,
                ),
            )
        }
        activeLoginAttempts.clear()
        super.onCleared()
    }

    private data class LoginAttempt(
        val serverType: ServerType,
        val authMethod: String,
        val startedAt: TimeMark,
        val correlationId: String,
    )

    private fun loginErrorType(error: AppError): String = when (error) {
        is AppError.NetworkError -> when {
            error.isConnectivity -> "network_unavailable"
            error.isTimeout -> "network_timeout"
            else -> "network_failure"
        }
        is AppError.ApiError -> "api_failure"
        is AppError.DatabaseError -> "database_failure"
        is AppError.UnknownError -> "unexpected_failure"
        is AppError.AuthError -> "auth_failure"
        is AppError.NotFoundError -> "not_found"
    }

    private fun updateAfterLoginFailure(errorMessage: String?) {
        updateState {
            it.copy(
                isLoading = false,
                isOAuthInProgress = false,
                loginError = errorMessage,
            )
        }
        updateFormState(
            url = urlState.text.toString(),
            username = usernameState.text.toString(),
            password = passwordState.text.toString(),
        )
    }

    private fun validateUrl(url: String): LoginFieldError? {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank() || trimmedUrl == "https://" || trimmedUrl == "http://") {
            return null
        }
        return if (isValidServerUrl(trimmedUrl)) null else LoginFieldError.InvalidUrl
    }

    private fun isValidServerUrl(url: String): Boolean {
        val trimmedUrl = url.trim()
        val schemeSeparator = trimmedUrl.indexOf("://")
        if (schemeSeparator <= 0) return false

        val scheme = trimmedUrl.substring(0, schemeSeparator).lowercase()
        if (scheme != "http" && scheme != "https") return false

        val authority = trimmedUrl
            .substring(schemeSeparator + 3)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')

        if (authority.isBlank()) return false

        val host = when {
            authority.startsWith('[') -> authority.substringAfter('[').substringBefore(']')
            authority.count { it == ':' } == 1 -> authority.substringBefore(':')
            else -> authority
        }

        return host.isNotBlank()
    }
}
