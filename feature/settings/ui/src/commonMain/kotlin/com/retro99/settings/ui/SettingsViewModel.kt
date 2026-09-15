package com.retro99.settings.ui

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.BookAnalyticsEvent
import com.retro99.analytics.api.ReaderAnalyticsEvent
import com.retro99.base.result.AppError
import com.retro99.base.ui.BaseViewModel
import com.retro99.reader.domain.usecase.GetCustomReaderFontsUseCase
import com.retro99.reader.domain.usecase.GetReaderSettingsUseCase
import com.retro99.reader.domain.usecase.ImportCustomReaderFontUseCase
import com.retro99.reader.domain.usecase.SaveReaderSettingsUseCase
import com.retro99.settings.ui.model.ReaderSettingsUiModel
import com.retro99.settings.ui.model.toDomainModel
import com.retro99.settings.ui.model.toUiModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class SettingsViewModel(
    @Provided private val getReaderSettingsUseCase: GetReaderSettingsUseCase,
    @Provided private val saveReaderSettingsUseCase: SaveReaderSettingsUseCase,
    @Provided private val getCustomReaderFontsUseCase: GetCustomReaderFontsUseCase,
    @Provided private val importCustomReaderFontUseCase: ImportCustomReaderFontUseCase,
    @Provided private val analytics: Analytics,
) : BaseViewModel<SettingsViewState, SettingsIntent>(SettingsViewState()) {

    init {
        observeReaderSettings()
    }

    override fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.OnSectionToggled -> toggleSection(intent.section)
            is SettingsIntent.OnFontsToggled -> toggleFonts()
            SettingsIntent.OnUndoSettingsChange -> undoSettingsChange()
            SettingsIntent.OnDismissSettingsUndo -> dismissSettingsUndo()
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
        // Only track when expanding, not collapsing
        if (!isCurrentlyExpanded) {
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
        updateState { state ->
            state.copy(isFontsExpanded = !state.isFontsExpanded)
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
                updateState {
                    it.copy(
                        readerSettings = uiModel,
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
        analytics.logEvent(ReaderAnalyticsEvent.SettingChanged(settingName, newValue))

        val currentSettings = viewState.value.readerSettings
        val newSettings = update(currentSettings)
        updateState {
            it.copy(
                readerSettings = newSettings,
                undoReaderSettings = it.undoReaderSettings ?: currentSettings,
                undoRequestId = it.undoRequestId + 1,
            )
        }

        viewModelScope.launch {
            saveReaderSettingsUseCase(newSettings.toDomainModel())
        }
    }

    private fun undoSettingsChange() {
        val undoSettings = viewState.value.undoReaderSettings ?: return
        updateState {
            it.copy(
                readerSettings = undoSettings,
                undoReaderSettings = null,
            )
        }
        viewModelScope.launch {
            saveReaderSettingsUseCase(undoSettings.toDomainModel())
        }
    }

    private fun dismissSettingsUndo() {
        updateState { it.copy(undoReaderSettings = null) }
    }
}
