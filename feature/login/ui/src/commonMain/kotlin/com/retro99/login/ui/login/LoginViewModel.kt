package com.retro99.login.ui.login

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.fold
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AuthAnalyticsEvent
import com.retro99.base.result.log
import com.retro99.base.server.ServerType
import com.retro99.base.ui.BaseViewModel
import com.retro99.login.domain.usecase.LoginUseCase
import com.retro99.server.api.ServerRegistry
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class LoginViewModel(
    @Provided private val loginUseCase: LoginUseCase,
    @Provided private val analytics: Analytics,
    @Provided private val serverRegistry: ServerRegistry,
    @InjectedParam private val onSignInSuccess: () -> Unit,
    @InjectedParam private val onBackClick: () -> Unit,
    @InjectedParam private val existingServerId: String?,
) : BaseViewModel<LoginViewState, LoginIntent>(
    LoginViewState(
        isReauthentication = existingServerId != null,
        isServerConfigurationLoading = existingServerId != null,
    ),
) {

    val urlState = TextFieldState(initialText = if (existingServerId == null) "https://" else "")
    val usernameState = TextFieldState()
    val passwordState = TextFieldState()

    init {
        observeTextFieldChanges()
        if (existingServerId == null) {
            updateFormState(
                url = urlState.text.toString(),
                username = usernameState.text.toString(),
                password = passwordState.text.toString(),
            )
        } else {
            loadExistingServer(existingServerId)
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
                isSignInEnabled = allFieldsNotEmpty && noErrors && !currentState.isLoading &&
                    !currentState.isServerConfigurationLoading &&
                    !currentState.isExistingServerUnavailable,
                isOAuthSignInEnabled = currentState.isOAuthVisible && isValidServerUrl(url) &&
                    !currentState.isLoading && !currentState.isServerConfigurationLoading,
            )
        }
    }

    private fun loadExistingServer(serverId: String) {
        viewModelScope.launch {
            val server = serverRegistry.getServer(serverId)
            if (server == null || server.type != ServerType.Storyteller) {
                updateState {
                    it.copy(
                        isServerConfigurationLoading = false,
                        isExistingServerUnavailable = true,
                    )
                }
                return@launch
            }

            urlState.edit {
                replace(0, length, server.baseUrl)
            }
            updateState {
                it.copy(
                    selectedServerType = server.type,
                    isServerConfigurationLoading = false,
                )
            }
            updateFormState(
                url = urlState.text.toString(),
                username = usernameState.text.toString(),
                password = passwordState.text.toString(),
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
        if (existingServerId != null) return
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
        val url = urlState.text.toString().trim()
        val serverType = viewState.value.selectedServerType

        analytics.logEvent(
            AuthAnalyticsEvent.LoginAttempted(serverUrlHash = url.hashCode().toString())
        )

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

            loginUseCase(
                serverType = serverType,
                serverUrl = url,
                username = username,
                password = password,
                existingServerId = existingServerId,
            ).fold(
                success = {
                    analytics.setUserId(username.hashCode().toString())
                    analytics.logEvent(AuthAnalyticsEvent.LoginSucceeded)
                    onSignInSuccess()
                },
                failure = { error ->
                    analytics.logEvent(
                        AuthAnalyticsEvent.LoginFailed(
                            errorType = error::class.simpleName ?: "unknown",
                        )
                    )
                    error.log(analytics, "LoginViewModel: Failed to login")
                    updateAfterLoginFailure(error.message)
                },
            )
        }
    }

    private fun handleOAuthSignInClicked() {
        if (existingServerId != null) return
        val url = urlState.text.toString().trim()
        val serverType = viewState.value.selectedServerType

        analytics.logEvent(
            AuthAnalyticsEvent.LoginAttempted(serverUrlHash = url.hashCode().toString())
        )

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
            loginUseCase.withOAuth(serverType, url).fold(
                success = {
                    analytics.setUserId(url.hashCode().toString())
                    analytics.logEvent(AuthAnalyticsEvent.LoginSucceeded)
                    onSignInSuccess()
                },
                failure = { error ->
                    analytics.logEvent(
                        AuthAnalyticsEvent.LoginFailed(
                            errorType = error::class.simpleName ?: "unknown",
                        )
                    )
                    error.log(analytics, "LoginViewModel: Failed to login with OAuth")
                    updateAfterLoginFailure(error.message)
                },
            )
        }
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
