package com.retro99.settings.ui

import com.retro99.base.ui.BaseIntent
import com.retro99.reader.domain.model.ChapterProgressDisplayMode
import com.retro99.reader.domain.model.HighlightStyle
import com.retro99.reader.domain.model.NavigationAction
import com.retro99.reader.domain.model.ProgressBarPosition
import com.retro99.reader.domain.model.ProgressIndicatorMode
import com.retro99.settings.ui.model.FontFamilyUiModel
import com.retro99.settings.ui.model.ReaderTextAlignUiModel
import com.retro99.settings.ui.model.ReaderThemeUiModel
import io.github.vinceglb.filekit.core.PlatformFile

sealed interface SettingsIntent : BaseIntent {
    // Section expansion
    data class OnSectionToggled(val section: SettingsSection) : SettingsIntent
    data object OnFontsToggled : SettingsIntent
    data object OnUndoSettingsChange : SettingsIntent
    data object OnDismissSettingsUndo : SettingsIntent
    data object OnRetrySettingsSave : SettingsIntent
    data object OnDismissSettingsSaveFailure : SettingsIntent
    data object OnCustomFontImportCancelled : SettingsIntent

    // Reader settings intents
    data class OnThemeChanged(val theme: ReaderThemeUiModel) : SettingsIntent
    data class OnFontSizeChanged(val fontSize: Double) : SettingsIntent
    data class OnFontFamilyChanged(val fontFamily: FontFamilyUiModel) : SettingsIntent
    data class OnFontWeightChanged(val fontWeight: Double) : SettingsIntent
    data class OnTextNormalizationChanged(val textNormalization: Boolean) : SettingsIntent
    data class OnCustomFontSelected(val file: PlatformFile) : SettingsIntent
    data class OnLineHeightChanged(val lineHeight: Float) : SettingsIntent
    data class OnParagraphSpacingChanged(val paragraphSpacing: Double) : SettingsIntent
    data class OnMarginHorizontalChanged(val margin: Int) : SettingsIntent
    data class OnMarginVerticalChanged(val margin: Int) : SettingsIntent
    data class OnTextAlignChanged(val textAlign: ReaderTextAlignUiModel) : SettingsIntent
    data class OnScrollModeChanged(val scrollMode: Boolean?) : SettingsIntent
    data class OnPublisherStylesChanged(val publisherStyles: Boolean) : SettingsIntent
    data class OnShowProgressBarChanged(val showProgressBar: Boolean?) : SettingsIntent
    data class OnChapterProgressDisplayModeChanged(
        val mode: ChapterProgressDisplayMode,
    ) : SettingsIntent

    // Progress settings intents
    data class OnShowTotalProgressChanged(val showTotalProgress: Boolean) : SettingsIntent
    data class OnProgressIndicatorModeChanged(val mode: ProgressIndicatorMode) : SettingsIntent
    data class OnProgressBarPositionChanged(val position: ProgressBarPosition) : SettingsIntent

    // ReadAloud settings intents
    data class OnHighlightColorChanged(val colorArgb: Int) : SettingsIntent
    data class OnUnderlineColorChanged(val colorArgb: Int) : SettingsIntent
    data class OnHighlightStyleChanged(val style: HighlightStyle) : SettingsIntent

    // Fullscreen mode intent
    data class OnFullscreenModeChanged(val fullscreenMode: Boolean) : SettingsIntent

    // Show current time intent
    data class OnShowCurrentTimeChanged(val showCurrentTime: Boolean) : SettingsIntent

    // Show reading time intent
    data class OnShowReadingTimeChanged(val showReadingTime: Boolean) : SettingsIntent

    // Volume button navigation intents
    data class OnVolumeButtonsEnabledChanged(val enabled: Boolean) : SettingsIntent
    data class OnVolumeUpActionChanged(val action: NavigationAction) : SettingsIntent
    data class OnVolumeDownActionChanged(val action: NavigationAction) : SettingsIntent

    // Tap navigation intents
    data class OnTapNavigationEnabledChanged(val enabled: Boolean) : SettingsIntent
    data class OnLeftTapActionChanged(val action: NavigationAction) : SettingsIntent
    data class OnRightTapActionChanged(val action: NavigationAction) : SettingsIntent

    // Double-tap timeout intent
    data class OnDoubleTapTimeoutChanged(val timeoutMs: Int) : SettingsIntent

    // Audio progress bar visibility intent
    data class OnShowAudioProgressBarChanged(val showAudioProgressBar: Boolean?) : SettingsIntent

    // Keep screen awake during ReadAloud audio intent
    data class OnKeepScreenOnDuringAudioChanged(val enabled: Boolean) : SettingsIntent

    // Enable on-device text-to-speech read-aloud
    data class OnTtsEnabledChanged(val enabled: Boolean) : SettingsIntent
}
