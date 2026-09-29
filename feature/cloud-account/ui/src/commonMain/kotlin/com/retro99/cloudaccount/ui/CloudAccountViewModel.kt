package com.retro99.cloudaccount.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.CloudAccountAnalyticsEvent
import com.retro99.analytics.api.CloudAccountConsentKind
import com.retro99.analytics.api.CloudAccountOperation
import com.retro99.analytics.api.CloudAccountObservation
import com.retro99.base.ui.BaseViewModel
import com.retro99.cloudaccount.domain.CloudAccountException
import com.retro99.cloudaccount.domain.usecase.GetCloudStorageUsageUseCase
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult
import com.retro99.cloudaccount.domain.model.CloudRegistrationResult
import com.retro99.cloudaccount.domain.usecase.ActivateCloudProfileUseCase
import com.retro99.cloudaccount.domain.usecase.EnableCloudSyncUseCase
import com.retro99.cloudaccount.domain.usecase.DeleteCloudAccountUseCase
import com.retro99.cloudaccount.domain.usecase.GetCloudProfileLinkUseCase
import com.retro99.cloudaccount.domain.usecase.LinkCloudAccountUseCase
import com.retro99.cloudaccount.domain.usecase.ObserveCloudAuthStateUseCase
import com.retro99.cloudaccount.domain.usecase.RegisterCloudAccountUseCase
import com.retro99.cloudaccount.domain.usecase.RestoreCloudSessionUseCase
import com.retro99.cloudaccount.domain.usecase.SetAutoBackupEnabledUseCase
import com.retro99.cloudaccount.domain.usecase.SignInCloudAccountUseCase
import com.retro99.cloudaccount.domain.usecase.SignOutCloudAccountUseCase
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.usecase.ObserveSyncStatusUseCase
import com.retro99.sync.domain.usecase.SyncNowUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.catch
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
    @Provided private val deleteCloudAccountUseCase: DeleteCloudAccountUseCase,
    @Provided private val setAutoBackupEnabledUseCase: SetAutoBackupEnabledUseCase,
    @Provided private val analytics: Analytics,
    @InjectedParam private val onBack: () -> Unit,
) : BaseViewModel<CloudAccountViewState, CloudAccountIntent>(CloudAccountViewState()) {
    val emailState = TextFieldState()
    val passwordState = TextFieldState()
    private val operationTelemetry = CloudAccountOperationTelemetry(analytics)
    private val retryTracker = CloudAccountRetryTracker()

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
            is CloudAccountIntent.OnTosAcceptedChanged -> updateTosAccepted(intent.accepted)
            is CloudAccountIntent.OnPasswordVisibilityChanged -> analytics.logEvent(
                CloudAccountAnalyticsEvent.PasswordVisibilityChanged(intent.isVisible),
            )
            CloudAccountIntent.OnGoogleSignInClicked -> signInWithGoogle()
            CloudAccountIntent.OnSwitchToSignInClicked -> switchMode(CloudAccountMode.SignIn)
            CloudAccountIntent.OnSwitchToCreateAccountClicked -> {
                switchMode(CloudAccountMode.CreateAccount)
            }
            CloudAccountIntent.OnSignOutClicked -> signOut()
            CloudAccountIntent.OnDeleteAccountClicked -> showDeleteAccountConfirmation()
            CloudAccountIntent.OnDeleteAccountConfirmed -> deleteAccount()
            is CloudAccountIntent.OnDeleteAccountDismissed ->
                dismissDeleteAccountConfirmation(intent.entryPoint)
            CloudAccountIntent.OnSyncClicked -> sync()
            is CloudAccountIntent.OnAutoBackupToggled -> toggleAutoBackup(intent.enabled)
            is CloudAccountIntent.OnAutoBackupAttestationChanged ->
                updateUploadRightsAttestation(intent.attested)
            CloudAccountIntent.OnAutoBackupConfirmed -> confirmAutoBackup()
            is CloudAccountIntent.OnAutoBackupDismissed -> dismissAutoBackupConfirmation(intent.entryPoint)
            CloudAccountIntent.OnLinkConfirmed -> linkAccount()
            is CloudAccountIntent.OnLinkDismissed -> dismissLinkConfirmation(intent.entryPoint)
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
                handleAuthState(authState)
            }
            .catch { error ->
                if (error is CancellationException || error !is Exception) throw error
                reportCloudAccountObservationFailure(
                    analytics = analytics,
                    observation = CloudAccountObservation.AuthState,
                    error = error,
                )
                updateState { state ->
                    state.copy(
                        authState = if (state.authState == CloudAuthState.RestoringSession) {
                            CloudAuthState.SignedOut
                        } else {
                            state.authState
                        },
                        isLoading = false,
                        error = CloudAccountError.Generic,
                        showLinkConfirmation = false,
                    )
                }
                updateFormState(emailState.text.toString(), passwordState.text.toString())
            }
            .launchIn(viewModelScope)
    }

    private fun observeSyncStatus() {
        observeSyncStatusUseCase()
            .onEach { syncStatus ->
                updateState { it.copy(syncStatus = syncStatus) }
            }
            .catch { error ->
                if (error is CancellationException || error !is Exception) throw error
                reportCloudAccountObservationFailure(
                    analytics = analytics,
                    observation = CloudAccountObservation.SyncStatus,
                    error = error,
                )
                updateState { it.copy(error = CloudAccountError.Generic) }
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
                prepareAuthenticatedAccount(authState.account, entryPoint = "auth_state_observer")
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
                    showDeleteAccountConfirmation = false,
                    showAutoBackupConfirmation = false,
                    autoBackupRightsAttested = false,
                    isUpdatingAutoBackup = false,
                )
            }
        }
    }

    private fun restoreSession() {
        viewModelScope.launch {
            when (
                val execution = operationTelemetry.execute(
                    operation = CloudAccountOperation.RestoreSession,
                    entryPoint = "screen_entry",
                    isRetry = false,
                    failureReasonCode = { "session_restore_failed" },
                    classify = { authState ->
                        if (viewState.value.error == CloudAccountError.ProfileAlreadyLinked) {
                            CloudAccountOperationOutcome.Failed("profile_already_linked")
                        } else {
                            CloudAccountOperationOutcome.Succeeded(authState.toAnalyticsResultCode())
                        }
                    },
                ) {
                    stage("session_restore", "started")
                    val authState = restoreCloudSessionUseCase()
                    if (authState is CloudAuthState.SignedIn) {
                        prepareAuthenticatedAccount(authState.account, entryPoint = "session_restore", trace = this)
                    } else {
                        updateState { currentState ->
                            currentState.copy(
                                authState = authState,
                                isLoading = false,
                            )
                        }
                    }
                    authState
                }
            ) {
                is CloudAccountOperationExecution.Returned -> Unit
                is CloudAccountOperationExecution.Threw -> {
                    updateState { currentState ->
                        currentState.copy(
                            authState = CloudAuthState.SignedOut,
                            isLoading = false,
                            error = execution.error.toCloudAccountError(),
                        )
                    }
                    updateFormState(emailState.text.toString(), passwordState.text.toString())
                }
                is CloudAccountOperationExecution.Cancelled -> Unit
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
            val mode = currentState.mode
            val operation = CloudAccountOperation.Authentication
            val execution = operationTelemetry.execute(
                operation = operation,
                entryPoint = "email_submit",
                isRetry = retryTracker.isRetry(operation),
                authMethod = "email",
                mode = mode.analyticsName,
                reportUnexpectedFailure = { error ->
                    error is CloudAccountException.LocalStatePersistence ||
                        error is IllegalStateException
                },
                failureReasonCode = { error ->
                    error.cloudAuthenticationFailureReason()
                },
                classify = { resultCode ->
                    if (resultCode == "profile_already_linked") {
                        CloudAccountOperationOutcome.Failed("profile_already_linked")
                    } else {
                        CloudAccountOperationOutcome.Succeeded(resultCode)
                    }
                },
            ) {
                stage("authentication_request", "started")
                when (mode) {
                    CloudAccountMode.SignIn -> {
                        val account = signInCloudAccountUseCase(email, password)
                        prepareAuthenticatedAccount(account, entryPoint = "email_sign_in", trace = this)
                        if (viewState.value.error == CloudAccountError.ProfileAlreadyLinked) {
                            "profile_already_linked"
                        } else {
                            "signed_in"
                        }
                    }
                    CloudAccountMode.CreateAccount -> {
                        when (val result = registerCloudAccountUseCase(email, password, currentState.tosAccepted)) {
                            is CloudRegistrationResult.SignedIn -> {
                                prepareAuthenticatedAccount(result.account, entryPoint = "email_registration", trace = this)
                                if (viewState.value.error == CloudAccountError.ProfileAlreadyLinked) {
                                    "profile_already_linked"
                                } else {
                                    "signed_in"
                                }
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
                                "verification_pending"
                            }
                        }
                    }
                }
            }
            when (execution) {
                is CloudAccountOperationExecution.Returned -> retryTracker.record(operation, execution.outcome)
                is CloudAccountOperationExecution.Threw -> {
                    retryTracker.recordFailure(operation)
                    showError(execution.error)
                }
                is CloudAccountOperationExecution.Cancelled -> Unit
            }
        }
    }

    private fun signInWithGoogle() {
        if (viewState.value.isLoading) return
        val operation = CloudAccountOperation.Authentication
        updateState {
            it.copy(
                isLoading = true,
                isSubmitEnabled = false,
                showVerificationMessage = false,
                error = null,
            )
        }

        viewModelScope.launch {
            val execution = operationTelemetry.execute(
                operation = operation,
                entryPoint = "google_button",
                isRetry = retryTracker.isRetry(operation),
                authMethod = "google",
                mode = "sign_in",
                expectedCancellationReasonCode = { error ->
                    if (error is CloudAccountException.OAuthCancelled) "oauth_cancelled" else null
                },
                reportUnexpectedFailure = { error ->
                    error is CloudAccountException.LocalStatePersistence || error is IllegalStateException
                },
                failureReasonCode = { error ->
                    error.cloudAuthenticationFailureReason()
                },
                classify = { resultCode ->
                    if (resultCode == "profile_already_linked") {
                        CloudAccountOperationOutcome.Failed("profile_already_linked")
                    } else {
                        CloudAccountOperationOutcome.Succeeded(resultCode)
                    }
                },
            ) {
                stage("oauth_start", "started")
                val account = signInCloudAccountUseCase.signInWithGoogle()
                prepareAuthenticatedAccount(account, entryPoint = "google_sign_in", trace = this)
                if (viewState.value.error == CloudAccountError.ProfileAlreadyLinked) {
                    "profile_already_linked"
                } else {
                    "signed_in"
                }
            }
            when (execution) {
                is CloudAccountOperationExecution.Returned -> retryTracker.record(operation, execution.outcome)
                is CloudAccountOperationExecution.Threw -> {
                    retryTracker.recordFailure(operation)
                    showError(execution.error)
                }
                is CloudAccountOperationExecution.Cancelled -> {
                    updateState {
                        it.copy(
                            authState = CloudAuthState.SignedOut,
                            isLoading = false,
                            isSubmitEnabled = false,
                            error = null,
                            showVerificationMessage = false,
                            showLinkConfirmation = false,
                        )
                    }
                    updateFormState(emailState.text.toString(), passwordState.text.toString())
                }
            }
        }
    }

    private suspend fun prepareAuthenticatedAccount(
        account: CloudAccount,
        entryPoint: String,
        trace: CloudAccountOperationTrace? = null,
    ) {
        val currentState = viewState.value
        val currentAccount = (currentState.authState as? CloudAuthState.SignedIn)?.account
        if (currentAccount?.id == account.id &&
            (currentState.profileLink != null || currentState.showLinkConfirmation ||
                currentState.isLoadingStorageUsage || currentState.storageUsage != null ||
                currentState.storageUsageError != null)
        ) return
        trace?.stage("profile_link_lookup", "started")
        val profileLink = getCloudProfileLinkUseCase()
        if (profileLink != null && profileLink.cloudUserId != account.id) {
            signOutAfterLinkConflict(CloudAccountError.ProfileAlreadyLinked)
            return
        }
        if (profileLink == null) {
            analytics.logEvent(CloudAccountAnalyticsEvent.ConfirmationShown("profile_link"))
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
        refreshStorageUsage(entryPoint = entryPoint)
    }

    private fun refreshStorageUsage(entryPoint: String) {
        if (viewState.value.authState !is CloudAuthState.SignedIn) return
        updateState { it.copy(isLoadingStorageUsage = true, storageUsageError = null) }
        viewModelScope.launch {
            when (
                val execution = operationTelemetry.execute(
                    operation = CloudAccountOperation.StorageUsage,
                    entryPoint = entryPoint,
                    isRetry = false,
                    failureReasonCode = { "storage_usage_unavailable" },
                ) {
                    stage("storage_usage_request", "started")
                    getCloudStorageUsageUseCase()
                }
            ) {
                is CloudAccountOperationExecution.Returned -> {
                    updateState {
                        it.copy(storageUsage = execution.value, isLoadingStorageUsage = false, storageUsageError = null)
                    }
                }
                is CloudAccountOperationExecution.Cancelled -> {
                    updateState { it.copy(isLoadingStorageUsage = false) }
                }
                is CloudAccountOperationExecution.Threw -> {
                    updateState {
                        it.copy(
                            isLoadingStorageUsage = false,
                            storageUsageError = "Storage usage unavailable",
                        )
                    }
                }
            }
        }
    }

    private fun linkAccount() {
        if (viewState.value.isLoading || !viewState.value.showLinkConfirmation) return
        analytics.logEvent(
            CloudAccountAnalyticsEvent.ConfirmationConfirmed("profile_link", "confirm_button"),
        )
        updateState { currentState ->
            currentState.copy(
                isLoading = true,
                showLinkConfirmation = false,
                error = null,
            )
        }
        viewModelScope.launch {
            val operation = CloudAccountOperation.ProfileLink
            when (
                val execution = operationTelemetry.execute(
                    operation = operation,
                    entryPoint = "profile_link_confirm",
                    isRetry = retryTracker.isRetry(operation),
                    reportUnexpectedFailure = { error ->
                        error !is CloudAccountException.NotConfigured &&
                            error !is CloudAccountException.ProfileAlreadyLinked
                    },
                    failureReasonCode = { "profile_link_failed" },
                    classify = { resultCode ->
                        if (resultCode == "profile_already_linked") {
                            CloudAccountOperationOutcome.Failed(resultCode)
                        } else {
                            CloudAccountOperationOutcome.Succeeded(resultCode)
                        }
                    },
                ) {
                    stage("profile_link", "started")
                    completeLinking()
                }
            ) {
                is CloudAccountOperationExecution.Returned -> retryTracker.record(operation, execution.outcome)
                is CloudAccountOperationExecution.Threw -> {
                    retryTracker.recordFailure(operation)
                    showError(execution.error)
                }
                is CloudAccountOperationExecution.Cancelled -> Unit
            }
        }
    }

    private suspend fun completeLinking(): String {
        val result = linkCloudAccountUseCase()
        val resultCode = when (result) {
            is CloudProfileLinkResult.Linked -> {
                updateState {
                    it.copy(
                        profileLink = result.link,
                        isLoading = false,
                        error = null,
                    )
                }
                "linked"
            }
            is CloudProfileLinkResult.LocalProfileAlreadyLinked -> {
                signOutAfterLinkConflict(CloudAccountError.ProfileAlreadyLinked)
                "profile_already_linked"
            }
            is CloudProfileLinkResult.CloudAccountAlreadyLinked -> {
                switchToLinkedProfile(result)
                "switched_to_linked_profile"
            }
        }
        updateFormState(emailState.text.toString(), passwordState.text.toString())
        return resultCode
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
        when (
            val execution = operationTelemetry.execute(
                operation = CloudAccountOperation.LinkConflictCleanup,
                entryPoint = "profile_link_conflict",
                isRetry = retryTracker.isRetry(CloudAccountOperation.LinkConflictCleanup),
                reportUnexpectedFailure = { failure ->
                    failure !is CloudAccountException.NotConfigured &&
                        failure !is CloudAccountException.ProfileAlreadyLinked
                },
                failureReasonCode = { "sign_out_cleanup_failed" },
            ) {
                signOutCloudAccountUseCase()
            }
        ) {
            is CloudAccountOperationExecution.Returned -> retryTracker.record(
                CloudAccountOperation.LinkConflictCleanup,
                execution.outcome,
            )
            is CloudAccountOperationExecution.Threw -> retryTracker.recordFailure(
                CloudAccountOperation.LinkConflictCleanup,
            )
            is CloudAccountOperationExecution.Cancelled -> Unit
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

    private fun dismissLinkConfirmation(entryPoint: String) {
        if (!viewState.value.showLinkConfirmation || viewState.value.isLoading) return
        analytics.logEvent(
            CloudAccountAnalyticsEvent.ConfirmationDismissed("profile_link", entryPoint),
        )
        signOut(entryPoint = "link_confirmation_dismissal")
    }

    private fun signOut(entryPoint: String = "sign_out_button") {
        if (viewState.value.isLoading) return
        updateState {
            it.copy(
                isLoading = true,
                error = null,
            )
        }
        viewModelScope.launch {
            val operation = CloudAccountOperation.SignOut
            when (
                val execution = operationTelemetry.execute(
                    operation = operation,
                    entryPoint = entryPoint,
                    isRetry = retryTracker.isRetry(operation),
                    reportUnexpectedFailure = { error ->
                        error !is CloudAccountException.NotConfigured &&
                            error !is CloudAccountException.ProfileAlreadyLinked
                    },
                    failureReasonCode = { "sign_out_failed" },
                ) {
                    stage("local_session_cleanup", "started")
                    signOutCloudAccountUseCase()
                }
            ) {
                is CloudAccountOperationExecution.Returned -> {
                    retryTracker.record(operation, execution.outcome)
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
                }
                is CloudAccountOperationExecution.Threw -> {
                    retryTracker.recordFailure(operation)
                    showError(execution.error)
                }
                is CloudAccountOperationExecution.Cancelled -> {
                    updateState { it.copy(isLoading = false) }
                    updateFormState(emailState.text.toString(), passwordState.text.toString())
                }
            }
        }
    }

    private fun showDeleteAccountConfirmation() {
        if (viewState.value.isLoading) return
        analytics.logEvent(CloudAccountAnalyticsEvent.ConfirmationShown("delete_account"))
        updateState { it.copy(showDeleteAccountConfirmation = true, error = null) }
    }

    private fun dismissDeleteAccountConfirmation(entryPoint: String) {
        if (viewState.value.isLoading) return
        if (!viewState.value.showDeleteAccountConfirmation) return
        analytics.logEvent(
            CloudAccountAnalyticsEvent.ConfirmationDismissed("delete_account", entryPoint),
        )
        updateState { it.copy(showDeleteAccountConfirmation = false) }
    }

    private fun deleteAccount() {
        if (viewState.value.isLoading || !viewState.value.showDeleteAccountConfirmation) return
        val operation = CloudAccountOperation.DeleteAccount
        analytics.logEvent(
            CloudAccountAnalyticsEvent.ConfirmationConfirmed("delete_account", "confirm_button"),
        )
        updateState {
            it.copy(
                isLoading = true,
                showDeleteAccountConfirmation = false,
                error = null,
            )
        }
        viewModelScope.launch {
            when (
                val execution = operationTelemetry.execute(
                    operation = operation,
                    entryPoint = "confirm_button",
                    isRetry = retryTracker.isRetry(operation),
                    reportUnexpectedFailure = { error ->
                        error is CloudAccountException.LocalStatePersistence
                    },
                    failureReasonCode = { "account_deletion_failed" },
                ) {
                    stage("delete_cloud_account", "started")
                    deleteCloudAccountUseCase()
                }
            ) {
                is CloudAccountOperationExecution.Returned -> {
                    retryTracker.record(operation, execution.outcome)
                    updateState {
                        it.copy(
                            authState = CloudAuthState.SignedOut,
                            profileLink = null,
                            storageUsage = null,
                            isLoadingStorageUsage = false,
                            storageUsageError = null,
                            isLoading = false,
                            showLinkConfirmation = false,
                            showDeleteAccountConfirmation = false,
                            showVerificationMessage = false,
                            error = null,
                        )
                    }
                    updateFormState(emailState.text.toString(), passwordState.text.toString())
                }
                is CloudAccountOperationExecution.Threw -> {
                    retryTracker.recordFailure(operation)
                    showError(execution.error)
                }
                is CloudAccountOperationExecution.Cancelled -> {
                    updateState { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun sync() {
        if (viewState.value.isLoading || viewState.value.profileLink == null) return
        val operation = CloudAccountOperation.SyncNow
        updateState {
            it.copy(
                isLoading = true,
                error = null,
            )
        }
        viewModelScope.launch {
            val execution = operationTelemetry.execute(
                operation = operation,
                entryPoint = "sync_button",
                isRetry = retryTracker.isRetry(operation),
                classify = { result ->
                    when (result) {
                        is SyncResult.Completed -> CloudAccountOperationOutcome.Succeeded()
                        is SyncResult.Offline -> CloudAccountOperationOutcome.Failed("offline")
                        SyncResult.NotConfigured -> CloudAccountOperationOutcome.Failed("not_configured")
                        SyncResult.NotAuthenticated -> CloudAccountOperationOutcome.Failed("not_authenticated")
                        SyncResult.ProfileNotLinked -> CloudAccountOperationOutcome.Failed("profile_not_linked")
                        SyncResult.SyncDisabled -> CloudAccountOperationOutcome.Failed("sync_disabled")
                        is SyncResult.Failed -> CloudAccountOperationOutcome.Failed("sync_failed")
                    }
                },
            ) {
                stage("enable_cloud_sync", "started")
                val enabledProfileLink = enableCloudSyncUseCase()
                updateState { currentState -> currentState.copy(profileLink = enabledProfileLink) }
                val result = syncNowUseCase()
                if (result is SyncResult.Completed) {
                    stage("refresh_profile_link", "started")
                    val profileLink = getCloudProfileLinkUseCase()
                    updateState { it.copy(profileLink = profileLink) }
                    refreshStorageUsage(entryPoint = "sync_completed")
                }
                result
            }
            when (execution) {
                is CloudAccountOperationExecution.Threw -> {
                    retryTracker.recordFailure(operation)
                    showError(execution.error)
                }
                is CloudAccountOperationExecution.Returned -> {
                    retryTracker.record(operation, execution.outcome)
                    when (val result = execution.value) {
                        is SyncResult.Completed, is SyncResult.Offline -> updateState {
                            it.copy(isLoading = false, error = null)
                        }
                        SyncResult.NotConfigured -> showError(CloudAccountError.NotConfigured)
                        SyncResult.NotAuthenticated,
                        SyncResult.ProfileNotLinked,
                        SyncResult.SyncDisabled,
                        is SyncResult.Failed,
                        -> showError(CloudAccountError.Generic)
                    }
                }
                is CloudAccountOperationExecution.Cancelled -> {
                    updateState { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun toggleAutoBackup(enabled: Boolean) {
        val current = viewState.value
        if (current.isLoading || current.isUpdatingAutoBackup || current.profileLink == null) return
        if (current.profileLink.autoBackupEnabled == enabled) return

        if (!enabled) {
            setAutoBackupEnabled(enabled = false)
            return
        }

        analytics.logEvent(CloudAccountAnalyticsEvent.ConfirmationShown("auto_backup"))
        updateState {
            it.copy(
                showAutoBackupConfirmation = true,
                autoBackupRightsAttested = false,
                error = null,
            )
        }
    }

    private fun confirmAutoBackup() {
        val current = viewState.value
        if (!current.showAutoBackupConfirmation || !current.autoBackupRightsAttested ||
            current.isUpdatingAutoBackup
        ) return
        analytics.logEvent(
            CloudAccountAnalyticsEvent.ConfirmationConfirmed("auto_backup", "confirm_button"),
        )
        setAutoBackupEnabled(enabled = true, rightsAttested = true)
    }

    private fun dismissAutoBackupConfirmation(entryPoint: String) {
        if (viewState.value.isUpdatingAutoBackup) return
        if (!viewState.value.showAutoBackupConfirmation) return
        analytics.logEvent(
            CloudAccountAnalyticsEvent.ConfirmationDismissed("auto_backup", entryPoint),
        )
        updateState {
            it.copy(
                showAutoBackupConfirmation = false,
                autoBackupRightsAttested = false,
            )
        }
    }

    private fun setAutoBackupEnabled(
        enabled: Boolean,
        rightsAttested: Boolean = false,
    ) {
        updateState {
            it.copy(
                isUpdatingAutoBackup = true,
                error = null,
            )
        }
        viewModelScope.launch {
            val operation = CloudAccountOperation.AutoBackup
            when (
                val execution = operationTelemetry.execute(
                    operation = operation,
                    entryPoint = if (enabled) "confirm_button" else "auto_backup_switch",
                    isRetry = retryTracker.isRetry(operation),
                    isEnabled = enabled,
                    reportUnexpectedFailure = { error ->
                        error !is CloudAccountException.NotConfigured &&
                            error !is CloudAccountException.ProfileAlreadyLinked
                    },
                    failureReasonCode = { "auto_backup_update_failed" },
                ) {
                    stage("persist_auto_backup", "started")
                    persistAutoBackupEnabled(enabled, rightsAttested)
                }
            ) {
                is CloudAccountOperationExecution.Returned -> retryTracker.record(operation, execution.outcome)
                is CloudAccountOperationExecution.Threw -> {
                    retryTracker.recordFailure(operation)
                    updateState { it.copy(isUpdatingAutoBackup = false) }
                    showError(execution.error)
                }
                is CloudAccountOperationExecution.Cancelled -> {
                    updateState { it.copy(isUpdatingAutoBackup = false) }
                }
            }
        }
    }

    private suspend fun persistAutoBackupEnabled(
        enabled: Boolean,
        rightsAttested: Boolean,
    ) {
        val profileLink = setAutoBackupEnabledUseCase(enabled, rightsAttested)
        updateState {
            it.copy(
                profileLink = profileLink,
                isUpdatingAutoBackup = false,
                showAutoBackupConfirmation = false,
                autoBackupRightsAttested = false,
                error = null,
            )
        }
    }

    private fun switchMode(mode: CloudAccountMode) {
        if (viewState.value.mode == mode) return
        updateState {
            it.copy(
                mode = mode,
                error = null,
                showVerificationMessage = false,
            )
        }
        analytics.logEvent(CloudAccountAnalyticsEvent.ModeChanged(mode.analyticsName))
    }

    private fun updateFormState(email: String, password: String) {
        updateState {
            it.copy(
                isSubmitEnabled = email.isValidEmail() && password.isNotBlank() &&
                    (it.mode != CloudAccountMode.CreateAccount || it.tosAccepted) &&
                    !it.isLoading,
            )
        }
    }

    private fun updateTosAccepted(accepted: Boolean) {
        if (viewState.value.tosAccepted == accepted) return
        analytics.logEvent(
            CloudAccountAnalyticsEvent.ConsentChanged(CloudAccountConsentKind.AccountTerms, accepted),
        )
        val email = emailState.text.toString().trim()
        val password = passwordState.text.toString()
        updateState {
            val state = it.copy(tosAccepted = accepted)
            state.copy(
                isSubmitEnabled = email.isValidEmail() && password.isNotBlank() &&
                    (state.mode != CloudAccountMode.CreateAccount || state.tosAccepted) &&
                    !state.isLoading,
            )
        }
    }

    private fun updateUploadRightsAttestation(attested: Boolean) {
        if (viewState.value.autoBackupRightsAttested == attested) return
        analytics.logEvent(
            CloudAccountAnalyticsEvent.ConsentChanged(CloudAccountConsentKind.UploadRights, attested),
        )
        updateState { it.copy(autoBackupRightsAttested = attested) }
    }

    private fun showError(exception: Exception) {
        updateState {
            it.copy(
                isLoading = false,
                error = exception.toCloudAccountError(),
                showLinkConfirmation = exception !is CloudAccountException.LocalStatePersistence &&
                    it.authState is CloudAuthState.SignedIn && it.profileLink == null,
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

private fun Exception.cloudAuthenticationFailureReason(): String = when (this) {
    is CloudAccountException.LocalStatePersistence -> if (cleanupFailed) {
        "local_state_rollback_failed"
    } else {
        "local_state_persistence_failed"
    }
    is CloudAccountException.OAuthFailure -> reason.analyticsCode
    else -> "authentication_failed"
}

private val CloudAccountMode.analyticsName: String
    get() = when (this) {
        CloudAccountMode.SignIn -> "sign_in"
        CloudAccountMode.CreateAccount -> "create_account"
    }

private fun CloudAuthState.toAnalyticsResultCode(): String = when (this) {
    CloudAuthState.RestoringSession -> "restoring"
    CloudAuthState.SignedOut -> "signed_out"
    is CloudAuthState.AwaitingEmailVerification -> "verification_pending"
    is CloudAuthState.SignedIn -> "signed_in"
    is CloudAuthState.ReauthenticationRequired -> "reauthentication_required"
    is CloudAuthState.RefreshUnavailable -> "refresh_unavailable"
}
