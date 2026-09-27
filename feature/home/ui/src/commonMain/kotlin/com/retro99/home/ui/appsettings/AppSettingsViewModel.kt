package com.retro99.home.ui.appsettings

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
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
            AppSettingsIntent.OnAddProfileDismissed -> hideAddProfileDialog()
            is AppSettingsIntent.OnProfileLongPressed -> onProfileLongPressed(intent.profileId)
            AppSettingsIntent.OnProfileMenuDismissed -> dismissProfileMenu()
            AppSettingsIntent.OnRenameProfileClicked -> showRenameProfileDialog()
            is AppSettingsIntent.OnRenameProfileConfirmed -> renameProfile(intent.newName)
            AppSettingsIntent.OnRenameProfileDismissed -> hideRenameProfileDialog()
            AppSettingsIntent.OnDeleteProfileClicked -> showDeleteProfileDialog()
            AppSettingsIntent.OnDeleteProfileConfirmed -> deleteProfile()
            AppSettingsIntent.OnDeleteProfileDismissed -> hideDeleteProfileDialog()
        }
    }

    private fun selectProfile(profileId: String) {
        analytics.logEvent(AppSettingsAnalyticsEvent.ProfileSwitched(profileId = profileId))
        viewModelScope.launch {
            userRegistry.setActiveProfile(profileId)
        }
    }

    private fun showAddProfileDialog() {
        updateState { it.copy(showAddProfileDialog = true) }
    }

    private fun hideAddProfileDialog() {
        updateState { it.copy(showAddProfileDialog = false) }
    }

    private fun addProfile(name: String) {
        viewModelScope.launch {
            val profile = userRegistry.createProfile(name = name)
            userRegistry.setActiveProfile(profile.id)
            analytics.logEvent(AppSettingsAnalyticsEvent.ProfileCreated(profileName = name))
            updateState { it.copy(showAddProfileDialog = false) }
        }
    }

    private fun onProfileLongPressed(profileId: String) {
        val profile = viewState.value.userProfiles.find { it.id == profileId }
        updateState { it.copy(selectedProfileForMenu = profile) }
    }

    private fun dismissProfileMenu() {
        updateState { it.copy(selectedProfileForMenu = null) }
    }

    private fun showRenameProfileDialog() {
        updateState { it.copy(showRenameProfileDialog = true) }
    }

    private fun hideRenameProfileDialog() {
        updateState { it.copy(showRenameProfileDialog = false, selectedProfileForMenu = null) }
    }

    private fun renameProfile(newName: String) {
        val profile = viewState.value.selectedProfileForMenu ?: return
        viewModelScope.launch {
            userRegistry.updateProfile(profile.copy(name = newName))
            analytics.logEvent(AppSettingsAnalyticsEvent.ProfileRenamed)
            updateState { it.copy(showRenameProfileDialog = false, selectedProfileForMenu = null) }
        }
    }

    private fun showDeleteProfileDialog() {
        updateState { it.copy(showDeleteProfileDialog = true) }
    }

    private fun hideDeleteProfileDialog() {
        updateState { it.copy(showDeleteProfileDialog = false, selectedProfileForMenu = null) }
    }

    private fun deleteProfile() {
        val profileId = viewState.value.selectedProfileForMenu?.id ?: return
        viewModelScope.launch {
            userRegistry.deleteProfile(profileId)
            analytics.logEvent(AppSettingsAnalyticsEvent.ProfileDeleted)
            updateState { it.copy(showDeleteProfileDialog = false, selectedProfileForMenu = null) }
        }
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
