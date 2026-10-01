package com.retro99.settings.ui

import com.retro99.reader.domain.model.ChapterProgressDisplayMode
import com.retro99.reader.domain.model.HighlightStyle
import com.retro99.reader.domain.model.NavigationAction
import com.retro99.reader.domain.model.ProgressBarPosition
import com.retro99.reader.domain.model.ProgressIndicatorMode
import com.retro99.settings.ui.model.FontFamilyUiModel
import com.retro99.settings.ui.model.ReaderSettingsUiModel
import com.retro99.settings.ui.model.ReaderTextAlignUiModel
import com.retro99.settings.ui.model.ReaderThemeUiModel

data class SettingsViewState(
    val isLoading: Boolean = false,
    val readerSettings: ReaderSettingsUiModel = ReaderSettingsUiModel(),
    /** "Update my other servers as I read" (project 3, slice 4). */
    val updateLinkedCopies: Boolean = true,
    val undoReaderSettings: ReaderSettingsUiModel? = null,
    val undoSettingName: String? = null,
    val undoRequestId: Int = 0,
    val settingSaveFailureRequestId: Int? = null,
    val customFonts: List<FontFamilyUiModel> = emptyList(),
    val expandedSections: Set<SettingsSection> = emptySet(),
    val isFontsExpanded: Boolean = false,
) {
    // Convenience accessors for UI
    val theme: ReaderThemeUiModel get() = readerSettings.theme
    val fontSize: Double get() = readerSettings.fontSize
    val fontFamily: FontFamilyUiModel get() = readerSettings.fontFamily
    val fontWeight: Double get() = readerSettings.fontWeight
    val textNormalization: Boolean get() = readerSettings.textNormalization
    val lineHeight: Float get() = readerSettings.lineHeight
    val paragraphSpacing: Double get() = readerSettings.paragraphSpacing
    val marginHorizontal: Int get() = readerSettings.marginHorizontal
    val marginVertical: Int get() = readerSettings.marginVertical
    val textAlign: ReaderTextAlignUiModel get() = readerSettings.textAlign
    val scrollMode: Boolean? get() = readerSettings.scrollMode
    val publisherStyles: Boolean get() = readerSettings.publisherStyles
    val showProgressBar: Boolean? get() = readerSettings.showProgressBar
    val chapterProgressDisplayMode: ChapterProgressDisplayMode
        get() = readerSettings.chapterProgressDisplayMode
    val highlightColor: Int get() = readerSettings.highlightColor
    val underlineColor: Int get() = readerSettings.underlineColor
    val highlightStyle: HighlightStyle get() = readerSettings.highlightStyle
    val showTotalProgress: Boolean get() = readerSettings.showTotalProgress
    val progressIndicatorMode: ProgressIndicatorMode get() = readerSettings.progressIndicatorMode
    val progressBarPosition: ProgressBarPosition get() = readerSettings.progressBarPosition
    val fullscreenMode: Boolean get() = readerSettings.fullscreenMode
    val showCurrentTime: Boolean get() = readerSettings.showCurrentTime
    val showReadingTime: Boolean get() = readerSettings.showReadingTime
    val volumeButtonsEnabled: Boolean get() = readerSettings.volumeButtonsEnabled
    val volumeUpAction: NavigationAction get() = readerSettings.volumeUpAction
    val volumeDownAction: NavigationAction get() = readerSettings.volumeDownAction
    val tapNavigationEnabled: Boolean get() = readerSettings.tapNavigationEnabled
    val leftTapAction: NavigationAction get() = readerSettings.leftTapAction
    val rightTapAction: NavigationAction get() = readerSettings.rightTapAction
    val doubleTapTimeoutMs: Int get() = readerSettings.doubleTapTimeoutMs
    val showAudioProgressBar: Boolean? get() = readerSettings.showAudioProgressBar
    val keepScreenOnDuringAudio: Boolean get() = readerSettings.keepScreenOnDuringAudio
    val ttsEnabled: Boolean get() = readerSettings.ttsEnabled

    fun isSectionExpanded(section: SettingsSection): Boolean = section in expandedSections
}

enum class SettingsSection {
    TYPOGRAPHY,
    LAYOUT,
    PROGRESS,
    NAVIGATION,
    READALOUD,
}
