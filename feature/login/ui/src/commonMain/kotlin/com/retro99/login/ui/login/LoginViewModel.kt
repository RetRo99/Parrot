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
import com.retro99.login.domain.ServerProbeResult
import com.retro99.login.domain.usecase.LoginUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
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
    @InjectedParam private val onSignInAttemptStarted: (String, String, String) -> Unit = { _, _, _ -> },
    @InjectedParam private val onSignInFailure: (String, String, String) -> Unit = { _, _, _ -> },
    @InjectedParam private val onBackClick: () -> Unit,
    @InjectedParam private val existingServerId: String? = null,
    @InjectedParam isRetryOrigin: Boolean = false,
    @InjectedParam private val draft: LoginDraft = LoginDraft(),
) : BaseViewModel<LoginViewState, LoginIntent>(
    LoginViewState(
        selectedServerType = if (existingServerId == null) {
            draft.serverType
        } else {
            ServerType.Storyteller
        },
    ),
) {

    val urlState = TextFieldState()
    val usernameState = TextFieldState()
    val passwordState = TextFieldState()
    private var lastFailedLogin: Pair<String, String>? = null
    private val retryAttribution = LoginRetryAttribution(isRetryOrigin)
    private val loginSubmissionGate = LoginSubmissionGate()
    private val activeLoginAttempts = mutableMapOf<String, LoginAttempt>()
    private var activeServerTypePicker: ServerTypePickerAttempt? = null
    private var activeUrlHelpAttempt: UrlHelpAttempt? = null
    private val validationTelemetry = LoginValidationTelemetry(analytics)
    private var hasAttemptedCredentialsSubmit = false
    private var isExistingServerPrefillPending = existingServerId != null
    private var addressProbeJob: Job? = null
    private var lastProbeKey: Pair<String, ServerType>? = null
    private var lastSeenUrl = ""
    private var lastSeenCredentials: Pair<String, String> = "" to ""

    init {
        if (existingServerId == null) {
            urlState.edit { replace(0, length, draft.address) }
            usernameState.edit { replace(0, length, draft.username) }
        }
        observeTextFieldChanges()
        observeAddressForProbe()
        if (existingServerId == null) {
            updateFormState(
                url = urlState.text.toString(),
                username = usernameState.text.toString(),
                password = passwordState.text.toString(),
            )
        } else {
            updateState { it.copy(isLoading = true) }
            loadExistingServerConfig(existingServerId)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun loadExistingServerConfig(serverId: String) {
        val correlationId = Uuid.random().toString()
        val context = DiagnosticContext(
            screen = "login",
            sourceScreen = "server_management",
            entryPoint = "server_card_login",
            action = "load_server_config",
            operation = "existing_server_login",
            serverType = "unknown",
            correlationId = correlationId,
        )
        analytics.logBreadcrumb(context.copy(stage = "started", outcome = "started"))
        viewModelScope.launch {
            try {
                val server = loginUseCase.getServerConfig(serverId)
                if (server == null) {
                    isExistingServerPrefillPending = false
                    analytics.logBreadcrumb(
                        context.copy(
                            stage = "read_server_config",
                            outcome = "failed",
                            reasonCode = "server_unavailable",
                        ),
                    )
                    updateState {
                        it.copy(isLoading = false, serverConfigurationUnavailable = true)
                    }
                    return@launch
                }

                urlState.edit { replace(0, length, server.baseUrl) }
                updateState {
                    it.copy(
                        selectedServerType = server.type,
                        isLoading = false,
                        serverConfigurationUnavailable = false,
                    )
                }
                isExistingServerPrefillPending = false
                updateFormState(
                    url = urlState.text.toString(),
                    username = usernameState.text.toString(),
                    password = passwordState.text.toString(),
                )
                analytics.logBreadcrumb(
                    context.copy(
                        stage = "prefilled",
                        outcome = "succeeded",
                        serverType = server.type.identifier,
                    ),
                )
            } catch (cancellation: CancellationException) {
                analytics.logBreadcrumb(context.copy(stage = "read_server_config", outcome = "cancelled"))
                throw cancellation
            } catch (failure: Exception) {
                isExistingServerPrefillPending = false
                val failureContext = context.copy(
                    stage = "read_server_config",
                    outcome = "failed",
                    reasonCode = "server_config_load_failed",
                )
                analytics.logBreadcrumb(failureContext)
                analytics.logException(failure, failureContext)
                updateState {
                    it.copy(isLoading = false, serverConfigurationUnavailable = true)
                }
            }
        }
    }

    private fun observeTextFieldChanges() {
        snapshotFlow {
            Triple(
                urlState.text.toString(),
                usernameState.text.toString(),
                passwordState.text.toString(),
            )
        }.onEach { (url, username, password) ->
            if (!isExistingServerPrefillPending) {
                onFormTextChanged(url)
                updateFormState(url, username, password)
            }
        }.launchIn(viewModelScope)
    }

    private fun onFormTextChanged(url: String) {
        if (url != lastSeenUrl) {
            lastSeenUrl = url
            resetAddressCheck()
        }
        val credentials = usernameState.text.toString() to passwordState.text.toString()
        if (credentials != lastSeenCredentials) {
            lastSeenCredentials = credentials
            if (viewState.value.credentialsRejected) {
                updateState { currentState -> currentState.copy(credentialsRejected = false) }
            }
        }
    }

    private fun resetAddressCheck() {
        addressProbeJob?.cancel()
        lastProbeKey = null
        updateState { currentState ->
            if (currentState.addressCheck == AddressCheck.Idle &&
                currentState.unreachableOnSignIn == null
            ) {
                currentState
            } else {
                currentState.copy(
                    addressCheck = AddressCheck.Idle,
                    unreachableOnSignIn = null,
                )
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeAddressForProbe() {
        snapshotFlow { urlState.text.toString() }
            .debounce(ADDRESS_CHECK_DEBOUNCE_MS)
            .onEach { probeAddress() }
            .launchIn(viewModelScope)
    }

    private fun probeAddress() {
        if (isExistingServerPrefillPending) return
        val url = ServerAddress.normalize(urlState.text.toString())
        if (url.isEmpty() || !ServerAddress.isValid(url)) return
        val preferredType = viewState.value.selectedServerType
        val key = url to preferredType
        if (key == lastProbeKey) return
        lastProbeKey = key
        addressProbeJob?.cancel()
        updateState { currentState -> currentState.copy(addressCheck = AddressCheck.Checking) }
        addressProbeJob = viewModelScope.launch {
            val result = try {
                loginUseCase.probeServer(url, preferredType)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                ServerProbeResult.Unreachable
            }
            applyProbeResult(url, preferredType, result)
        }
    }

    private fun applyProbeResult(
        url: String,
        preferredType: ServerType,
        result: ServerProbeResult,
    ) {
        val host = ServerAddress.displayHost(url)
        val check = when (result) {
            is ServerProbeResult.Found -> AddressCheck.Found(
                serverType = result.serverType,
                host = host,
                switched = result.serverType != preferredType,
                supportsBrowserSignIn = result.supportsBrowserSignIn,
                isInsecure = ServerAddress.isInsecure(url),
            )
            ServerProbeResult.Unreachable -> AddressCheck.Unreachable(host)
            ServerProbeResult.NotSupported -> AddressCheck.NotSupported
        }
        val foundType = (result as? ServerProbeResult.Found)?.serverType
        if (foundType != null && foundType != preferredType) {
            lastProbeKey = url to foundType
        }
        updateState { currentState ->
            currentState.copy(
                addressCheck = check,
                selectedServerType = foundType ?: currentState.selectedServerType,
            )
        }
        updateFormState(
            url = urlState.text.toString(),
            username = usernameState.text.toString(),
            password = passwordState.text.toString(),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "login",
                action = "check_address",
                operation = "server_address_check",
                stage = "terminal",
                outcome = if (check is AddressCheck.Found) "succeeded" else "failed",
                reasonCode = when (check) {
                    is AddressCheck.Found -> if (check.switched) "server_type_switched" else null
                    is AddressCheck.Unreachable -> "server_unreachable"
                    AddressCheck.NotSupported -> "server_not_supported"
                    else -> null
                },
                serverType = (foundType ?: preferredType).identifier,
            ),
        )
    }

    private fun updateFormState(
        url: String,
        username: String,
        password: String,
    ) {
        if (existingServerId == null) {
            draft.address = url
            draft.username = username
            draft.serverType = viewState.value.selectedServerType
        }
        val urlError = validateUrl(url, showRequiredError = hasAttemptedCredentialsSubmit)
        val usernameError = if (hasAttemptedCredentialsSubmit && username.isBlank()) {
            LoginFieldError.Required
        } else {
            null
        }
        val passwordError = if (hasAttemptedCredentialsSubmit && password.isBlank()) {
            LoginFieldError.Required
        } else {
            null
        }
        validationTelemetry.onUrlValidationChanged(
            error = urlError,
            hasValidServerUrl = ServerAddress.isValid(ServerAddress.normalize(url)),
            serverType = viewState.value.selectedServerType,
        )
        updateState { currentState ->
            val allFieldsNotEmpty =
                url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
            val noErrors = urlError == null

            currentState.copy(
                urlError = urlError,
                usernameError = usernameError,
                passwordError = passwordError,
                isSignInEnabled = allFieldsNotEmpty && noErrors && !currentState.isLoading,
                isOAuthSignInEnabled = currentState.isOAuthVisible &&
                    ServerAddress.isValid(ServerAddress.normalize(url)) &&
                    !currentState.isLoading,
            )
        }
    }

    override fun onIntent(intent: LoginIntent) {
        when (intent) {
            LoginIntent.OnSignInClicked -> handleSignInClicked()
            LoginIntent.OnOAuthSignInClicked -> handleOAuthSignInClicked()
            LoginIntent.OnBackClicked -> onBackClick()
            LoginIntent.OnServerTypePickerOpened -> beginServerTypePickerAttempt()
            is LoginIntent.OnServerTypePickerDismissed -> cancelServerTypePicker(intent.reason.reasonCode)
            is LoginIntent.OnServerTypeSelected -> handleServerTypeSelected(intent.serverType)
            LoginIntent.OnUrlFocusLost -> probeAddress()
            LoginIntent.OnUrlHelpOpenRequested -> beginUrlHelpAttempt()
            LoginIntent.OnUrlHelpOpened -> markUrlHelpOpened()
            is LoginIntent.OnUrlHelpDismissed -> dismissUrlHelp(intent.reason.reasonCode)
            is LoginIntent.OnPasswordVisibilityChanged ->
                analytics.logEvent(AuthAnalyticsEvent.LoginPasswordVisibilityChanged(intent.isVisible))
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun beginServerTypePickerAttempt() {
        if (activeServerTypePicker != null) return
        val serverType = viewState.value.selectedServerType
        val attempt = ServerTypePickerAttempt(
            correlationId = Uuid.random().toString(),
        )
        activeServerTypePicker = attempt
        analytics.logEvent(AuthAnalyticsEvent.ServerTypePickerAttempted(serverType.identifier))
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "login",
                action = "select_server_type",
                operation = "server_type_picker",
                stage = "menu_open",
                outcome = "started",
                serverType = serverType.identifier,
                correlationId = attempt.correlationId,
            ),
        )
    }

    private fun handleServerTypeSelected(serverType: ServerType) {
        if (activeServerTypePicker == null) beginServerTypePickerAttempt()
        val attempt = activeServerTypePicker ?: return
        val previousServerType = viewState.value.selectedServerType
        updateState { currentState ->
            currentState.copy(selectedServerType = serverType)
        }
        updateFormState(
            url = urlState.text.toString(),
            username = usernameState.text.toString(),
            password = passwordState.text.toString(),
        )
        probeAddress()
        analytics.logEvent(
            AuthAnalyticsEvent.ServerTypeSelected(
                previousServerType = previousServerType.identifier,
                serverType = serverType.identifier,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "login",
                action = "select_server_type",
                operation = "server_type_picker",
                stage = "selection_applied",
                outcome = "succeeded",
                serverType = serverType.identifier,
                correlationId = attempt.correlationId,
            ),
        )
        activeServerTypePicker = null
    }

    private fun cancelServerTypePicker(reasonCode: String) {
        val attempt = activeServerTypePicker ?: return
        val serverType = viewState.value.selectedServerType
        analytics.logEvent(
            AuthAnalyticsEvent.ServerTypePickerCancelled(
                serverType = serverType.identifier,
                reasonCode = reasonCode,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "login",
                action = "select_server_type",
                operation = "server_type_picker",
                stage = "dismissed",
                outcome = "cancelled",
                reasonCode = reasonCode,
                serverType = serverType.identifier,
                correlationId = attempt.correlationId,
            ),
        )
        activeServerTypePicker = null
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun beginUrlHelpAttempt() {
        if (activeUrlHelpAttempt != null) return
        val attempt = UrlHelpAttempt(
            serverType = viewState.value.selectedServerType,
            correlationId = Uuid.random().toString(),
        )
        activeUrlHelpAttempt = attempt
        analytics.logEvent(AuthAnalyticsEvent.LoginUrlHelpAttempted(attempt.serverType.identifier))
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "login",
                action = "view_url_help",
                operation = "url_help_tooltip",
                stage = "started",
                outcome = "started",
                serverType = attempt.serverType.identifier,
                correlationId = attempt.correlationId,
            ),
        )
    }

    private fun markUrlHelpOpened() {
        if (activeUrlHelpAttempt == null) beginUrlHelpAttempt()
        val attempt = activeUrlHelpAttempt ?: return
        if (attempt.isOpened) return
        activeUrlHelpAttempt = attempt.copy(isOpened = true)
        analytics.logEvent(AuthAnalyticsEvent.LoginUrlHelpOpened(attempt.serverType.identifier))
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "login",
                action = "view_url_help",
                operation = "url_help_tooltip",
                stage = "visible",
                outcome = "succeeded",
                serverType = attempt.serverType.identifier,
                correlationId = attempt.correlationId,
            ),
        )
    }

    private fun dismissUrlHelp(reasonCode: String) {
        val attempt = activeUrlHelpAttempt ?: return
        analytics.logEvent(
            AuthAnalyticsEvent.LoginUrlHelpDismissed(
                serverType = attempt.serverType.identifier,
                reasonCode = reasonCode,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "login",
                action = "view_url_help",
                operation = "url_help_tooltip",
                stage = "dismissed",
                outcome = "cancelled",
                reasonCode = reasonCode,
                serverType = attempt.serverType.identifier,
                correlationId = attempt.correlationId,
            ),
        )
        activeUrlHelpAttempt = null
    }

    private fun handleSignInClicked() {
        if (viewState.value.isLoading || viewState.value.serverConfigurationUnavailable) return
        val url = ServerAddress.normalize(urlState.text.toString())
        val serverType = viewState.value.selectedServerType
        val username = usernameState.text.toString()
        val password = passwordState.text.toString()
        hasAttemptedCredentialsSubmit = true
        updateFormState(url, username, password)

        val validationState = viewState.value
        if (
            validationState.urlError != null ||
            validationState.usernameError != null ||
            validationState.passwordError != null
        ) {
            if (validationState.urlError != LoginFieldError.InvalidUrl) {
                validationTelemetry.onRequiredFieldsMissing(serverType)
            }
            return
        }
        if (!loginSubmissionGate.tryStart()) return

        val attempt = beginLoginAttempt(serverType, authMethod = "credentials")

        updateState {
            it.copy(
                isLoading = true,
                isOAuthInProgress = false,
                isSignInEnabled = false,
                isOAuthSignInEnabled = false,
                loginError = null,
                credentialsRejected = false,
                unreachableOnSignIn = null,
            )
        }

        viewModelScope.launch {
            performLoginSafely {
                loginUseCase(
                    serverType = serverType,
                    serverUrl = url,
                    username = username,
                    password = password,
                    existingServerId = existingServerId,
                )
            }.fold(
                success = {
                    completeLogin(attempt)
                    draft.clear()
                    onSignInSuccess()
                },
                failure = { error ->
                    failLoginAttempt(
                        error = error,
                        attempt = attempt,
                    )
                    propagateExistingServerLoginFailure(
                        existingServerId = existingServerId,
                        error = error,
                        serverType = attempt.serverType,
                        correlationId = attempt.correlationId,
                        onFailure = onSignInFailure,
                    )
                    updateAfterLoginFailure(error)
                },
            )
        }
    }

    private fun handleOAuthSignInClicked() {
        if (viewState.value.isLoading || viewState.value.serverConfigurationUnavailable) return
        if (!loginSubmissionGate.tryStart()) return
        val url = ServerAddress.normalize(urlState.text.toString())
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
            performLoginSafely {
                loginUseCase.withOAuth(
                    serverType = serverType,
                    serverUrl = url,
                    existingServerId = existingServerId,
                )
            }.fold(
                success = {
                    completeLogin(attempt)
                    onSignInSuccess()
                },
                failure = { error ->
                    failLoginAttempt(
                        error = error,
                        attempt = attempt,
                    )
                    propagateExistingServerLoginFailure(
                        existingServerId = existingServerId,
                        error = error,
                        serverType = attempt.serverType,
                        correlationId = attempt.correlationId,
                        onFailure = onSignInFailure,
                    )
                    updateAfterLoginFailure(error)
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
            isRetry = retryAttribution.consume(
                serverTypeId = serverType.identifier,
                authMethod = authMethod,
                lastFailedLogin = lastFailedLogin,
            ),
        )
        activeLoginAttempts[attempt.correlationId] = attempt
        existingServerId?.let { serverId ->
            onSignInAttemptStarted(serverId, serverType.identifier, attempt.correlationId)
        }
        analytics.logEvent(
            AuthAnalyticsEvent.LoginAttempted(
                serverType = serverType.identifier,
                authMethod = authMethod,
                isRetry = attempt.isRetry,
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
                isRetry = attempt.isRetry,
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
                    isRetry = attempt.isRetry,
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
                    isRetry = attempt.isRetry,
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
                    isRetry = attempt.isRetry,
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
        dismissUrlHelp(UrlHelpDismissalReason.ScreenExit.reasonCode)
        super.onCleared()
    }

    private data class LoginAttempt(
        val serverType: ServerType,
        val authMethod: String,
        val startedAt: TimeMark,
        val correlationId: String,
        val isRetry: Boolean,
    )

    private data class ServerTypePickerAttempt(
        val correlationId: String,
    )

    private data class UrlHelpAttempt(
        val serverType: ServerType,
        val correlationId: String,
        val isOpened: Boolean = false,
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

    private fun updateAfterLoginFailure(error: AppError) {
        val isInvalidCredentials = (error as? AppError.AuthError)?.isInvalidCredentials == true
        val isUnreachable = error is AppError.NetworkError &&
            (error.isConnectivity || error.isTimeout)
        val host = ServerAddress.displayHost(ServerAddress.normalize(urlState.text.toString()))
        updateState { currentState ->
            val nextFocusId = (currentState.focusRequest?.id ?: 0) + 1
            currentState.copy(
                isLoading = false,
                isOAuthInProgress = false,
                loginError = if (isInvalidCredentials || isUnreachable) null else error.message,
                credentialsRejected = isInvalidCredentials,
                unreachableOnSignIn = if (isUnreachable) host else null,
                focusRequest = when {
                    isInvalidCredentials ->
                        LoginFocusRequest(LoginField.Password, nextFocusId)
                    isUnreachable && existingServerId == null ->
                        LoginFocusRequest(LoginField.Address, nextFocusId)
                    else -> currentState.focusRequest
                },
            )
        }
        updateFormState(
            url = urlState.text.toString(),
            username = usernameState.text.toString(),
            password = passwordState.text.toString(),
        )
    }

    private fun validateUrl(url: String, showRequiredError: Boolean): LoginFieldError? {
        val normalizedUrl = ServerAddress.normalize(url)
        if (normalizedUrl.isEmpty()) {
            return if (showRequiredError) LoginFieldError.Required else null
        }
        return if (ServerAddress.isValid(normalizedUrl)) null else LoginFieldError.InvalidUrl
    }

    private companion object {
        const val ADDRESS_CHECK_DEBOUNCE_MS = 600L
    }
}
