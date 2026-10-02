package com.retro99.settings.ui.model

import com.retro99.reader.domain.model.ChapterProgressDisplayMode
import com.retro99.reader.domain.model.HighlightStyle
import com.retro99.reader.domain.model.NavigationAction
import com.retro99.reader.domain.model.ProgressBarPosition
import com.retro99.reader.domain.model.ProgressIndicatorMode
import com.retro99.reader.domain.model.ReaderSettingsDomainModel.Companion.DEFAULT_DOUBLE_TAP_TIMEOUT_MS
import com.retro99.reader.domain.model.ReaderSettingsDomainModel.Companion.DEFAULT_HIGHLIGHT_COLOR
import com.retro99.reader.domain.model.ReaderSettingsDomainModel.Companion.DEFAULT_UNDERLINE_COLOR

data class ReaderSettingsUiModel(
    val fontSize: Double = 1.0,
    val fontFamily: FontFamilyUiModel = FontFamilyUiModel.DEFAULT,
    val fontWeight: Double = 1.0,
    val textNormalization: Boolean = false,
    val theme: ReaderThemeUiModel = ReaderThemeUiModel.SYSTEM,
    val lineHeight: Float = 1.5f,
    val paragraphSpacing: Double = 0.0,
    val marginHorizontal: Int = 16,
    val marginVertical: Int = 16,
    val scrollMode: Boolean? = null,
    val textAlign: ReaderTextAlignUiModel = ReaderTextAlignUiModel.START,
    // When true, uses publisher's CSS styles. When false, allows custom lineHeight, textAlign, etc.
    val publisherStyles: Boolean = true,
    // Highlight color for ReadAloud text highlighting (ARGB Int value)
    val highlightColor: Int = DEFAULT_HIGHLIGHT_COLOR,
    // Underline color for ReadAloud text highlighting (ARGB Int value)
    val underlineColor: Int = DEFAULT_UNDERLINE_COLOR,
    // Highlight style for ReadAloud text highlighting
    val highlightStyle: HighlightStyle = HighlightStyle.HIGHLIGHT,
    // Progress bar visibility: true = always, null = on tap (with controls), false = never
    val showProgressBar: Boolean? = true,
    // Chapter progress display mode: NONE, PERCENTAGE, RELATIVE, or FIXED
    val chapterProgressDisplayMode: ChapterProgressDisplayMode = ChapterProgressDisplayMode.RELATIVE,
    // Whether to show total book progress
    val showTotalProgress: Boolean = true,
    // Progress indicator mode: NONE, CHAPTER, or BOOK
    val progressIndicatorMode: ProgressIndicatorMode = ProgressIndicatorMode.CHAPTER,
    // Progress bar position: TOP or BOTTOM
    val progressBarPosition: ProgressBarPosition = ProgressBarPosition.BOTTOM,
    // Whether to hide system bars (status bar, navigation bar) for immersive reading
    val fullscreenMode: Boolean = false,
    // Whether to show current time in the progress bar
    val showCurrentTime: Boolean = true,
    // Whether to show estimated remaining reading time in the progress bar
    val showReadingTime: Boolean = true,
    // Volume button navigation settings (for Onyx Boox e-readers)
    // Whether volume buttons are used for page navigation (only applies to ebooks, not read-aloud)
    val volumeButtonsEnabled: Boolean = false,
    // Action for volume up button
    val volumeUpAction: NavigationAction = NavigationAction.NEXT_PAGE,
    // Action for volume down button
    val volumeDownAction: NavigationAction = NavigationAction.PREVIOUS_PAGE,
    // Whether tap navigation is enabled (tap left/right sides of screen to turn pages)
    val tapNavigationEnabled: Boolean = true,
    // Action for left tap
    val leftTapAction: NavigationAction = NavigationAction.PREVIOUS_PAGE,
    // Action for right tap
    val rightTapAction: NavigationAction = NavigationAction.NEXT_PAGE,
    // Double-tap timeout in milliseconds for detecting double-taps on sentences and page navigation
    val doubleTapTimeoutMs: Int = DEFAULT_DOUBLE_TAP_TIMEOUT_MS,
    // Audio progress bar visibility for ReadAloud: null = on tap (with controls), false = never
    val showAudioProgressBar: Boolean? = null,
    // Whether to keep the screen awake while ReadAloud audio is playing
    val keepScreenOnDuringAudio: Boolean = true,
    // Whether book search hides matches past the furthest page read (spoiler protection)
    val hideSearchResultsAhead: Boolean = true,
    val ttsVoiceId: String? = null,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsEnabled: Boolean = false,
)

enum class ReaderThemeUiModel {
    LIGHT,
    DARK,
    SEPIA,
    SYSTEM,
}

enum class ReaderTextAlignUiModel {
    START,
    END,
    CENTER,
    JUSTIFY,
}

data class FontFamilyUiModel(
    val id: String,
    val cssValue: String,
    val displayName: String? = null,
    val isCustom: Boolean = false,
    val previewFilePath: String? = null,
) {
    companion object {
        // Default - uses publisher's font or system default
        val DEFAULT = FontFamilyUiModel("default", "default")

        // Generic CSS font families
        val SERIF = FontFamilyUiModel("serif", "serif")
        val SANS_SERIF = FontFamilyUiModel("sans_serif", "sans-serif")
        val CURSIVE = FontFamilyUiModel("cursive", "cursive")
        val FANTASY = FontFamilyUiModel("fantasy", "fantasy")
        val MONOSPACE = FontFamilyUiModel("monospace", "monospace")

        // Accessibility fonts embedded with Readium
        val ACCESSIBLE_DFA = FontFamilyUiModel("accessible_dfa", "AccessibleDfA")
        val IA_WRITER_DUOSPACE = FontFamilyUiModel("ia_writer_duospace", "IA Writer Duospace")
        val OPEN_DYSLEXIC = FontFamilyUiModel("open_dyslexic", "OpenDyslexic")

        val CORE = listOf(
            DEFAULT,
            SERIF,
            SANS_SERIF,
            CURSIVE,
            FANTASY,
            MONOSPACE,
            ACCESSIBLE_DFA,
            IA_WRITER_DUOSPACE,
            OPEN_DYSLEXIC,
        )

        val BUILT_IN: List<FontFamilyUiModel>
            get() = CORE + platformBundledReaderFonts()
    }
}

internal expect fun platformBundledReaderFonts(): List<FontFamilyUiModel>
