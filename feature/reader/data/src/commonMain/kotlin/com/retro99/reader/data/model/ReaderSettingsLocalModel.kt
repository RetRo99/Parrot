package com.retro99.reader.data.model

import com.retro99.reader.domain.model.ChapterProgressDisplayMode
import com.retro99.reader.domain.model.HighlightStyle
import com.retro99.reader.domain.model.NavigationAction
import com.retro99.reader.domain.model.ProgressBarPosition
import com.retro99.reader.domain.model.ProgressIndicatorMode
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.domain.model.ReaderTextAlign
import com.retro99.reader.domain.model.ReaderTheme
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReaderSettingsLocalModel(
    @SerialName("font_size")
    val fontSize: Double = 1.0,
    @SerialName("font_family")
    val fontFamily: String = "default",
    @SerialName("font_weight")
    val fontWeight: Double = 1.0,
    @SerialName("text_normalization")
    val textNormalization: Boolean = false,
    @SerialName("theme")
    val theme: String = "SYSTEM",
    @SerialName("line_height")
    val lineHeight: Float = 1.5f,
    @SerialName("paragraph_spacing")
    val paragraphSpacing: Double = 0.0,
    @SerialName("margin_horizontal")
    val marginHorizontal: Int = 16,
    @SerialName("margin_vertical")
    val marginVertical: Int = 16,
    @SerialName("scroll_mode")
    val scrollMode: Boolean? = null,
    @SerialName("text_align")
    val textAlign: String = "START",
    @SerialName("publisher_styles")
    val publisherStyles: Boolean = true,
    @SerialName("playback_speed")
    val playbackSpeed: Float = 1.0f,
    @SerialName("volume")
    val volume: Float = 1.0f,
    @SerialName("tts_voice_id")
    val ttsVoiceId: String? = null,
    @SerialName("tts_rate")
    val ttsRate: Float = 1.0f,
    @SerialName("tts_pitch")
    val ttsPitch: Float = 1.0f,
    @SerialName("tts_enabled")
    val ttsEnabled: Boolean = false,
    // Highlight color as ARGB Int value
    @SerialName("highlight_color_argb")
    val highlightColorArgb: Int = ReaderSettingsDomainModel.DEFAULT_HIGHLIGHT_COLOR,
    // Underline color as ARGB Int value
    @SerialName("underline_color_argb")
    val underlineColorArgb: Int = ReaderSettingsDomainModel.DEFAULT_UNDERLINE_COLOR,
    @SerialName("highlight_style")
    val highlightStyle: String = "HIGHLIGHT",
    // Progress bar visibility: true = always, null = on tap (with controls), false = never
    @SerialName("show_progress_bar")
    val showProgressBar: Boolean? = true,
    // Chapter progress display mode: NONE, PERCENTAGE, RELATIVE, or FIXED
    @SerialName("chapter_progress_display_mode")
    val chapterProgressDisplayMode: String = "RELATIVE",
    // Whether to show total book progress
    @SerialName("show_total_progress")
    val showTotalProgress: Boolean = true,
    // Progress indicator mode: NONE, CHAPTER, or BOOK
    @SerialName("progress_indicator_mode")
    val progressIndicatorMode: String = "CHAPTER",
    // Progress bar position: TOP or BOTTOM
    @SerialName("progress_bar_position")
    val progressBarPosition: String = "BOTTOM",
    // Whether to hide system bars (status bar, navigation bar) for immersive reading
    @SerialName("fullscreen_mode")
    val fullscreenMode: Boolean = false,
    // Whether to show current time in the progress bar
    @SerialName("show_current_time")
    val showCurrentTime: Boolean = true,
    // Whether to show estimated reading time for the current chapter
    @SerialName("show_reading_time")
    val showReadingTime: Boolean = true,
    // Reading speed in words per minute (used for reading time estimation)
    @SerialName("reading_speed_wpm")
    val readingSpeedWpm: Int = ReaderSettingsDomainModel.DEFAULT_READING_SPEED_WPM,
    // Volume button navigation settings (for Onyx Boox e-readers)
    @SerialName("volume_buttons_enabled")
    val volumeButtonsEnabled: Boolean = false,
    @SerialName("volume_up_action")
    val volumeUpAction: String = "NEXT_PAGE",
    @SerialName("volume_down_action")
    val volumeDownAction: String = "PREVIOUS_PAGE",
    // Whether tap navigation is enabled (tap left/right sides of screen to turn pages)
    @SerialName("tap_navigation_enabled")
    val tapNavigationEnabled: Boolean = true,
    // Action for left tap
    @SerialName("left_tap_action")
    val leftTapAction: String = "PREVIOUS_PAGE",
    // Action for right tap
    @SerialName("right_tap_action")
    val rightTapAction: String = "NEXT_PAGE",
    // Double-tap timeout in milliseconds for detecting double-taps
    @SerialName("double_tap_timeout_ms")
    val doubleTapTimeoutMs: Int = ReaderSettingsDomainModel.DEFAULT_DOUBLE_TAP_TIMEOUT_MS,
    // Audio progress bar visibility for ReadAloud: null = on tap (with controls), false = never
    @SerialName("show_audio_progress_bar")
    val showAudioProgressBar: Boolean? = null,
    // Whether to keep the screen awake while ReadAloud audio is playing
    @SerialName("keep_screen_on_during_audio")
    val keepScreenOnDuringAudio: Boolean = true,
)

fun ReaderSettingsLocalModel.toDomain(): ReaderSettingsDomainModel {
    return ReaderSettingsDomainModel(
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        textNormalization = textNormalization,
        theme = try {
            ReaderTheme.valueOf(theme)
        } catch (e: IllegalArgumentException) {
            ReaderTheme.SYSTEM
        },
        lineHeight = lineHeight,
        paragraphSpacing = paragraphSpacing,
        marginHorizontal = marginHorizontal,
        marginVertical = marginVertical,
        scrollMode = scrollMode,
        textAlign = try {
            ReaderTextAlign.valueOf(textAlign)
        } catch (e: IllegalArgumentException) {
            ReaderTextAlign.START
        },
        publisherStyles = publisherStyles,
        playbackSpeed = playbackSpeed,
        volume = volume,
        ttsVoiceId = ttsVoiceId,
        ttsRate = ttsRate,
        ttsPitch = ttsPitch,
        ttsEnabled = ttsEnabled,
        // Treat 0 (fully transparent black) as invalid and use default
        highlightColor = if (highlightColorArgb == 0) {
            ReaderSettingsDomainModel.DEFAULT_HIGHLIGHT_COLOR
        } else {
            highlightColorArgb
        },
        underlineColor = if (underlineColorArgb == 0) {
            ReaderSettingsDomainModel.DEFAULT_UNDERLINE_COLOR
        } else {
            underlineColorArgb
        },
        highlightStyle = try {
            HighlightStyle.valueOf(highlightStyle)
        } catch (e: IllegalArgumentException) {
            HighlightStyle.HIGHLIGHT
        },
        showProgressBar = showProgressBar,
        chapterProgressDisplayMode = try {
            ChapterProgressDisplayMode.valueOf(chapterProgressDisplayMode)
        } catch (e: IllegalArgumentException) {
            ChapterProgressDisplayMode.RELATIVE
        },
        showTotalProgress = showTotalProgress,
        progressIndicatorMode = try {
            ProgressIndicatorMode.valueOf(progressIndicatorMode)
        } catch (e: IllegalArgumentException) {
            ProgressIndicatorMode.CHAPTER
        },
        progressBarPosition = try {
            ProgressBarPosition.valueOf(progressBarPosition)
        } catch (e: IllegalArgumentException) {
            ProgressBarPosition.BOTTOM
        },
        fullscreenMode = fullscreenMode,
        showCurrentTime = showCurrentTime,
        showReadingTime = showReadingTime,
        readingSpeedWpm = readingSpeedWpm,
        volumeButtonsEnabled = volumeButtonsEnabled,
        volumeUpAction = try {
            NavigationAction.valueOf(volumeUpAction)
        } catch (e: IllegalArgumentException) {
            NavigationAction.NEXT_PAGE
        },
        volumeDownAction = try {
            NavigationAction.valueOf(volumeDownAction)
        } catch (e: IllegalArgumentException) {
            NavigationAction.PREVIOUS_PAGE
        },
        tapNavigationEnabled = tapNavigationEnabled,
        leftTapAction = try {
            NavigationAction.valueOf(leftTapAction)
        } catch (e: IllegalArgumentException) {
            NavigationAction.PREVIOUS_PAGE
        },
        rightTapAction = try {
            NavigationAction.valueOf(rightTapAction)
        } catch (e: IllegalArgumentException) {
            NavigationAction.NEXT_PAGE
        },
        doubleTapTimeoutMs = doubleTapTimeoutMs,
        showAudioProgressBar = showAudioProgressBar,
        keepScreenOnDuringAudio = keepScreenOnDuringAudio,
    )
}

fun ReaderSettingsDomainModel.toLocal(): ReaderSettingsLocalModel {
    return ReaderSettingsLocalModel(
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        textNormalization = textNormalization,
        theme = theme.name,
        lineHeight = lineHeight,
        paragraphSpacing = paragraphSpacing,
        marginHorizontal = marginHorizontal,
        marginVertical = marginVertical,
        scrollMode = scrollMode,
        textAlign = textAlign.name,
        publisherStyles = publisherStyles,
        playbackSpeed = playbackSpeed,
        volume = volume,
        ttsVoiceId = ttsVoiceId,
        ttsRate = ttsRate,
        ttsPitch = ttsPitch,
        ttsEnabled = ttsEnabled,
        highlightColorArgb = highlightColor,
        underlineColorArgb = underlineColor,
        highlightStyle = highlightStyle.name,
        showProgressBar = showProgressBar,
        chapterProgressDisplayMode = chapterProgressDisplayMode.name,
        showTotalProgress = showTotalProgress,
        progressIndicatorMode = progressIndicatorMode.name,
        progressBarPosition = progressBarPosition.name,
        fullscreenMode = fullscreenMode,
        showCurrentTime = showCurrentTime,
        showReadingTime = showReadingTime,
        readingSpeedWpm = readingSpeedWpm,
        volumeButtonsEnabled = volumeButtonsEnabled,
        volumeUpAction = volumeUpAction.name,
        volumeDownAction = volumeDownAction.name,
        tapNavigationEnabled = tapNavigationEnabled,
        leftTapAction = leftTapAction.name,
        rightTapAction = rightTapAction.name,
        doubleTapTimeoutMs = doubleTapTimeoutMs,
        showAudioProgressBar = showAudioProgressBar,
        keepScreenOnDuringAudio = keepScreenOnDuringAudio,
    )
}
