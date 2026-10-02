package com.retro99.reader.domain.model

/**
 * Represents reader settings/preferences.
 */
data class ReaderSettingsDomainModel(
    val fontSize: Double = 1.0,
    val fontFamily: String = "default",
    val fontWeight: Double = 1.0,
    val textNormalization: Boolean = false,
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val lineHeight: Float = 1.5f,
    val paragraphSpacing: Double = 0.0,
    val marginHorizontal: Int = 16,
    val marginVertical: Int = 16,
    val scrollMode: Boolean? = null,
    val textAlign: ReaderTextAlign = ReaderTextAlign.START,
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
    // Tap navigation settings
    // Whether tap navigation is enabled (tap left/right sides of screen to turn pages)
    val tapNavigationEnabled: Boolean = true,
    // Action for left tap (default: previous page)
    val leftTapAction: NavigationAction = NavigationAction.PREVIOUS_PAGE,
    // Action for right tap (default: next page)
    val rightTapAction: NavigationAction = NavigationAction.NEXT_PAGE,
    // Double-tap timeout in milliseconds for detecting double-taps on sentences (ReadAloud) and page navigation
    // Lower values require faster taps, higher values allow more time between taps but delay single-tap actions
    val doubleTapTimeoutMs: Int = DEFAULT_DOUBLE_TAP_TIMEOUT_MS,
    // Audio progress bar visibility for ReadAloud: null = on tap (with controls), false = never
    val showAudioProgressBar: Boolean? = null,
    // Whether to keep the screen awake while ReadAloud audio is playing
    val keepScreenOnDuringAudio: Boolean = true,
    // Whether book search hides matches past the furthest page read (spoiler protection)
    val hideSearchResultsAhead: Boolean = true,
) {
    companion object {
        /** Default reading speed in words per minute (average adult reading speed) */
        const val DEFAULT_READING_SPEED_WPM = 200

        /** Default double-tap timeout in milliseconds */
        const val DEFAULT_DOUBLE_TAP_TIMEOUT_MS = 300

        /** Default highlight color (Yellow with 50% alpha) */
        const val DEFAULT_HIGHLIGHT_COLOR = 0x80FFEB3B.toInt()

        /** Default underline color (Blue with 80% alpha) */
        const val DEFAULT_UNDERLINE_COLOR = 0xCC64B5F6.toInt()
    }
}

enum class ReaderTheme {
    LIGHT,
    DARK,
    SEPIA,
    SYSTEM,
}

enum class ReaderTextAlign {
    START,
    END,
    CENTER,
    JUSTIFY,
}

/**
 * Available highlight styles for ReadAloud text highlighting.
 */
enum class HighlightStyle {
    /** Background highlight only */
    HIGHLIGHT,

    /** Underline only (no background highlight) */
    UNDERLINE,

    /** Background highlight with underline */
    HIGHLIGHT_UNDERLINE,
}

/**
 * Display mode for chapter progress in the progress bar.
 */
enum class ChapterProgressDisplayMode {
    /** No chapter progress info shown */
    NONE,

    /** Show chapter progress as percentage (e.g., "50%") */
    PERCENTAGE,

    /** Show page numbers based on current screen/viewport (changes with font size, margins, etc.) */
    RELATIVE,

    /** Show page numbers based on publication's fixed position (EPUB position based on 1024-char blocks) */
    FIXED,
}

/**
 * Progress indicator mode for the reader.
 */
enum class ProgressIndicatorMode {
    /** No progress indicator shown */
    NONE,

    /** Show chapter progress indicator */
    CHAPTER,

    /** Show book (total) progress indicator */
    BOOK,
}

/**
 * Position of the progress bar in the reader.
 */
enum class ProgressBarPosition {
    /** Progress bar at the top, overlaid with toolbar when visible */
    TOP,

    /** Progress bar at the bottom (default) */
    BOTTOM,
}

/**
 * Actions that can be assigned to navigation controls (volume buttons, tap zones).
 * Used for:
 * - Volume buttons on Onyx Boox e-readers for page navigation
 * - Left/right screen tap zones for page navigation
 */
enum class NavigationAction {
    /** Navigate to the next page */
    NEXT_PAGE,

    /** Navigate to the previous page */
    PREVIOUS_PAGE,
}
