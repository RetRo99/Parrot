package com.retro99.settings.ui

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.BookAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.ReaderAnalyticsEvent
import com.retro99.base.result.AppError
import com.retro99.base.result.log
import com.retro99.base.ui.BaseViewModel
import com.retro99.reader.domain.usecase.GetCustomReaderFontsUseCase
import com.retro99.reader.domain.usecase.GetReaderSettingsUseCase
import com.retro99.reader.domain.usecase.ImportCustomReaderFontUseCase
import com.retro99.reader.domain.usecase.SaveReaderSettingsUseCase
import com.retro99.reader.domain.write.LinkedCopyPropagationSetting
import com.retro99.settings.ui.model.ReaderSettingsUiModel
import com.retro99.settings.ui.model.toDomainModel
import com.retro99.settings.ui.model.toUiModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@KoinViewModel
class SettingsViewModel(
    @Provided private val getReaderSettingsUseCase: GetReaderSettingsUseCase,
    @Provided private val saveReaderSettingsUseCase: SaveReaderSettingsUseCase,
    @Provided private val getCustomReaderFontsUseCase: GetCustomReaderFontsUseCase,
    @Provided private val importCustomReaderFontUseCase: ImportCustomReaderFontUseCase,
    @Provided private val analytics: Analytics,
    @Provided private val linkedCopyPropagationSetting: LinkedCopyPropagationSetting,
) : BaseViewModel<SettingsViewState, SettingsIntent>(SettingsViewState()) {

    private val saveMutex = Mutex()
    private var persistedReaderSettings = ReaderSettingsUiModel()
    private var pendingSaveCount = 0
    private var latestSaveRequestId = 0
    private var nextFailureRequestId = 0
    private var failedSaveRequest: ReaderSettingSaveRequest? = null

    init {
        observeReaderSettings()
        linkedCopyPropagationSetting.observeEnabled()
            .onEach { enabled -> updateState { state -> state.copy(updateLinkedCopies = enabled) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.OnSectionToggled -> toggleSection(intent.section)
            is SettingsIntent.OnFontsToggled -> toggleFonts()
            SettingsIntent.OnUndoSettingsChange -> undoSettingsChange()
            SettingsIntent.OnDismissSettingsUndo -> dismissSettingsUndo()
            SettingsIntent.OnRetrySettingsSave -> retryFailedSettingsSave()
            SettingsIntent.OnDismissSettingsSaveFailure -> dismissSettingsSaveFailure()
            SettingsIntent.OnCustomFontImportCancelled -> {
                analytics.logEvent(ReaderAnalyticsEvent.CustomFontImportCancelled)
                analytics.logBreadcrumb(
                    DiagnosticContext(
                        screen = "reader_settings",
                        action = "import_custom_font",
                        operation = "custom_font_import",
                        stage = "terminal",
                        outcome = "cancelled",
                    ),
                )
            }
            is SettingsIntent.OnThemeChanged -> updateReaderSetting("theme", intent.theme.name) {
                it.copy(theme = intent.theme)
            }

            is SettingsIntent.OnFontSizeChanged -> updateReaderSetting(
                "font_size",
                intent.fontSize.toString(),
            ) {
                it.copy(fontSize = intent.fontSize)
            }

            is SettingsIntent.OnFontFamilyChanged -> updateReaderSetting(
                "font_family",
                intent.fontFamily.cssValue,
            ) {
                it.copy(fontFamily = intent.fontFamily)
            }

            is SettingsIntent.OnFontWeightChanged -> updateReaderSetting(
                "font_weight",
                intent.fontWeight.toString(),
            ) {
                it.copy(fontWeight = intent.fontWeight)
            }

            is SettingsIntent.OnTextNormalizationChanged -> updateReaderSetting(
                "text_normalization",
                intent.textNormalization.toString(),
            ) {
                it.copy(textNormalization = intent.textNormalization)
            }

            is SettingsIntent.OnCustomFontSelected -> importCustomFont(intent.file)

            is SettingsIntent.OnLineHeightChanged -> updateReaderSetting(
                "line_height",
                intent.lineHeight.toString(),
            ) {
                it.copy(lineHeight = intent.lineHeight)
            }

            is SettingsIntent.OnParagraphSpacingChanged -> updateReaderSetting(
                "paragraph_spacing",
                intent.paragraphSpacing.toString(),
            ) {
                it.copy(paragraphSpacing = intent.paragraphSpacing)
            }

            is SettingsIntent.OnMarginHorizontalChanged -> updateReaderSetting(
                "margin_horizontal",
                intent.margin.toString(),
            ) {
                it.copy(marginHorizontal = intent.margin)
            }

            is SettingsIntent.OnMarginVerticalChanged -> updateReaderSetting(
                "margin_vertical",
                intent.margin.toString(),
            ) {
                it.copy(marginVertical = intent.margin)
            }

            is SettingsIntent.OnTextAlignChanged -> updateReaderSetting(
                "text_align",
                intent.textAlign.name,
            ) {
                it.copy(textAlign = intent.textAlign)
            }

            is SettingsIntent.OnScrollModeChanged -> updateReaderSetting(
                "scroll_mode",
                intent.scrollMode.toString(),
            ) {
                it.copy(scrollMode = intent.scrollMode)
            }

            is SettingsIntent.OnPublisherStylesChanged -> updateReaderSetting(
                "publisher_styles",
                intent.publisherStyles.toString(),
            ) {
                it.copy(publisherStyles = intent.publisherStyles)
            }

            is SettingsIntent.OnShowProgressBarChanged -> updateReaderSetting(
                "show_progress_bar",
                intent.showProgressBar.toString(),
            ) {
                it.copy(showProgressBar = intent.showProgressBar)
            }

            is SettingsIntent.OnChapterProgressDisplayModeChanged -> updateReaderSetting(
                "chapter_progress_display_mode",
                intent.mode.name,
            ) {
                it.copy(chapterProgressDisplayMode = intent.mode)
            }

            is SettingsIntent.OnShowTotalProgressChanged -> updateReaderSetting(
                "show_total_progress",
                intent.showTotalProgress.toString(),
            ) {
                it.copy(showTotalProgress = intent.showTotalProgress)
            }

            is SettingsIntent.OnProgressIndicatorModeChanged -> updateReaderSetting(
                "progress_indicator_mode",
                intent.mode.name,
            ) {
                it.copy(progressIndicatorMode = intent.mode)
            }

            is SettingsIntent.OnProgressBarPositionChanged -> updateReaderSetting(
                "progress_bar_position",
                intent.position.name,
            ) {
                it.copy(progressBarPosition = intent.position)
            }

            is SettingsIntent.OnHighlightColorChanged -> updateReaderSetting(
                "highlight_color_argb",
                intent.colorArgb.toString(),
            ) {
                it.copy(highlightColor = intent.colorArgb)
            }

            is SettingsIntent.OnUnderlineColorChanged -> updateReaderSetting(
                "underline_color_argb",
                intent.colorArgb.toString(),
            ) {
                it.copy(underlineColor = intent.colorArgb)
            }

            is SettingsIntent.OnHighlightStyleChanged -> updateReaderSetting(
                "highlight_style",
                intent.style.name,
            ) {
                it.copy(highlightStyle = intent.style)
            }

            is SettingsIntent.OnFullscreenModeChanged -> updateReaderSetting(
                "fullscreen_mode",
                intent.fullscreenMode.toString(),
            ) {
                it.copy(fullscreenMode = intent.fullscreenMode)
            }

            is SettingsIntent.OnShowCurrentTimeChanged -> updateReaderSetting(
                "show_current_time",
                intent.showCurrentTime.toString(),
            ) {
                it.copy(showCurrentTime = intent.showCurrentTime)
            }

            is SettingsIntent.OnUpdateLinkedCopiesChanged -> {
                updateState { state -> state.copy(updateLinkedCopies = intent.enabled) }
                viewModelScope.launch { linkedCopyPropagationSetting.setEnabled(intent.enabled) }
            }

            is SettingsIntent.OnShowReadingTimeChanged -> updateReaderSetting(
                "show_reading_time",
                intent.showReadingTime.toString(),
            ) {
                it.copy(showReadingTime = intent.showReadingTime)
            }

            is SettingsIntent.OnVolumeButtonsEnabledChanged -> updateReaderSetting(
                "volume_buttons_enabled",
                intent.enabled.toString(),
            ) {
                it.copy(volumeButtonsEnabled = intent.enabled)
            }

            is SettingsIntent.OnVolumeUpActionChanged -> updateReaderSetting(
                "volume_up_action",
                intent.action.name,
            ) {
                it.copy(volumeUpAction = intent.action)
            }

            is SettingsIntent.OnVolumeDownActionChanged -> updateReaderSetting(
                "volume_down_action",
                intent.action.name,
            ) {
                it.copy(volumeDownAction = intent.action)
            }

            is SettingsIntent.OnTapNavigationEnabledChanged -> updateReaderSetting(
                "tap_navigation_enabled",
                intent.enabled.toString(),
            ) {
                it.copy(tapNavigationEnabled = intent.enabled)
            }

            is SettingsIntent.OnLeftTapActionChanged -> updateReaderSetting(
                "left_tap_action",
                intent.action.name,
            ) {
                it.copy(leftTapAction = intent.action)
            }

            is SettingsIntent.OnRightTapActionChanged -> updateReaderSetting(
                "right_tap_action",
                intent.action.name,
            ) {
                it.copy(rightTapAction = intent.action)
            }

            is SettingsIntent.OnDoubleTapTimeoutChanged -> updateReaderSetting(
                "double_tap_timeout_ms",
                intent.timeoutMs.toString(),
            ) {
                it.copy(doubleTapTimeoutMs = intent.timeoutMs)
            }

            is SettingsIntent.OnShowAudioProgressBarChanged -> updateReaderSetting(
                "show_audio_progress_bar",
                intent.showAudioProgressBar?.toString() ?: "null",
            ) {
                it.copy(showAudioProgressBar = intent.showAudioProgressBar)
            }

            is SettingsIntent.OnKeepScreenOnDuringAudioChanged -> updateReaderSetting(
                "keep_screen_on_during_audio",
                intent.enabled.toString(),
            ) {
                it.copy(keepScreenOnDuringAudio = intent.enabled)
            }

            is SettingsIntent.OnHideSearchAheadChanged -> updateReaderSetting(
                "hide_search_results_ahead",
                intent.enabled.toString(),
            ) {
                it.copy(hideSearchResultsAhead = intent.enabled)
            }

            is SettingsIntent.OnTtsEnabledChanged -> {
                analytics.logEvent(
                    ReaderAnalyticsEvent.TtsEnabledChanged(isEnabled = intent.enabled),
                )
                updateReaderSetting(
                    "tts_enabled",
                    intent.enabled.toString(),
                ) { settings ->
                    settings.copy(ttsEnabled = intent.enabled)
                }
            }

        }
    }

    private fun toggleSection(section: SettingsSection) {
        val isCurrentlyExpanded = section in viewState.value.expandedSections
        if (isCurrentlyExpanded) {
            analytics.logEvent(
                ReaderAnalyticsEvent.ReaderSettingsSectionCollapsed(
                    sectionName = section.name.lowercase(),
                ),
            )
        } else {
            analytics.logEvent(
                ReaderAnalyticsEvent.SettingsSectionExpanded(
                    sectionName = section.name.lowercase(),
                )
            )
        }
        updateState { state ->
            val newExpandedSections = if (section in state.expandedSections) {
                state.expandedSections - section
            } else {
                state.expandedSections + section
            }
            state.copy(expandedSections = newExpandedSections)
        }
    }

    private fun toggleFonts() {
        val willExpand = !viewState.value.isFontsExpanded
        analytics.logEvent(ReaderAnalyticsEvent.ReaderSettingsFontsToggled(willExpand))
        updateState { state ->
            state.copy(isFontsExpanded = willExpand)
        }
    }

    private fun observeReaderSettings() {
        combine(
            getReaderSettingsUseCase(),
            getCustomReaderFontsUseCase(),
        ) { settings, customFonts ->
            settings to customFonts
        }
            .onEach { (settings, customFonts) ->
                val uiCustomFonts = customFonts.map { it.toUiModel() }
                val uiModel = settings.toUiModel().copy(
                    fontFamily = settings.fontFamily.toUiModel(customFonts),
                )
                persistedReaderSettings = uiModel
                updateState { state ->
                    state.copy(
                        readerSettings = if (pendingSaveCount == 0) uiModel else state.readerSettings,
                        customFonts = uiCustomFonts,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun importCustomFont(file: io.github.vinceglb.filekit.core.PlatformFile) {
        viewModelScope.launch {
            importCustomReaderFontUseCase(file)
                .onSuccess { font ->
                    val uiFont = font.toUiModel()
                    analytics.logEvent(BookAnalyticsEvent.CustomFontImported(fontName = uiFont.cssValue))
                    updateReaderSetting("font_family", uiFont.cssValue) {
                        it.copy(fontFamily = uiFont)
                    }
                }
                .onFailure { error ->
                    val throwable = when (error) {
                        is AppError.NetworkError -> error.throwable
                        is AppError.DatabaseError -> error.throwable
                        is AppError.UnknownError -> error.throwable
                        is AppError.ApiError -> Exception(error.message)
                        is AppError.AuthError -> Exception(error.message)
                        is AppError.NotFoundError -> Exception(error.message)
                    }
                    analytics.logException(throwable, "SettingsViewModel: Failed to import custom font")
                }
        }
    }

    private fun updateReaderSetting(
        settingName: String,
        newValue: String,
        update: (ReaderSettingsUiModel) -> ReaderSettingsUiModel,
    ) {
        val currentSettings = viewState.value.readerSettings
        val newSettings = update(currentSettings)
        if (newSettings == currentSettings) return

        failedSaveRequest = null
        val request = ReaderSettingSaveRequest(
            settingName = settingName,
            newValue = newValue,
            target = newSettings,
            rollback = currentSettings,
            correlationId = newCorrelationId(),
            requestId = ++latestSaveRequestId,
        )
        updateState {
            it.copy(
                readerSettings = newSettings,
                undoReaderSettings = null,
                undoSettingName = null,
                undoRequestId = it.undoRequestId + 1,
                settingSaveFailureRequestId = null,
            )
        }
        persistReaderSettings(request)
    }

    private fun persistReaderSettings(request: ReaderSettingSaveRequest) {
        pendingSaveCount += 1
        analytics.logEvent(
            ReaderAnalyticsEvent.ReaderSettingSaveAttempted(
                settingName = request.settingName,
                isRetry = request.isRetry,
                isUndo = request.isUndo,
            ),
        )
        analytics.logBreadcrumb(request.diagnosticContext(stage = "started", outcome = "started"))
        viewModelScope.launch {
            val result = try {
                saveMutex.withLock {
                    saveReaderSettingsUseCase(request.target.toDomainModel())
                }
            } catch (cancelled: CancellationException) {
                pendingSaveCount = (pendingSaveCount - 1).coerceAtLeast(0)
                analytics.logEvent(
                    ReaderAnalyticsEvent.ReaderSettingSaveCancelled(
                        settingName = request.settingName,
                        isRetry = request.isRetry,
                        isUndo = request.isUndo,
                    ),
                )
                analytics.logBreadcrumb(
                    request.diagnosticContext(stage = "terminal", outcome = "cancelled"),
                )
                if (request.requestId == latestSaveRequestId && pendingSaveCount == 0) {
                    updateState { state ->
                        state.copy(
                            readerSettings = persistedReaderSettings,
                            undoReaderSettings = null,
                            undoSettingName = null,
                            settingSaveFailureRequestId = null,
                        )
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                Err(AppError.UnknownError(error))
            }

            result.onSuccess {
                handleReaderSettingsSaveSucceeded(request)
            }.onFailure { error ->
                handleReaderSettingsSaveFailed(request, error)
            }
        }
    }

    private fun handleReaderSettingsSaveSucceeded(request: ReaderSettingSaveRequest) {
        persistedReaderSettings = request.target
        pendingSaveCount = (pendingSaveCount - 1).coerceAtLeast(0)
        analytics.logEvent(
            ReaderAnalyticsEvent.ReaderSettingSaveSucceeded(
                settingName = request.settingName,
                isRetry = request.isRetry,
                isUndo = request.isUndo,
            ),
        )
        if (request.isUndo) {
            analytics.logEvent(ReaderAnalyticsEvent.ReaderSettingChangeUndone)
        } else {
            analytics.logEvent(
                ReaderAnalyticsEvent.SettingChanged(
                    settingName = request.settingName,
                    newValue = request.newValue,
                    isRetry = request.isRetry,
                ),
            )
        }
        analytics.logBreadcrumb(
            request.diagnosticContext(
                stage = "terminal",
                outcome = if (request.isUndo) "reversed" else "succeeded",
            ),
        )

        if (request.requestId == latestSaveRequestId && pendingSaveCount == 0) {
            failedSaveRequest = null
            updateState { state ->
                state.copy(
                    readerSettings = request.target,
                    undoReaderSettings = request.rollback.takeUnless { request.isUndo },
                    undoSettingName = request.settingName.takeUnless { request.isUndo },
                    undoRequestId = if (request.isUndo) state.undoRequestId else state.undoRequestId + 1,
                    settingSaveFailureRequestId = null,
                )
            }
        }
    }

    private fun handleReaderSettingsSaveFailed(request: ReaderSettingSaveRequest, error: AppError) {
        pendingSaveCount = (pendingSaveCount - 1).coerceAtLeast(0)
        val reasonCode = error.toReaderSettingSaveReasonCode()
        analytics.logEvent(
            ReaderAnalyticsEvent.ReaderSettingSaveFailed(
                settingName = request.settingName,
                reasonCode = reasonCode,
                isRetry = request.isRetry,
                isUndo = request.isUndo,
            ),
        )
        analytics.logBreadcrumb(
            request.diagnosticContext(
                stage = "terminal",
                outcome = "failed",
                reasonCode = reasonCode,
            ),
        )
        error.log(
            analytics,
            request.diagnosticContext(
                stage = "terminal",
                outcome = "failed",
                reasonCode = reasonCode,
            ),
        )

        if (request.requestId == latestSaveRequestId && pendingSaveCount == 0) {
            failedSaveRequest = request.copy(isRetry = false)
            val failureRequestId = ++nextFailureRequestId
            updateState { state ->
                state.copy(
                    readerSettings = persistedReaderSettings,
                    undoReaderSettings = null,
                    undoSettingName = null,
                    undoRequestId = state.undoRequestId + 1,
                    settingSaveFailureRequestId = failureRequestId,
                )
            }
        }
    }

    private fun retryFailedSettingsSave() {
        val failed = failedSaveRequest ?: return
        failedSaveRequest = null
        val retry = failed.copy(
            requestId = ++latestSaveRequestId,
            isRetry = true,
        )
        updateState { state ->
            state.copy(
                readerSettings = retry.target,
                undoReaderSettings = null,
                undoSettingName = null,
                undoRequestId = state.undoRequestId + 1,
                settingSaveFailureRequestId = null,
            )
        }
        persistReaderSettings(retry)
    }

    private fun dismissSettingsSaveFailure() {
        val failed = failedSaveRequest ?: return
        failedSaveRequest = null
        analytics.logEvent(ReaderAnalyticsEvent.ReaderSettingSaveAbandoned(failed.settingName))
        analytics.logBreadcrumb(
            failed.diagnosticContext(stage = "recovery", outcome = "abandoned"),
        )
        updateState { it.copy(settingSaveFailureRequestId = null) }
    }

    private fun undoSettingsChange() {
        val undoSettings = viewState.value.undoReaderSettings ?: return
        val settingName = viewState.value.undoSettingName ?: return
        val currentSettings = viewState.value.readerSettings
        failedSaveRequest = null
        val request = ReaderSettingSaveRequest(
            settingName = settingName,
            newValue = "reversed",
            target = undoSettings,
            rollback = currentSettings,
            correlationId = newCorrelationId(),
            requestId = ++latestSaveRequestId,
            isUndo = true,
        )
        updateState {
            it.copy(
                readerSettings = undoSettings,
                undoReaderSettings = null,
                undoSettingName = null,
                undoRequestId = it.undoRequestId + 1,
                settingSaveFailureRequestId = null,
            )
        }
        persistReaderSettings(request)
    }

    private fun dismissSettingsUndo() {
        updateState { it.copy(undoReaderSettings = null, undoSettingName = null) }
    }

    private fun ReaderSettingSaveRequest.diagnosticContext(
        stage: String,
        outcome: String,
        reasonCode: String? = null,
    ) = DiagnosticContext(
        screen = "reader_settings",
        action = if (isUndo) "undo_setting_change" else "save_setting",
        operation = if (isUndo) "reader_setting_undo" else "reader_setting_save",
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        correlationId = correlationId,
    )

    private fun AppError.toReaderSettingSaveReasonCode(): String = when (this) {
        is AppError.NetworkError -> when {
            isTimeout -> "timeout"
            isConnectivity -> "connectivity"
            else -> "network_error"
        }
        is AppError.ApiError -> "api_error"
        is AppError.DatabaseError -> "database_error"
        is AppError.UnknownError -> "unknown_error"
        is AppError.AuthError -> if (isCancellation) "cancelled" else "auth_error"
        is AppError.NotFoundError -> "not_found"
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newCorrelationId(): String = Uuid.random().toString()

    private data class ReaderSettingSaveRequest(
        val settingName: String,
        val newValue: String,
        val target: ReaderSettingsUiModel,
        val rollback: ReaderSettingsUiModel,
        val correlationId: String,
        val requestId: Int,
        val isRetry: Boolean = false,
        val isUndo: Boolean = false,
    )
}
