package com.retro99.home.ui.appsettings

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.FileLogger
import com.retro99.base.ui.BaseViewModel
import com.retro99.base.ui.sharing.FileSharer
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.reader.domain.usecase.ClearCurrentlyReadingUseCase
import com.retro99.reader.domain.usecase.ObserveCurrentlyReadingUseCase
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class AppSettingsViewModel(
    @Provided private val fileLogger: FileLogger,
    @Provided private val fileSharer: FileSharer,
    @Provided private val preferences: Preferences,
    @Provided private val clearCurrentlyReadingUseCase: ClearCurrentlyReadingUseCase,
    @Provided private val observeCurrentlyReadingUseCase: ObserveCurrentlyReadingUseCase,
    @Provided private val userRegistry: UserRegistry,
    @Provided private val analytics: Analytics,
) : BaseViewModel<AppSettingsViewState, AppSettingsIntent>(
    AppSettingsViewState(),
) {

    private val profileOperationRetryTracker = ProfileOperationRetryTracker()
    private val profileOperationGate = ProfileOperationGate()
    private val profileOperationTapShield = ProfileOperationTapShieldHolder.instance
    private var addProfileRetryKey: String? = null
    private var renameProfileRetryKey: String? = null
    private var deleteProfileRetryKey: String? = null

    init {
        observeUserProfiles()
        observeCurrentlyReading()
        observeBooleanPref(PreferencesKey.FileLoggingEnabled, defaultValue = false) { enabled ->
            updateState { it.copy(isLoggingEnabled = enabled) }
        }
        observeBooleanPref(PreferencesKey.FileLoggingCrashesOnly, defaultValue = false) { enabled ->
            updateState { it.copy(logCrashesOnly = enabled) }
        }
        observeBooleanPref(PreferencesKey.OpenLastBookOnLaunch, defaultValue = false) { enabled ->
            updateState { it.copy(openLastBookOnLaunch = enabled) }
        }
        observeBooleanPref(PreferencesKey.ShowContinueReading, defaultValue = true) { enabled ->
            updateState { it.copy(showContinueReading = enabled) }
        }
    }

    private fun observeCurrentlyReading() {
        observeCurrentlyReadingUseCase()
            .onStart {
                analytics.logBreadcrumb(
                    DiagnosticContext(
                        screen = "app_settings",
                        action = "observe_current_book",
                        operation = "current_book_state",
                        stage = "started",
                        outcome = "started",
                    ),
                )
            }
            .onEach { currentlyReading ->
                val hasCurrentlyReadingBook = currentlyReading != null
                val stateChanged = viewState.value.hasCurrentlyReadingBook != hasCurrentlyReadingBook
                updateState { it.withCurrentlyReading(currentlyReading) }
                if (stateChanged) {
                    analytics.logBreadcrumb(
                        DiagnosticContext(
                            screen = "app_settings",
                            action = "observe_current_book",
                            operation = "current_book_state",
                            stage = "state_updated",
                            outcome = if (hasCurrentlyReadingBook) "available" else "empty",
                        ),
                    )
                }
            }
            .catch { error ->
                analytics.logException(
                    error,
                    DiagnosticContext(
                        screen = "app_settings",
                        action = "observe_current_book",
                        operation = "current_book_state",
                        stage = "observe",
                        outcome = "failed",
                        reasonCode = "current_book_observation_failed",
                    ),
                )
            }
            .launchIn(viewModelScope)
    }

    private fun observeBooleanPref(
        key: PreferencesKey,
        defaultValue: Boolean,
        onUpdate: (Boolean) -> Unit,
    ) {
        preferences.observeBoolean(key, defaultValue)
            .onEach { onUpdate(it) }
            .launchIn(viewModelScope)
    }

    private fun observeUserProfiles() {
        userRegistry.observeAllProfiles()
            .onEach { profiles ->
                updateState { it.copy(userProfiles = profiles) }
            }
            .launchIn(viewModelScope)

        userRegistry.observeActiveProfile()
            .onEach { profile ->
                updateState { it.copy(activeProfile = profile) }
            }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: AppSettingsIntent) {
        when (intent) {
            is AppSettingsIntent.OnLoggingToggled -> setLoggingEnabled(intent.enabled)
            is AppSettingsIntent.OnLogCrashesOnlyToggled -> setLogCrashesOnly(intent.enabled)
            is AppSettingsIntent.OnOpenLastBookToggled -> setOpenLastBookOnLaunch(intent.enabled)
            is AppSettingsIntent.OnShowContinueReadingToggled -> setShowContinueReading(intent.enabled)
            AppSettingsIntent.OnShareLogsClicked -> shareLogs()
            AppSettingsIntent.OnClearLogsClicked -> clearLogs()
            AppSettingsIntent.OnLogsClearedMessageShown -> onLogsClearedMessageShown()
            AppSettingsIntent.OnNoLogsMessageShown -> onNoLogsMessageShown()
            AppSettingsIntent.OnClearCurrentBookClicked -> clearCurrentBook()
            AppSettingsIntent.OnCurrentBookClearedMessageShown -> onCurrentBookClearedMessageShown()
            AppSettingsIntent.OnCurrentBookClearFailedMessageShown -> onCurrentBookClearFailedMessageShown()
            is AppSettingsIntent.OnProfileSelected -> selectProfile(intent.profileId)
            AppSettingsIntent.OnAddProfileClicked -> showAddProfileDialog()
            is AppSettingsIntent.OnAddProfileConfirmed -> addProfile(intent.name)
            is AppSettingsIntent.OnAddProfileDismissed -> hideAddProfileDialog(intent.entryPoint)
            is AppSettingsIntent.OnProfileLongPressed -> onProfileLongPressed(intent.profileId, intent.entryPoint)
            is AppSettingsIntent.OnProfileMenuDismissed -> dismissProfileMenu(intent.entryPoint)
            AppSettingsIntent.OnRenameProfileClicked -> showRenameProfileDialog()
            is AppSettingsIntent.OnRenameProfileConfirmed -> renameProfile(intent.newName)
            is AppSettingsIntent.OnRenameProfileDismissed -> hideRenameProfileDialog(intent.entryPoint)
            AppSettingsIntent.OnDeleteProfileClicked -> showDeleteProfileDialog()
            AppSettingsIntent.OnDeleteProfileConfirmed -> deleteProfile()
            is AppSettingsIntent.OnDeleteProfileDismissed -> hideDeleteProfileDialog(intent.entryPoint)
            AppSettingsIntent.OnProfileOperationFailedMessageShown -> onProfileOperationFailedMessageShown()
        }
    }

    private fun selectProfile(profileId: String) {
        if (viewState.value.activeProfile?.id == profileId) return
        launchProfileOperation {
            val succeeded = runProfileOperation(
                operation = AppSettingsAnalyticsEvent.ProfileOperation.Switch,
                retryKey = "switch:$profileId",
                successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileSwitched(isRetry) },
            ) {
                userRegistry.setActiveProfile(profileId)
                check(userRegistry.getActiveProfileId() == profileId) {
                    "profile_activation_not_applied"
                }
            }
            updateState { it.copy(showProfileOperationFailedMessage = !succeeded) }
        }
    }

    private fun showAddProfileDialog() {
        if (profileOperationGate.isInProgress || viewState.value.showAddProfileDialog) return
        addProfileRetryKey?.let(profileOperationRetryTracker::clear)
        addProfileRetryKey = profileOperationRetryTracker.newSessionKey(
            AppSettingsAnalyticsEvent.ProfileOperation.Create,
        )
        analytics.logEvent(
            AppSettingsAnalyticsEvent.ProfileDialogOpened(AppSettingsAnalyticsEvent.ProfileOperation.Create),
        )
        updateState { it.copy(showAddProfileDialog = true, showProfileOperationFailedMessage = false) }
    }

    private fun hideAddProfileDialog(entryPoint: String) {
        if (profileOperationGate.isInProgress || !viewState.value.showAddProfileDialog) return
        addProfileRetryKey?.let(profileOperationRetryTracker::clear)
        addProfileRetryKey = null
        analytics.logEvent(
            AppSettingsAnalyticsEvent.ProfileOperationCancelled(
                profileOperation = AppSettingsAnalyticsEvent.ProfileOperation.Create,
                entryPoint = entryPoint,
            ),
        )
        updateState { it.copy(showAddProfileDialog = false, showProfileOperationFailedMessage = false) }
    }

    private fun addProfile(name: String) {
        if (name.isBlank() || !viewState.value.showAddProfileDialog) return
        val retryKey = addProfileRetryKey ?: profileOperationRetryTracker.newSessionKey(
            AppSettingsAnalyticsEvent.ProfileOperation.Create,
        ).also { addProfileRetryKey = it }
        launchProfileOperation {
            var createdProfileId: String? = null
            val created = runProfileOperation(
                operation = AppSettingsAnalyticsEvent.ProfileOperation.Create,
                retryKey = retryKey,
                successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileCreated(isRetry) },
            ) {
                val profile = userRegistry.createProfile(name = name)
                createdProfileId = profile.id
                check(userRegistry.getProfile(profile.id) != null) {
                    "profile_creation_not_persisted"
                }
            }
            if (!created) {
                updateState { it.copy(showProfileOperationFailedMessage = true) }
            } else {
                addProfileRetryKey = null
                updateState {
                    it.copy(showAddProfileDialog = false, showProfileOperationFailedMessage = false)
                }
                createdProfileId?.let { profileId ->
                    val switched = runProfileOperation(
                        operation = AppSettingsAnalyticsEvent.ProfileOperation.Switch,
                        retryKey = "switch:$profileId",
                        successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileSwitched(isRetry) },
                    ) {
                        userRegistry.setActiveProfile(profileId)
                        check(userRegistry.getActiveProfileId() == profileId) {
                            "profile_activation_not_applied"
                        }
                    }
                    updateState { it.copy(showProfileOperationFailedMessage = !switched) }
                }
            }
        }
    }

    private fun onProfileLongPressed(profileId: String, entryPoint: String) {
        if (profileOperationGate.isInProgress) return
        val profile = viewState.value.userProfiles.find { it.id == profileId }
        if (profile == null) return
        analytics.logEvent(AppSettingsAnalyticsEvent.ProfileMenuOpened(entryPoint))
        updateState { it.copy(selectedProfileForMenu = profile) }
    }

    private fun dismissProfileMenu(entryPoint: String) {
        if (profileOperationGate.isInProgress || viewState.value.selectedProfileForMenu == null) return
        analytics.logEvent(AppSettingsAnalyticsEvent.ProfileMenuDismissed(entryPoint))
        updateState { it.copy(selectedProfileForMenu = null) }
    }

    private fun showRenameProfileDialog() {
        if (profileOperationGate.isInProgress || viewState.value.showRenameProfileDialog) return
        if (viewState.value.selectedProfileForMenu == null) return
        renameProfileRetryKey?.let(profileOperationRetryTracker::clear)
        renameProfileRetryKey = profileOperationRetryTracker.newSessionKey(
            AppSettingsAnalyticsEvent.ProfileOperation.Rename,
        )
        analytics.logEvent(
            AppSettingsAnalyticsEvent.ProfileDialogOpened(AppSettingsAnalyticsEvent.ProfileOperation.Rename),
        )
        updateState { it.copy(showRenameProfileDialog = true, showProfileOperationFailedMessage = false) }
    }

    private fun hideRenameProfileDialog(entryPoint: String) {
        if (profileOperationGate.isInProgress || !viewState.value.showRenameProfileDialog) return
        renameProfileRetryKey?.let(profileOperationRetryTracker::clear)
        renameProfileRetryKey = null
        analytics.logEvent(
            AppSettingsAnalyticsEvent.ProfileOperationCancelled(
                profileOperation = AppSettingsAnalyticsEvent.ProfileOperation.Rename,
                entryPoint = entryPoint,
            ),
        )
        updateState {
            it.copy(
                showRenameProfileDialog = false,
                selectedProfileForMenu = null,
                showProfileOperationFailedMessage = false,
            )
        }
    }

    private fun renameProfile(newName: String) {
        val profile = viewState.value.selectedProfileForMenu ?: return
        if (newName.isBlank() || !viewState.value.showRenameProfileDialog) return
        val retryKey = renameProfileRetryKey ?: profileOperationRetryTracker.newSessionKey(
            AppSettingsAnalyticsEvent.ProfileOperation.Rename,
        ).also { renameProfileRetryKey = it }
        launchProfileOperation {
            val updatedProfile = profile.copy(name = newName)
            val succeeded = runProfileOperation(
                operation = AppSettingsAnalyticsEvent.ProfileOperation.Rename,
                retryKey = retryKey,
                successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileRenamed(isRetry) },
            ) {
                userRegistry.updateProfile(updatedProfile)
                check(userRegistry.getProfile(profile.id)?.name == updatedProfile.name) {
                    "profile_rename_not_applied"
                }
            }
            if (succeeded) renameProfileRetryKey = null
            updateState {
                if (succeeded) {
                    it.copy(
                        showRenameProfileDialog = false,
                        selectedProfileForMenu = null,
                        showProfileOperationFailedMessage = false,
                    )
                } else {
                    it.copy(showProfileOperationFailedMessage = true)
                }
            }
        }
    }

    private fun showDeleteProfileDialog() {
        if (profileOperationGate.isInProgress || viewState.value.showDeleteProfileDialog) return
        if (viewState.value.selectedProfileForMenu == null) return
        deleteProfileRetryKey?.let(profileOperationRetryTracker::clear)
        deleteProfileRetryKey = profileOperationRetryTracker.newSessionKey(
            AppSettingsAnalyticsEvent.ProfileOperation.Delete,
        )
        analytics.logEvent(
            AppSettingsAnalyticsEvent.ProfileDialogOpened(AppSettingsAnalyticsEvent.ProfileOperation.Delete),
        )
        updateState { it.copy(showDeleteProfileDialog = true, showProfileOperationFailedMessage = false) }
    }

    private fun hideDeleteProfileDialog(entryPoint: String) {
        if (profileOperationGate.isInProgress || !viewState.value.showDeleteProfileDialog) return
        deleteProfileRetryKey?.let(profileOperationRetryTracker::clear)
        deleteProfileRetryKey = null
        analytics.logEvent(
            AppSettingsAnalyticsEvent.ProfileOperationCancelled(
                profileOperation = AppSettingsAnalyticsEvent.ProfileOperation.Delete,
                entryPoint = entryPoint,
            ),
        )
        updateState {
            it.copy(
                showDeleteProfileDialog = false,
                selectedProfileForMenu = null,
                showProfileOperationFailedMessage = false,
            )
        }
    }

    private fun deleteProfile() {
        val profileId = viewState.value.selectedProfileForMenu?.id ?: return
        if (!viewState.value.showDeleteProfileDialog) return
        val retryKey = deleteProfileRetryKey ?: profileOperationRetryTracker.newSessionKey(
            AppSettingsAnalyticsEvent.ProfileOperation.Delete,
        ).also { deleteProfileRetryKey = it }
        launchProfileOperation {
            val succeeded = runProfileOperation(
                operation = AppSettingsAnalyticsEvent.ProfileOperation.Delete,
                retryKey = retryKey,
                successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileDeleted(isRetry) },
            ) {
                userRegistry.deleteProfile(profileId)
                check(userRegistry.getProfile(profileId) == null) {
                    "profile_deletion_not_applied"
                }
            }
            if (succeeded) deleteProfileRetryKey = null
            updateState {
                if (succeeded) {
                    it.copy(
                        showDeleteProfileDialog = false,
                        selectedProfileForMenu = null,
                        showProfileOperationFailedMessage = false,
                    )
                } else {
                    it.copy(showProfileOperationFailedMessage = true)
                }
            }
        }
    }

    private fun onProfileOperationFailedMessageShown() {
        updateState { it.copy(showProfileOperationFailedMessage = false) }
    }

    private fun launchProfileOperation(block: suspend () -> Unit) {
        if (profileOperationTapShield.isBlocking.value) return
        if (!profileOperationGate.tryStart()) return
        profileOperationTapShield.block()
        updateState { it.copy(isProfileOperationInProgress = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                profileOperationGate.finish()
                updateState { it.copy(isProfileOperationInProgress = false) }
                withContext(NonCancellable) {
                    profileOperationTapShield.releaseAfterRepeatTapWindow()
                }
            }
        }
    }

    private suspend fun runProfileOperation(
        operation: AppSettingsAnalyticsEvent.ProfileOperation,
        retryKey: String,
        successEvent: (Boolean) -> AnalyticsEvent,
        execute: suspend () -> Unit,
    ): Boolean {
        val isRetry = profileOperationRetryTracker.isRetry(retryKey)
        val succeeded = executeProfileOperation(
            analytics = analytics,
            operation = operation,
            isRetry = isRetry,
            successEvent = successEvent,
            execute = execute,
        )
        if (succeeded) {
            profileOperationRetryTracker.recordSuccess(retryKey)
        } else {
            profileOperationRetryTracker.recordFailure(retryKey)
        }
        return succeeded
    }

    private fun setLoggingEnabled(enabled: Boolean) {
        preferences.putBoolean(PreferencesKey.FileLoggingEnabled, enabled)
        analytics.logEvent(AppSettingsAnalyticsEvent.FileLoggingToggled(isEnabled = enabled))
        updateState { it.copy(isLoggingEnabled = enabled) }
    }

    private fun setLogCrashesOnly(enabled: Boolean) {
        preferences.putBoolean(PreferencesKey.FileLoggingCrashesOnly, enabled)
        analytics.logEvent(AppSettingsAnalyticsEvent.CrashOnlyLoggingToggled(isEnabled = enabled))
        updateState { it.copy(logCrashesOnly = enabled) }
    }

    private fun setOpenLastBookOnLaunch(enabled: Boolean) {
        preferences.putBoolean(PreferencesKey.OpenLastBookOnLaunch, enabled)
        analytics.logEvent(AppSettingsAnalyticsEvent.OpenLastBookOnLaunchToggled(isEnabled = enabled))
        updateState { it.copy(openLastBookOnLaunch = enabled) }
    }

    private fun setShowContinueReading(enabled: Boolean) {
        preferences.putBoolean(PreferencesKey.ShowContinueReading, enabled)
        updateState { it.copy(showContinueReading = enabled) }
    }

    private fun shareLogs() {
        val logContents = fileLogger.getLogContents()
        if (logContents.isEmpty()) {
            updateState { it.copy(showNoLogsMessage = true) }
            return
        }
        analytics.logEvent(AppSettingsAnalyticsEvent.LogsShared)
        val logFilePath = fileLogger.getLogFilePath()
        fileSharer.shareFile(
            filePath = logFilePath,
            mimeType = "text/plain",
            title = "Share App Logs",
        )
    }

    private fun clearLogs() {
        fileLogger.clearLogs()
        analytics.logEvent(AppSettingsAnalyticsEvent.LogsCleared)
        updateState { it.copy(showLogsClearedMessage = true) }
    }

    private fun onLogsClearedMessageShown() {
        updateState { it.copy(showLogsClearedMessage = false) }
    }

    private fun onNoLogsMessageShown() {
        updateState { it.copy(showNoLogsMessage = false) }
    }

    private fun clearCurrentBook() {
        val isRetry = viewState.value.canRetryCurrentBookClear
        val succeeded = executeCurrentBookClear(
            analytics = analytics,
            isRetry = isRetry,
        ) {
            clearCurrentlyReadingUseCase()
        }
        updateState { it.withCurrentBookClearOutcome(succeeded) }
    }

    private fun onCurrentBookClearedMessageShown() {
        updateState { it.copy(showCurrentBookClearedMessage = false) }
    }

    private fun onCurrentBookClearFailedMessageShown() {
        updateState { it.copy(showCurrentBookClearFailedMessage = false) }
    }
}
