package com.retro99.cloudaccount.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.retro99.base.ui.BaseViewModel
import com.retro99.cloudaccount.domain.CloudAccountException
import com.retro99.cloudaccount.domain.usecase.GetCloudStorageUsageUseCase
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult
import com.retro99.cloudaccount.domain.model.CloudRegistrationResult
import com.retro99.cloudaccount.domain.usecase.ActivateCloudProfileUseCase
import com.retro99.cloudaccount.domain.usecase.EnableCloudSyncUseCase
import com.retro99.cloudaccount.domain.usecase.GetCloudProfileLinkUseCase
import com.retro99.cloudaccount.domain.usecase.LinkCloudAccountUseCase
import com.retro99.cloudaccount.domain.usecase.ObserveCloudAuthStateUseCase
import com.retro99.cloudaccount.domain.usecase.RegisterCloudAccountUseCase
import com.retro99.cloudaccount.domain.usecase.RestoreCloudSessionUseCase
import com.retro99.cloudaccount.domain.usecase.SignInCloudAccountUseCase
import com.retro99.cloudaccount.domain.usecase.SignOutCloudAccountUseCase
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.usecase.ObserveSyncStatusUseCase
import com.retro99.sync.domain.usecase.SyncNowUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class CloudAccountViewModel(
    @Provided private val observeCloudAuthStateUseCase: ObserveCloudAuthStateUseCase,
    @Provided private val restoreCloudSessionUseCase: RestoreCloudSessionUseCase,
    @Provided private val registerCloudAccountUseCase: RegisterCloudAccountUseCase,
    @Provided private val signInCloudAccountUseCase: SignInCloudAccountUseCase,
    @Provided private val getCloudProfileLinkUseCase: GetCloudProfileLinkUseCase,
    @Provided private val linkCloudAccountUseCase: LinkCloudAccountUseCase,
    @Provided private val signOutCloudAccountUseCase: SignOutCloudAccountUseCase,
    @Provided private val activateCloudProfileUseCase: ActivateCloudProfileUseCase,
    @Provided private val enableCloudSyncUseCase: EnableCloudSyncUseCase,
    @Provided private val observeSyncStatusUseCase: ObserveSyncStatusUseCase,
    @Provided private val syncNowUseCase: SyncNowUseCase,
    @Provided private val getCloudStorageUsageUseCase: GetCloudStorageUsageUseCase,
    @InjectedParam private val onBack: () -> Unit,
) : BaseViewModel<CloudAccountViewState, CloudAccountIntent>(CloudAccountViewState()) {
    val emailState = TextFieldState()
    val passwordState = TextFieldState()

    init {
        observeFormState()
        observeAuthState()
        observeSyncStatus()
        restoreSession()
    }

    override fun onIntent(intent: CloudAccountIntent) {
        when (intent) {
            CloudAccountIntent.OnBackClicked -> onBack()
            CloudAccountIntent.OnSubmitClicked -> submit()
            CloudAccountIntent.OnGoogleSignInClicked -> signInWithGoogle()
            CloudAccountIntent.OnSwitchToSignInClicked -> switchMode(CloudAccountMode.SignIn)
            CloudAccountIntent.OnSwitchToCreateAccountClicked -> {
                switchMode(CloudAccountMode.CreateAccount)
            }
            CloudAccountIntent.OnSignOutClicked -> signOut()
            CloudAccountIntent.OnSyncClicked -> sync()
            CloudAccountIntent.OnLinkConfirmed -> linkAccount()
            CloudAccountIntent.OnLinkDismissed -> signOut()
        }
    }

    private fun observeFormState() {
        snapshotFlow {
            emailState.text.toString() to passwordState.text.toString()
        }.onEach { (email, password) ->
            updateFormState(email, password)
        }.launchIn(viewModelScope)
    }

    private fun observeAuthState() {
        observeCloudAuthStateUseCase()
            .onEach { authState ->
                try {
                    handleAuthState(authState)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    showError(exception)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeSyncStatus() {
        observeSyncStatusUseCase()
            .onEach { syncStatus ->
                updateState { it.copy(syncStatus = syncStatus) }
            }
            .launchIn(viewModelScope)
    }

    private suspend fun handleAuthState(authState: CloudAuthState) {
        if (authState is CloudAuthState.SignedIn) {
            if (viewState.value.isLoading) {
                updateState { currentState ->
                    currentState.copy(authState = authState)
                }
            } else {
                prepareAuthenticatedAccount(authState.account)
            }
        } else {
            updateState { currentState ->
                currentState.copy(
                    authState = authState,
                    profileLink = null,
                    storageUsage = null,
                    isLoadingStorageUsage = false,
                    storageUsageError = null,
                    isLoading = if (authState is CloudAuthState.RestoringSession) {
                        currentState.isLoading
                    } else {
                        false
                    },
                    showVerificationMessage = when (authState) {
                        is CloudAuthState.AwaitingEmailVerification -> true
                        CloudAuthState.SignedOut -> false
                        else -> currentState.showVerificationMessage
                    },
                    showLinkConfirmation = false,
                )
            }
        }
    }

    private fun restoreSession() {
        viewModelScope.launch {
            try {
                val authState = restoreCloudSessionUseCase()
                if (authState is CloudAuthState.SignedIn) {
                    prepareAuthenticatedAccount(authState.account)
                } else {
                    updateState { currentState ->
                        currentState.copy(
                            authState = authState,
                            isLoading = false,
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateState { currentState ->
                    currentState.copy(
                        authState = CloudAuthState.SignedOut,
                        isLoading = false,
                        error = exception.toCloudAccountError(),
                    )
                }
                updateFormState(emailState.text.toString(), passwordState.text.toString())
            }
        }
    }

    private fun submit() {
        val currentState = viewState.value
        if (!currentState.isSubmitEnabled || currentState.isLoading) return

        val email = emailState.text.toString().trim()
        val password = passwordState.text.toString()
        updateState {
            it.copy(
                isLoading = true,
                isSubmitEnabled = false,
                showVerificationMessage = false,
                error = null,
            )
        }

        viewModelScope.launch {
            try {
                when (viewState.value.mode) {
                    CloudAccountMode.SignIn -> {
                        val account = signInCloudAccountUseCase(email, password)
                        prepareAuthenticatedAccount(account)
                    }
                    CloudAccountMode.CreateAccount -> {
                        when (val result = registerCloudAccountUseCase(email, password)) {
                            is CloudRegistrationResult.SignedIn -> {
                                prepareAuthenticatedAccount(result.account)
                            }
                            is CloudRegistrationResult.AwaitingEmailVerification -> {
                                updateState {
                                    it.copy(
                                        authState = CloudAuthState.AwaitingEmailVerification(
                                            result.email,
                                        ),
                                        isLoading = false,
                                        showVerificationMessage = true,
                                    )
                                }
                                updateFormState(email, password)
                            }
                        }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showError(exception)
            }
        }
    }

    private fun signInWithGoogle() {
        if (viewState.value.isLoading) return
        updateState {
            it.copy(
                isLoading = true,
                isSubmitEnabled = false,
                showVerificationMessage = false,
                error = null,
            )
        }

        viewModelScope.launch {
            try {
                val account = signInCloudAccountUseCase.signInWithGoogle()
                prepareAuthenticatedAccount(account)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showError(exception)
            }
        }
    }

    private suspend fun prepareAuthenticatedAccount(
        account: CloudAccount,
    ) {
        val profileLink = getCloudProfileLinkUseCase()
        if (profileLink != null && profileLink.cloudUserId != account.id) {
            signOutAfterLinkConflict(CloudAccountError.ProfileAlreadyLinked)
            return
        }
        updateState {
            it.copy(
                authState = CloudAuthState.SignedIn(account),
                profileLink = profileLink,
                isLoading = false,
                showLinkConfirmation = profileLink == null,
                error = null,
            )
        }
        updateFormState(emailState.text.toString(), passwordState.text.toString())
        refreshStorageUsage()
    }

    private fun refreshStorageUsage() {
        if (viewState.value.authState !is CloudAuthState.SignedIn) return
        updateState { it.copy(isLoadingStorageUsage = true, storageUsageError = null) }
        viewModelScope.launch {
            try {
                val usage = getCloudStorageUsageUseCase()
                updateState {
                    it.copy(storageUsage = usage, isLoadingStorageUsage = false, storageUsageError = null)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateState {
                    it.copy(
                        isLoadingStorageUsage = false,
                        storageUsageError = exception.message ?: "Storage usage unavailable",
                    )
                }
            }
        }
    }

    private fun linkAccount() {
        if (viewState.value.isLoading || !viewState.value.showLinkConfirmation) return
        updateState { currentState ->
            currentState.copy(
                isLoading = true,
                showLinkConfirmation = false,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                completeLinking()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showError(exception)
            }
        }
    }

    private suspend fun completeLinking() {
        when (val result = linkCloudAccountUseCase()) {
            is CloudProfileLinkResult.Linked -> {
                updateState {
                    it.copy(
                        profileLink = result.link,
                        isLoading = false,
                        error = null,
                    )
                }
            }
            is CloudProfileLinkResult.LocalProfileAlreadyLinked -> {
                signOutAfterLinkConflict(CloudAccountError.ProfileAlreadyLinked)
            }
            is CloudProfileLinkResult.CloudAccountAlreadyLinked -> {
                switchToLinkedProfile(result)
            }
        }
        updateFormState(emailState.text.toString(), passwordState.text.toString())
    }

    private suspend fun switchToLinkedProfile(
        result: CloudProfileLinkResult.CloudAccountAlreadyLinked,
    ) {
        signOutCloudAccountUseCase()
        activateCloudProfileUseCase(result.link.localProfileId)
        updateState { currentState ->
            currentState.copy(
                authState = CloudAuthState.RestoringSession,
                profileLink = result.link,
                isLoading = true,
                error = null,
            )
        }
        restoreSession()
    }

    private suspend fun signOutAfterLinkConflict(error: CloudAccountError) {
        try {
            signOutCloudAccountUseCase()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            Unit
        }
        updateState {
            it.copy(
                authState = CloudAuthState.SignedOut,
                profileLink = null,
                isLoading = false,
                error = error,
                showLinkConfirmation = false,
            )
        }
    }

    private fun signOut() {
        if (viewState.value.isLoading) return
        updateState {
            it.copy(
                isLoading = true,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                signOutCloudAccountUseCase()
                updateState {
                    it.copy(
                        authState = CloudAuthState.SignedOut,
                        profileLink = null,
                        storageUsage = null,
                        isLoadingStorageUsage = false,
                        storageUsageError = null,
                        isLoading = false,
                        error = null,
                        showVerificationMessage = false,
                        showLinkConfirmation = false,
                    )
                }
                updateFormState(emailState.text.toString(), passwordState.text.toString())
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showError(exception)
            }
        }
    }

    private fun sync() {
        if (viewState.value.isLoading || viewState.value.profileLink == null) return
        updateState {
            it.copy(
                isLoading = true,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                val enabledProfileLink = enableCloudSyncUseCase()
                updateState { currentState ->
                    currentState.copy(profileLink = enabledProfileLink)
                }
                when (syncNowUseCase()) {
                    is SyncResult.Completed -> {
                        val profileLink = getCloudProfileLinkUseCase()
                        updateState {
                            it.copy(
                                profileLink = profileLink,
                                isLoading = false,
                                error = null,
                            )
                        }
                        refreshStorageUsage()
                    }
                    is SyncResult.Offline -> updateState {
                        it.copy(isLoading = false, error = null)
                    }
                    SyncResult.NotConfigured -> showError(CloudAccountError.NotConfigured)
                    else -> showError(CloudAccountError.Generic)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showError(exception)
            }
        }
    }

    private fun switchMode(mode: CloudAccountMode) {
        updateState {
            it.copy(
                mode = mode,
                error = null,
                showVerificationMessage = false,
            )
        }
    }

    private fun updateFormState(email: String, password: String) {
        updateState {
            it.copy(
                isSubmitEnabled = email.isValidEmail() && password.isNotBlank() && !it.isLoading,
            )
        }
    }

    private fun showError(exception: Exception) {
        updateState {
            it.copy(
                isLoading = false,
                error = exception.toCloudAccountError(),
                showLinkConfirmation = it.authState is CloudAuthState.SignedIn &&
                    it.profileLink == null,
            )
        }
        updateFormState(emailState.text.toString(), passwordState.text.toString())
    }

    private fun showError(error: CloudAccountError) {
        updateState {
            it.copy(
                isLoading = false,
                error = error,
            )
        }
        updateFormState(emailState.text.toString(), passwordState.text.toString())
    }
}

private fun String.isValidEmail(): Boolean {
    val trimmed = trim()
    val atIndex = trimmed.indexOf('@')
    if (atIndex <= 0 || atIndex != trimmed.lastIndexOf('@')) return false
    val localPart = trimmed.substring(0, atIndex)
    val domain = trimmed.substring(atIndex + 1)
    if (localPart.isEmpty() || domain.isEmpty()) return false
    val lastDotIndex = domain.lastIndexOf('.')
    if (lastDotIndex <= 0 || lastDotIndex == domain.lastIndex) return false
    return domain.substring(lastDotIndex + 1).length >= 2
}

private fun Throwable.toCloudAccountError(): CloudAccountError {
    return when (this) {
        is CloudAccountException.NotConfigured -> CloudAccountError.NotConfigured
        is CloudAccountException.ProfileAlreadyLinked -> CloudAccountError.ProfileAlreadyLinked
        else -> CloudAccountError.Generic
    }
}
