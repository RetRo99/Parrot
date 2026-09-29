package com.retro99.home.ui.appsettings

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.FileLogger
import com.retro99.base.ui.BaseViewModel
import com.retro99.base.ui.compose.ThemeMode
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
    private val failedSettingToggleTargets = mutableMapOf<AppSettingsAnalyticsEvent.SettingToggle, Boolean>()
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
        preferences.observeStringOrNull(PreferencesKey.ThemeMode)
            .onEach { storedKey ->
                val themeMode = ThemeMode.fromKey(storedKey) ?: ThemeMode.Night
                updateState { it.copy(themeMode = themeMode) }
            }
            .launchIn(viewModelScope)
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
            AppSettingsIntent.OnThemeModeClicked -> {
                updateState { it.copy(showThemeModeDialog = true) }
            }
            AppSettingsIntent.OnThemeModeDialogDismissed -> {
                updateState { it.copy(showThemeModeDialog = false) }
            }
            is AppSettingsIntent.OnThemeModeSelected -> {
                preferences.putString(PreferencesKey.ThemeMode, intent.themeMode.key)
                updateState { it.copy(showThemeModeDialog = false) }
            }
            is AppSettingsIntent.OnShowContinueReadingToggled -> setShowContinueReading(intent.enabled)
            AppSettingsIntent.OnShareLogsClicked -> shareLogs()
            AppSettingsIntent.OnShareLogsFailedMessageShown -> onLogShareFailedMessageShown()
            AppSettingsIntent.OnClearLogsClicked -> clearLogs()
            AppSettingsIntent.OnLogsClearedMessageShown -> onLogsClearedMessageShown()
            AppSettingsIntent.OnLogsClearFailedMessageShown -> onLogsClearFailedMessageShown()
            AppSettingsIntent.OnNoLogsMessageShown -> onNoLogsMessageShown()
            AppSettingsIntent.OnClearCurrentBookClicked -> clearCurrentBook()
            AppSettingsIntent.OnCurrentBookClearedMessageShown -> onCurrentBookClearedMessageShown()
            AppSettingsIntent.OnCurrentBookClearFailedMessageShown -> onCurrentBookClearFailedMessageShown()
            is AppSettingsIntent.OnProfileSelected -> selectProfile(intent.profileId)
            AppSettingsIntent.OnAddProfileClicked -> showAddProfileDialog()
            is AppSettingsIntent.OnAddProfileConfirmed -> addProfile(intent.name)
            is AppSettingsIntent.OnAddProfileDismissed -> hideAddProfileDialog(intent.entryPoint)
            AppSettingsIntent.OnProfileNameEdited -> onProfileNameEdited()
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
        updateState {
            it.copy(
                showAddProfileDialog = true,
                showProfileOperationFailedMessage = false,
                showDuplicateProfileNameError = false,
            )
        }
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
        updateState {
            it.copy(
                showAddProfileDialog = false,
                showProfileOperationFailedMessage = false,
                showDuplicateProfileNameError = false,
            )
        }
    }

    private fun addProfile(name: String) {
        if (name.isBlank() || !viewState.value.showAddProfileDialog) return
        if (isDuplicateProfileName(name, viewState.value.userProfiles)) {
            rejectDuplicateProfileName(AppSettingsAnalyticsEvent.ProfileOperation.Create)
            return
        }
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
                    it.copy(
                        showAddProfileDialog = false,
                        showProfileOperationFailedMessage = false,
                        showDuplicateProfileNameError = false,
                    )
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
        updateState {
            it.copy(
                showRenameProfileDialog = true,
                showProfileOperationFailedMessage = false,
                showDuplicateProfileNameError = false,
            )
        }
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
                showDuplicateProfileNameError = false,
            )
        }
    }

    private fun renameProfile(newName: String) {
        val profile = viewState.value.selectedProfileForMenu ?: return
        if (newName.isBlank() || !viewState.value.showRenameProfileDialog) return
        if (
            isDuplicateProfileName(
                candidate = newName,
                profiles = viewState.value.userProfiles,
                excludingProfileId = profile.id,
            )
        ) {
            rejectDuplicateProfileName(AppSettingsAnalyticsEvent.ProfileOperation.Rename)
            return
        }
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
                        showDuplicateProfileNameError = false,
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
            var activeProfileFallbackApplied = false
            val succeeded = runProfileOperation(
                operation = AppSettingsAnalyticsEvent.ProfileOperation.Delete,
                retryKey = retryKey,
                successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileDeleted(isRetry) },
                successReasonCode = {
                    "active_profile_fallback_activated".takeIf { activeProfileFallbackApplied }
                },
            ) {
                val wasActiveProfile = userRegistry.getActiveProfileId() == profileId
                userRegistry.deleteProfile(profileId)
                check(userRegistry.getProfile(profileId) == null) {
                    "profile_deletion_not_applied"
                }
                if (wasActiveProfile) {
                    val activeProfileId = userRegistry.getActiveProfileId()
                    check(
                        activeProfileId != null &&
                            activeProfileId != profileId &&
                            userRegistry.getProfile(activeProfileId) != null,
                    ) {
                        "active_profile_fallback_not_applied"
                    }
                    activeProfileFallbackApplied = true
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

    private fun onProfileNameEdited() {
        if (!viewState.value.showDuplicateProfileNameError) return
        updateState { it.copy(showDuplicateProfileNameError = false) }
    }

    private fun rejectDuplicateProfileName(operation: AppSettingsAnalyticsEvent.ProfileOperation) {
        reportDuplicateProfileNameRejected(analytics, operation)
        updateState {
            it.copy(
                showProfileOperationFailedMessage = false,
                showDuplicateProfileNameError = true,
            )
        }
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
        successReasonCode: (() -> String?)? = null,
        execute: suspend () -> Unit,
    ): Boolean {
        val isRetry = profileOperationRetryTracker.isRetry(retryKey)
        val succeeded = executeProfileOperation(
            analytics = analytics,
            operation = operation,
            isRetry = isRetry,
            successEvent = successEvent,
            successReasonCode = successReasonCode,
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
        runAppSettingToggle(
            setting = AppSettingsAnalyticsEvent.SettingToggle.FileLogging,
            enabled = enabled,
            persist = { preferences.putBoolean(PreferencesKey.FileLoggingEnabled, enabled) },
            successEvent = { isRetry ->
                AppSettingsAnalyticsEvent.FileLoggingToggled(isEnabled = enabled, isRetry = isRetry)
            },
        ) {
            updateState { it.copy(isLoggingEnabled = enabled) }
        }
    }

    private fun setLogCrashesOnly(enabled: Boolean) {
        runAppSettingToggle(
            setting = AppSettingsAnalyticsEvent.SettingToggle.CrashOnlyLogging,
            enabled = enabled,
            persist = { preferences.putBoolean(PreferencesKey.FileLoggingCrashesOnly, enabled) },
            successEvent = { isRetry ->
                AppSettingsAnalyticsEvent.CrashOnlyLoggingToggled(isEnabled = enabled, isRetry = isRetry)
            },
        ) {
            updateState { it.copy(logCrashesOnly = enabled) }
        }
    }

    private fun setOpenLastBookOnLaunch(enabled: Boolean) {
        runAppSettingToggle(
            setting = AppSettingsAnalyticsEvent.SettingToggle.OpenLastBookOnLaunch,
            enabled = enabled,
            persist = { preferences.putBoolean(PreferencesKey.OpenLastBookOnLaunch, enabled) },
            successEvent = { isRetry ->
                AppSettingsAnalyticsEvent.OpenLastBookOnLaunchToggled(isEnabled = enabled, isRetry = isRetry)
            },
        ) {
            updateState { it.copy(openLastBookOnLaunch = enabled) }
        }
    }

    private fun setShowContinueReading(enabled: Boolean) {
        runAppSettingToggle(
            setting = AppSettingsAnalyticsEvent.SettingToggle.ShowContinueReading,
            enabled = enabled,
            persist = { preferences.putBoolean(PreferencesKey.ShowContinueReading, enabled) },
            successEvent = { isRetry ->
                AppSettingsAnalyticsEvent.ShowContinueReadingToggled(isEnabled = enabled, isRetry = isRetry)
            },
        ) {
            updateState { it.copy(showContinueReading = enabled) }
        }
    }

    private fun runAppSettingToggle(
        setting: AppSettingsAnalyticsEvent.SettingToggle,
        enabled: Boolean,
        persist: () -> Unit,
        successEvent: (isRetry: Boolean) -> AnalyticsEvent,
        onSuccess: () -> Unit,
    ) {
        val isRetry = failedSettingToggleTargets[setting] == enabled
        val succeeded = executeAppSettingToggle(
            analytics = analytics,
            setting = setting,
            isEnabled = enabled,
            isRetry = isRetry,
            persist = persist,
            successEvent = successEvent,
        )
        if (succeeded) {
            failedSettingToggleTargets.remove(setting)
            onSuccess()
        } else {
            failedSettingToggleTargets[setting] = enabled
            updateState { it.withAppSettingSaveFailure() }
        }
    }

    private fun shareLogs() {
        val isRetry = viewState.value.canRetryLogShare
        val outcome = executeLogShare(
            analytics = analytics,
            isRetry = isRetry,
            readLogContents = fileLogger::getLogContents,
            launchShareSheet = {
                fileSharer.shareFile(
                    filePath = fileLogger.getLogFilePath(),
                    mimeType = "text/plain",
                    title = "Share App Logs",
                )
            },
        )
        updateState {
            when (outcome) {
                LogShareOutcome.NoLogs -> it.copy(
                    showNoLogsMessage = true,
                    showLogShareFailedMessage = false,
                    canRetryLogShare = false,
                )
                LogShareOutcome.Opened -> it.copy(
                    showLogShareFailedMessage = false,
                    canRetryLogShare = false,
                )
                LogShareOutcome.Failed -> it.copy(
                    showLogShareFailedMessage = true,
                    canRetryLogShare = true,
                )
            }
        }
    }

    private fun onLogShareFailedMessageShown() {
        updateState { it.copy(showLogShareFailedMessage = false) }
    }

    private fun clearLogs() {
        val succeeded = executeLogsClear(
            analytics = analytics,
            isRetry = viewState.value.canRetryLogsClear,
            clear = fileLogger::clearLogs,
        )
        updateState { it.withLogsClearOutcome(succeeded) }
    }

    private fun onLogsClearedMessageShown() {
        updateState { it.copy(showLogsClearedMessage = false) }
    }

    private fun onLogsClearFailedMessageShown() {
        updateState { it.copy(showLogsClearFailedMessage = false) }
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
