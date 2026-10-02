package com.retro99.settings.ui.model

import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.domain.model.ReaderTextAlign
import com.retro99.reader.domain.model.ReaderTheme

fun ReaderSettingsDomainModel.toUiModel(): ReaderSettingsUiModel = ReaderSettingsUiModel(
    fontSize = fontSize,
    fontFamily = fontFamily.toUiModel(),
    fontWeight = fontWeight,
    textNormalization = textNormalization,
    theme = theme.toUiModel(),
    lineHeight = lineHeight,
    paragraphSpacing = paragraphSpacing,
    marginHorizontal = marginHorizontal,
    marginVertical = marginVertical,
    scrollMode = scrollMode,
    textAlign = textAlign.toUiModel(),
    publisherStyles = publisherStyles,
    highlightColor = highlightColor,
    underlineColor = underlineColor,
    highlightStyle = highlightStyle,
    showProgressBar = showProgressBar,
    chapterProgressDisplayMode = chapterProgressDisplayMode,
    showTotalProgress = showTotalProgress,
    progressIndicatorMode = progressIndicatorMode,
    progressBarPosition = progressBarPosition,
    fullscreenMode = fullscreenMode,
    showCurrentTime = showCurrentTime,
    showReadingTime = showReadingTime,
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
    ttsVoiceId = ttsVoiceId,
    ttsRate = ttsRate,
    ttsPitch = ttsPitch,
    ttsEnabled = ttsEnabled,
)

fun ReaderSettingsUiModel.toDomainModel(): ReaderSettingsDomainModel = ReaderSettingsDomainModel(
    fontSize = fontSize,
    fontFamily = fontFamily.toDomainModel(),
    fontWeight = fontWeight,
    textNormalization = textNormalization,
    theme = theme.toDomainModel(),
    lineHeight = lineHeight,
    paragraphSpacing = paragraphSpacing,
    marginHorizontal = marginHorizontal,
    marginVertical = marginVertical,
    scrollMode = scrollMode,
    textAlign = textAlign.toDomainModel(),
    publisherStyles = publisherStyles,
    highlightColor = highlightColor,
    underlineColor = underlineColor,
    highlightStyle = highlightStyle,
    showProgressBar = showProgressBar,
    chapterProgressDisplayMode = chapterProgressDisplayMode,
    showTotalProgress = showTotalProgress,
    progressIndicatorMode = progressIndicatorMode,
    progressBarPosition = progressBarPosition,
    fullscreenMode = fullscreenMode,
    showCurrentTime = showCurrentTime,
    showReadingTime = showReadingTime,
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
    ttsVoiceId = ttsVoiceId,
    ttsRate = ttsRate,
    ttsPitch = ttsPitch,
    ttsEnabled = ttsEnabled,
)

fun ReaderTheme.toUiModel(): ReaderThemeUiModel = when (this) {
    ReaderTheme.LIGHT -> ReaderThemeUiModel.LIGHT
    ReaderTheme.DARK -> ReaderThemeUiModel.DARK
    ReaderTheme.SEPIA -> ReaderThemeUiModel.SEPIA
    ReaderTheme.SYSTEM -> ReaderThemeUiModel.SYSTEM
}

fun ReaderThemeUiModel.toDomainModel(): ReaderTheme = when (this) {
    ReaderThemeUiModel.LIGHT -> ReaderTheme.LIGHT
    ReaderThemeUiModel.DARK -> ReaderTheme.DARK
    ReaderThemeUiModel.SEPIA -> ReaderTheme.SEPIA
    ReaderThemeUiModel.SYSTEM -> ReaderTheme.SYSTEM
}

fun ReaderTextAlign.toUiModel(): ReaderTextAlignUiModel = when (this) {
    ReaderTextAlign.START -> ReaderTextAlignUiModel.START
    ReaderTextAlign.END -> ReaderTextAlignUiModel.END
    ReaderTextAlign.CENTER -> ReaderTextAlignUiModel.CENTER
    ReaderTextAlign.JUSTIFY -> ReaderTextAlignUiModel.JUSTIFY
}

fun ReaderTextAlignUiModel.toDomainModel(): ReaderTextAlign = when (this) {
    ReaderTextAlignUiModel.START -> ReaderTextAlign.START
    ReaderTextAlignUiModel.END -> ReaderTextAlign.END
    ReaderTextAlignUiModel.CENTER -> ReaderTextAlign.CENTER
    ReaderTextAlignUiModel.JUSTIFY -> ReaderTextAlign.JUSTIFY
}

fun String.toUiModel(): FontFamilyUiModel =
    toUiModel(customFonts = emptyList())

fun String.toUiModel(customFonts: List<CustomReaderFontDomainModel>): FontFamilyUiModel =
    FontFamilyUiModel.BUILT_IN.find { it.cssValue == this }
        ?: customFonts.firstOrNull { it.cssFamily == this }?.toUiModel()
        ?: FontFamilyUiModel(
            id = this,
            cssValue = this,
            displayName = this,
            isCustom = true,
        )

fun FontFamilyUiModel.toDomainModel(): String = cssValue

fun CustomReaderFontDomainModel.toUiModel(): FontFamilyUiModel =
    FontFamilyUiModel(
        id = id,
        cssValue = cssFamily,
        displayName = displayName,
        isCustom = true,
        previewFilePath = filePath,
    )
