package com.retro99.reader.ui.model

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.retro99.reader.domain.model.ChapterProgressDisplayMode
import com.retro99.reader.domain.model.HighlightStyle
import com.retro99.reader.domain.model.NavigationAction
import com.retro99.reader.domain.model.ProgressBarPosition
import com.retro99.reader.domain.model.ProgressIndicatorMode
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.domain.model.ReaderSettingsDomainModel.Companion.DEFAULT_DOUBLE_TAP_TIMEOUT_MS
import com.retro99.reader.domain.model.ReaderSettingsDomainModel.Companion.DEFAULT_HIGHLIGHT_COLOR
import com.retro99.reader.domain.model.ReaderSettingsDomainModel.Companion.DEFAULT_READING_SPEED_WPM
import com.retro99.reader.domain.model.ReaderSettingsDomainModel.Companion.DEFAULT_UNDERLINE_COLOR
import com.retro99.reader.domain.model.ReaderTextAlign
import com.retro99.reader.domain.model.ReaderTheme

data class ReaderSettingsUiModel(
    val fontSize: Double = 1.0,
    val fontFamily: String = "default",
    val fontWeight: Double = 1.0,
    val textNormalization: Boolean = false,
    val theme: ReaderThemeUi = ReaderThemeUi.SYSTEM,
    val lineHeight: Float = 1.5f,
    val paragraphSpacing: Double = 0.0,
    val marginHorizontal: Int = 16,
    val marginVertical: Int = 16,
    val scrollMode: Boolean? = null,
    val textAlign: ReaderTextAlignUi = ReaderTextAlignUi.START,
    // When true, uses publisher's CSS styles. When false, allows custom lineHeight, textAlign, etc.
    val publisherStyles: Boolean = true,
    // Media playback settings for ReadAloud books
    val playbackSpeed: Float = 1.0f,
    val volume: Float = 1.0f,
    val ttsVoiceId: String? = null,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsEnabled: Boolean = false,
    // Highlight color for ReadAloud text highlighting (ARGB Int value)
    val highlightColor: Int = DEFAULT_HIGHLIGHT_COLOR,
    // Underline color for ReadAloud text highlighting (ARGB Int value)
    val underlineColor: Int = DEFAULT_UNDERLINE_COLOR,
    // Highlight style for ReadAloud text highlighting
    val highlightStyle: ReadAloudHighlightStyle = ReadAloudHighlightStyle.HIGHLIGHT,
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
    // Whether to show estimated reading time for the current chapter
    val showReadingTime: Boolean = true,
    // Reading speed in words per minute (used for reading time estimation)
    val readingSpeedWpm: Int = DEFAULT_READING_SPEED_WPM,
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
    // Double-tap timeout in milliseconds
    val doubleTapTimeoutMs: Int = DEFAULT_DOUBLE_TAP_TIMEOUT_MS,
    // Audio progress bar visibility for ReadAloud: null = on tap (with controls), false = never
    val showAudioProgressBar: Boolean? = null,
    // Whether to keep the screen awake while ReadAloud audio is playing
    val keepScreenOnDuringAudio: Boolean = true,
    // Whether book search hides matches past the furthest page read (spoiler protection)
    val hideSearchResultsAhead: Boolean = true,
)

enum class ReaderThemeUi {
    LIGHT,
    DARK,
    SEPIA,
    SYSTEM,
}

enum class ReaderTextAlignUi {
    START,
    END,
    CENTER,
    JUSTIFY,
}

/**
 * Preset highlight colors for quick selection.
 * Each color has an associated ARGB value for rendering.
 */
object PresetHighlightColors {
    val YELLOW = 0x80FFEB3B.toInt()
    val GREEN = 0x8081C784.toInt()
    val BLUE = 0x8064B5F6.toInt()
    val PINK = 0x80F48FB1.toInt()
    val ORANGE = 0x80FFB74D.toInt()

    val all = listOf(YELLOW, GREEN, BLUE, PINK, ORANGE)
}

/**
 * Available highlight styles for ReadAloud text highlighting.
 */
enum class ReadAloudHighlightStyle {
    /** Background highlight only */
    HIGHLIGHT,

    /** Background highlight with underline */
    HIGHLIGHT_UNDERLINE,

    /** Underline only (no background highlight) */
    UNDERLINE,
}

fun ReaderSettingsDomainModel.toUiModel(): ReaderSettingsUiModel = ReaderSettingsUiModel(
    fontSize = fontSize,
    fontFamily = fontFamily,
    fontWeight = fontWeight,
    textNormalization = textNormalization,
    theme = theme.toUiTheme(),
    lineHeight = lineHeight,
    paragraphSpacing = paragraphSpacing,
    marginHorizontal = marginHorizontal,
    marginVertical = marginVertical,
    scrollMode = scrollMode,
    textAlign = textAlign.toUiTextAlign(),
    publisherStyles = publisherStyles,
    playbackSpeed = playbackSpeed,
    volume = volume,
    ttsVoiceId = ttsVoiceId,
    ttsRate = ttsRate,
    ttsPitch = ttsPitch,
    ttsEnabled = ttsEnabled,
    highlightColor = highlightColor,
    underlineColor = underlineColor,
    highlightStyle = highlightStyle.toUiHighlightStyle(),
    showProgressBar = showProgressBar,
    chapterProgressDisplayMode = chapterProgressDisplayMode,
    showTotalProgress = showTotalProgress,
    progressIndicatorMode = progressIndicatorMode,
    progressBarPosition = progressBarPosition,
    fullscreenMode = fullscreenMode,
    showCurrentTime = showCurrentTime,
    showReadingTime = showReadingTime,
    readingSpeedWpm = readingSpeedWpm,
    volumeButtonsEnabled = volumeButtonsEnabled,
    volumeUpAction = volumeUpAction,
    volumeDownAction = volumeDownAction,
    tapNavigationEnabled = tapNavigationEnabled,
    leftTapAction = leftTapAction,
    rightTapAction = rightTapAction,
    doubleTapTimeoutMs = doubleTapTimeoutMs,
    showAudioProgressBar = showAudioProgressBar,
    keepScreenOnDuringAudio = keepScreenOnDuringAudio,
    hideSearchResultsAhead = hideSearchResultsAhead,
)

fun ReaderSettingsUiModel.toDomainModel(): ReaderSettingsDomainModel = ReaderSettingsDomainModel(
    fontSize = fontSize,
    fontFamily = fontFamily,
    fontWeight = fontWeight,
    textNormalization = textNormalization,
    theme = theme.toDomainTheme(),
    lineHeight = lineHeight,
    paragraphSpacing = paragraphSpacing,
    marginHorizontal = marginHorizontal,
    marginVertical = marginVertical,
    scrollMode = scrollMode,
    textAlign = textAlign.toDomainTextAlign(),
    publisherStyles = publisherStyles,
    playbackSpeed = playbackSpeed,
    volume = volume,
    ttsVoiceId = ttsVoiceId,
    ttsRate = ttsRate,
    ttsPitch = ttsPitch,
    ttsEnabled = ttsEnabled,
    highlightColor = highlightColor,
    underlineColor = underlineColor,
    highlightStyle = highlightStyle.toDomainHighlightStyle(),
    showProgressBar = showProgressBar,
    chapterProgressDisplayMode = chapterProgressDisplayMode,
    showTotalProgress = showTotalProgress,
    progressIndicatorMode = progressIndicatorMode,
    progressBarPosition = progressBarPosition,
    fullscreenMode = fullscreenMode,
    showCurrentTime = showCurrentTime,
    showReadingTime = showReadingTime,
    readingSpeedWpm = readingSpeedWpm,
    volumeButtonsEnabled = volumeButtonsEnabled,
    volumeUpAction = volumeUpAction,
    volumeDownAction = volumeDownAction,
    tapNavigationEnabled = tapNavigationEnabled,
    leftTapAction = leftTapAction,
    rightTapAction = rightTapAction,
    doubleTapTimeoutMs = doubleTapTimeoutMs,
    showAudioProgressBar = showAudioProgressBar,
    keepScreenOnDuringAudio = keepScreenOnDuringAudio,
    hideSearchResultsAhead = hideSearchResultsAhead,
)

private fun ReaderTheme.toUiTheme(): ReaderThemeUi = when (this) {
    ReaderTheme.LIGHT -> ReaderThemeUi.LIGHT
    ReaderTheme.DARK -> ReaderThemeUi.DARK
    ReaderTheme.SEPIA -> ReaderThemeUi.SEPIA
    ReaderTheme.SYSTEM -> ReaderThemeUi.SYSTEM
}

private fun ReaderThemeUi.toDomainTheme(): ReaderTheme = when (this) {
    ReaderThemeUi.LIGHT -> ReaderTheme.LIGHT
    ReaderThemeUi.DARK -> ReaderTheme.DARK
    ReaderThemeUi.SEPIA -> ReaderTheme.SEPIA
    ReaderThemeUi.SYSTEM -> ReaderTheme.SYSTEM
}

private fun ReaderTextAlign.toUiTextAlign(): ReaderTextAlignUi = when (this) {
    ReaderTextAlign.START -> ReaderTextAlignUi.START
    ReaderTextAlign.END -> ReaderTextAlignUi.END
    ReaderTextAlign.CENTER -> ReaderTextAlignUi.CENTER
    ReaderTextAlign.JUSTIFY -> ReaderTextAlignUi.JUSTIFY
}

private fun ReaderTextAlignUi.toDomainTextAlign(): ReaderTextAlign = when (this) {
    ReaderTextAlignUi.START -> ReaderTextAlign.START
    ReaderTextAlignUi.END -> ReaderTextAlign.END
    ReaderTextAlignUi.CENTER -> ReaderTextAlign.CENTER
    ReaderTextAlignUi.JUSTIFY -> ReaderTextAlign.JUSTIFY
}

private fun HighlightStyle.toUiHighlightStyle(): ReadAloudHighlightStyle = when (this) {
    HighlightStyle.HIGHLIGHT -> ReadAloudHighlightStyle.HIGHLIGHT
    HighlightStyle.HIGHLIGHT_UNDERLINE -> ReadAloudHighlightStyle.HIGHLIGHT_UNDERLINE
    HighlightStyle.UNDERLINE -> ReadAloudHighlightStyle.UNDERLINE
}

private fun ReadAloudHighlightStyle.toDomainHighlightStyle(): HighlightStyle = when (this) {
    ReadAloudHighlightStyle.HIGHLIGHT -> HighlightStyle.HIGHLIGHT
    ReadAloudHighlightStyle.HIGHLIGHT_UNDERLINE -> HighlightStyle.HIGHLIGHT_UNDERLINE
    ReadAloudHighlightStyle.UNDERLINE -> HighlightStyle.UNDERLINE
}

// Theme background colors matching Readium CSS
// https://github.com/readium/readium-css/blob/master/css/src/modules/ReadiumCSS-day_mode.css
private val LightBackgroundColor = Color(0xFFFFFFFF)

// https://github.com/readium/readium-css/blob/master/css/src/modules/ReadiumCSS-night_mode.css
private val DarkBackgroundColor = Color(0xFF000000)

// https://github.com/readium/readium-css/blob/master/css/src/modules/ReadiumCSS-sepia_mode.css
private val SepiaBackgroundColor = Color(0xFFFAF4E8)

/**
 * Returns the background color for the reader based on the current theme.
 * For SYSTEM theme, it uses the system's dark/light mode setting.
 */
@Composable
fun ReaderThemeUi.backgroundColor(): Color {
    val isSystemDark = isSystemInDarkTheme()
    return when (this) {
        ReaderThemeUi.LIGHT -> LightBackgroundColor
        ReaderThemeUi.DARK -> DarkBackgroundColor
        ReaderThemeUi.SEPIA -> SepiaBackgroundColor
        ReaderThemeUi.SYSTEM -> if (isSystemDark) DarkBackgroundColor else LightBackgroundColor
    }
}
