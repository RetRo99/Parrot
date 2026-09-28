package com.retro99.settings.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.platform.isEinkDisplay
import com.retro99.reader.domain.model.ChapterProgressDisplayMode
import com.retro99.reader.domain.model.HighlightStyle
import com.retro99.reader.domain.model.NavigationAction
import com.retro99.reader.domain.model.ProgressBarPosition
import com.retro99.reader.domain.model.ProgressIndicatorMode
import com.retro99.settings.ui.model.FontFamilyUiModel
import com.retro99.settings.ui.model.ReaderTextAlignUiModel
import com.retro99.settings.ui.model.ReaderThemeUiModel
import com.retro99.settings.ui.model.toPreviewFontFamily
import com.retro99.settings.ui.model.toWeightedPreviewFontFamily
import com.retro99.translations.StringRes
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import resources.translations.books_detail_show_less
import resources.translations.books_detail_show_more
import resources.translations.general_cancel
import resources.translations.general_close
import resources.translations.general_ok
import resources.translations.settings_audio_progress_bar
import resources.translations.settings_audio_progress_bar_description
import resources.translations.settings_custom_color
import resources.translations.settings_changed
import resources.translations.settings_retry
import resources.translations.settings_save_failed
import resources.translations.settings_chapter_progress
import resources.translations.settings_chapter_progress_fixed
import resources.translations.settings_chapter_progress_none
import resources.translations.settings_chapter_progress_percentage
import resources.translations.settings_chapter_progress_relative
import resources.translations.settings_double_tap_timeout
import resources.translations.settings_double_tap_timeout_description
import resources.translations.settings_font_family
import resources.translations.settings_font_family_accessible_dfa
import resources.translations.settings_font_family_add
import resources.translations.settings_font_family_cursive
import resources.translations.settings_font_family_custom
import resources.translations.settings_font_family_default
import resources.translations.settings_font_family_fantasy
import resources.translations.settings_font_family_ia_writer_duospace
import resources.translations.settings_font_family_monospace
import resources.translations.settings_font_family_open_dyslexic
import resources.translations.settings_font_family_sans_serif
import resources.translations.settings_font_family_serif
import resources.translations.settings_font_size
import resources.translations.settings_font_weight
import resources.translations.settings_fullscreen_mode
import resources.translations.settings_fullscreen_mode_description
import resources.translations.settings_highlight_color
import resources.translations.settings_highlight_style
import resources.translations.settings_highlight_style_highlight
import resources.translations.settings_highlight_style_highlight_underline
import resources.translations.settings_highlight_style_underline
import resources.translations.settings_keep_screen_on_during_audio
import resources.translations.settings_keep_screen_on_during_audio_description
import resources.translations.settings_left_tap_action
import resources.translations.settings_line_height
import resources.translations.settings_margin_horizontal
import resources.translations.settings_margin_vertical
import resources.translations.settings_navigation_action_next_page
import resources.translations.settings_navigation_action_previous_page
import resources.translations.settings_paragraph_spacing
import resources.translations.settings_progress_bar
import resources.translations.settings_progress_bar_always
import resources.translations.settings_progress_bar_never
import resources.translations.settings_progress_bar_on_tap
import resources.translations.settings_progress_bar_position
import resources.translations.settings_progress_bar_position_bottom
import resources.translations.settings_progress_bar_position_top
import resources.translations.settings_progress_indicator
import resources.translations.settings_progress_indicator_book
import resources.translations.settings_progress_indicator_chapter
import resources.translations.settings_progress_indicator_none
import resources.translations.settings_publisher_styles
import resources.translations.settings_publisher_styles_description
import resources.translations.settings_reader_preview
import resources.translations.settings_reader_preview_sample
import resources.translations.settings_right_tap_action
import resources.translations.settings_scroll_mode
import resources.translations.settings_scroll_mode_auto
import resources.translations.settings_scroll_mode_paginated
import resources.translations.settings_scroll_mode_scroll
import resources.translations.settings_section_core
import resources.translations.settings_section_core_description
import resources.translations.settings_section_layout
import resources.translations.settings_section_layout_description
import resources.translations.settings_section_navigation
import resources.translations.settings_section_navigation_description
import resources.translations.settings_section_progress
import resources.translations.settings_section_progress_description
import resources.translations.settings_section_readaloud
import resources.translations.settings_section_readaloud_description
import resources.translations.settings_section_typography
import resources.translations.settings_section_typography_description
import resources.translations.settings_selected
import resources.translations.settings_show_current_time
import resources.translations.settings_show_current_time_description
import resources.translations.settings_show_reading_time
import resources.translations.settings_show_reading_time_description
import resources.translations.settings_show_total_progress
import resources.translations.settings_tap_navigation_enabled
import resources.translations.settings_tap_navigation_enabled_description
import resources.translations.settings_text_align
import resources.translations.settings_text_align_center
import resources.translations.settings_text_align_end
import resources.translations.settings_text_align_justify
import resources.translations.settings_text_align_start
import resources.translations.settings_text_normalization
import resources.translations.settings_text_normalization_description
import resources.translations.settings_theme
import resources.translations.settings_theme_dark
import resources.translations.settings_theme_light
import resources.translations.settings_theme_sepia
import resources.translations.settings_theme_system
import resources.translations.reader_settings_title
import resources.translations.settings_tts_enabled
import resources.translations.settings_tts_enabled_description
import resources.translations.settings_underline_color
import resources.translations.settings_undo
import resources.translations.settings_volume_buttons_enabled
import resources.translations.settings_volume_buttons_enabled_description
import resources.translations.settings_volume_down_action
import resources.translations.settings_volume_up_action
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        SettingsScreenContent(
            viewState = viewState,
            intentDispatcher = intentDispatcher,
            onClose = onClose,
            modifier = modifier,
        )
    }
}

@Composable
private fun SettingsScreenContent(
    viewState: SettingsViewState,
    intentDispatcher: IntentDispatcher<SettingsIntent>,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val animationsEnabled = remember { !isEinkDisplay() }
    val undoMessage = stringResource(StringRes.settings_changed)
    val undoLabel = stringResource(StringRes.settings_undo)
    val saveFailureMessage = stringResource(StringRes.settings_save_failed)
    val retryLabel = stringResource(StringRes.settings_retry)
    val fontPickerLauncher = rememberFilePickerLauncher(
        type = PickerType.File(extensions = listOf("ttf", "otf", "woff", "woff2")),
        mode = PickerMode.Single,
    ) { file ->
        if (file == null) {
            intentDispatcher(SettingsIntent.OnCustomFontImportCancelled)
        } else {
            intentDispatcher(SettingsIntent.OnCustomFontSelected(file))
        }
    }

    LaunchedEffect(viewState.undoRequestId) {
        if (viewState.undoReaderSettings != null) {
            val result = snackbarHostState.showSnackbar(
                message = undoMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            when (result) {
                SnackbarResult.ActionPerformed ->
                    intentDispatcher(SettingsIntent.OnUndoSettingsChange)

                SnackbarResult.Dismissed ->
                    intentDispatcher(SettingsIntent.OnDismissSettingsUndo)
            }
        }
    }

    LaunchedEffect(viewState.settingSaveFailureRequestId) {
        if (viewState.settingSaveFailureRequestId != null) {
            val result = snackbarHostState.showSnackbar(
                message = saveFailureMessage,
                actionLabel = retryLabel,
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.ActionPerformed ->
                    intentDispatcher(SettingsIntent.OnRetrySettingsSave)

                SnackbarResult.Dismissed ->
                    intentDispatcher(SettingsIntent.OnDismissSettingsSaveFailure)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSheetHeader(onClose = onClose)

            Spacer(modifier = Modifier.height(4.dp))

            CoreReaderSettingsPanel(
                viewState = viewState,
                animationsEnabled = animationsEnabled,
                onThemeSelected = { intentDispatcher(SettingsIntent.OnThemeChanged(it)) },
                onFontSizeChanged = {
                    intentDispatcher(SettingsIntent.OnFontSizeChanged(it.toDouble()))
                },
                onFontFamilySelected = {
                    intentDispatcher(SettingsIntent.OnFontFamilyChanged(it))
                },
                onAddFont = { fontPickerLauncher.launch() },
                onFontsToggled = { intentDispatcher(SettingsIntent.OnFontsToggled) },
                onScrollModeSelected = { intentDispatcher(SettingsIntent.OnScrollModeChanged(it)) },
            )

            // Typography: all text shape controls live together.
            ExpandableSettingsSection(
                title = stringResource(StringRes.settings_section_typography),
                description = stringResource(StringRes.settings_section_typography_description),
                isExpanded = viewState.isSectionExpanded(SettingsSection.TYPOGRAPHY),
                onToggle = { intentDispatcher(SettingsIntent.OnSectionToggled(SettingsSection.TYPOGRAPHY)) },
                animationsEnabled = animationsEnabled,
            ) {
                StepperSetting(
                    label = stringResource(StringRes.settings_font_weight),
                    value = "${(viewState.fontWeight * 100).roundToInt()}%",
                    manualInputValue = "${(viewState.fontWeight * 100).roundToInt()}",
                    onDecrease = {
                        val newValue = (viewState.fontWeight.toFloat() - FONT_WEIGHT_STEP)
                            .coerceAtLeast(MIN_FONT_WEIGHT)
                            .normalizedFontWeight()
                        intentDispatcher(SettingsIntent.OnFontWeightChanged(newValue))
                    },
                    onIncrease = {
                        val newValue = (viewState.fontWeight.toFloat() + FONT_WEIGHT_STEP)
                            .coerceAtMost(MAX_FONT_WEIGHT)
                            .normalizedFontWeight()
                        intentDispatcher(SettingsIntent.OnFontWeightChanged(newValue))
                    },
                    onManualValueSubmit = { input ->
                        input.toFloatOrNull()?.let { percent ->
                            val newValue = (percent / 100f)
                                .coerceIn(MIN_FONT_WEIGHT, MAX_FONT_WEIGHT)
                                .normalizedFontWeight()
                            intentDispatcher(SettingsIntent.OnFontWeightChanged(newValue))
                            true
                        } ?: false
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                SettingsSwitch(
                    label = stringResource(StringRes.settings_text_normalization),
                    description = stringResource(StringRes.settings_text_normalization_description),
                    checked = viewState.textNormalization,
                    onCheckedChange = { intentDispatcher(SettingsIntent.OnTextNormalizationChanged(it)) },
                )

                Spacer(modifier = Modifier.height(16.dp))

                SettingsSwitch(
                    label = stringResource(StringRes.settings_publisher_styles),
                    description = stringResource(StringRes.settings_publisher_styles_description),
                    checked = viewState.publisherStyles,
                    onCheckedChange = { intentDispatcher(SettingsIntent.OnPublisherStylesChanged(it)) },
                )

                SettingsAnimatedVisibility(
                    visible = !viewState.publisherStyles,
                    animationsEnabled = animationsEnabled,
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))

                        StepperSetting(
                            label = stringResource(StringRes.settings_line_height),
                            value = viewState.lineHeight.toSingleDecimalString(),
                            manualInputValue = viewState.lineHeight.toSingleDecimalString(),
                            onDecrease = {
                                intentDispatcher(
                                    SettingsIntent.OnLineHeightChanged(
                                        (viewState.lineHeight - LINE_HEIGHT_STEP)
                                            .coerceAtLeast(MIN_LINE_HEIGHT)
                                            .roundToSingleDecimal(),
                                    ),
                                )
                            },
                            onIncrease = {
                                intentDispatcher(
                                    SettingsIntent.OnLineHeightChanged(
                                        (viewState.lineHeight + LINE_HEIGHT_STEP)
                                            .coerceAtMost(MAX_LINE_HEIGHT)
                                            .roundToSingleDecimal(),
                                    ),
                                )
                            },
                            onManualValueSubmit = { input ->
                                input.toFloatOrNull()?.let { lineHeight ->
                                    intentDispatcher(
                                        SettingsIntent.OnLineHeightChanged(
                                            lineHeight.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)
                                                .roundToSingleDecimal(),
                                        ),
                                    )
                                    true
                                } ?: false
                            },
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        StepperSetting(
                            label = stringResource(StringRes.settings_paragraph_spacing),
                            value = "${(viewState.paragraphSpacing * 100).roundToInt()}%",
                            manualInputValue = "${(viewState.paragraphSpacing * 100).roundToInt()}",
                            onDecrease = {
                                val newValue = (viewState.paragraphSpacing - PARAGRAPH_SPACING_STEP)
                                    .coerceAtLeast(MIN_PARAGRAPH_SPACING)
                                intentDispatcher(SettingsIntent.OnParagraphSpacingChanged(newValue))
                            },
                            onIncrease = {
                                val newValue = (viewState.paragraphSpacing + PARAGRAPH_SPACING_STEP)
                                    .coerceAtMost(MAX_PARAGRAPH_SPACING)
                                intentDispatcher(SettingsIntent.OnParagraphSpacingChanged(newValue))
                            },
                            onManualValueSubmit = { input ->
                                input.toFloatOrNull()?.let { percent ->
                                    val newValue = (percent / 100.0)
                                        .coerceIn(MIN_PARAGRAPH_SPACING, MAX_PARAGRAPH_SPACING)
                                    intentDispatcher(SettingsIntent.OnParagraphSpacingChanged(newValue))
                                    true
                                } ?: false
                            },
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        TextAlignSelector(
                            selectedAlign = viewState.textAlign,
                            onAlignSelected = {
                                intentDispatcher(SettingsIntent.OnTextAlignChanged(it))
                            },
                        )
                    }
                }
            }

            // Layout: page geometry and reading flow.
            ExpandableSettingsSection(
                title = stringResource(StringRes.settings_section_layout),
                description = stringResource(StringRes.settings_section_layout_description),
                isExpanded = viewState.isSectionExpanded(SettingsSection.LAYOUT),
                onToggle = { intentDispatcher(SettingsIntent.OnSectionToggled(SettingsSection.LAYOUT)) },
                animationsEnabled = animationsEnabled,
            ) {
                StepperSetting(
                    label = stringResource(StringRes.settings_margin_horizontal),
                    value = "${viewState.marginHorizontal}dp",
                    manualInputValue = viewState.marginHorizontal.toString(),
                    onDecrease = {
                        val newValue = (viewState.marginHorizontal - MARGIN_STEP)
                            .coerceAtLeast(MIN_HORIZONTAL_MARGIN)
                        intentDispatcher(SettingsIntent.OnMarginHorizontalChanged(newValue))
                    },
                    onIncrease = {
                        val newValue = (viewState.marginHorizontal + MARGIN_STEP)
                            .coerceAtMost(MAX_HORIZONTAL_MARGIN)
                        intentDispatcher(SettingsIntent.OnMarginHorizontalChanged(newValue))
                    },
                    onManualValueSubmit = { input ->
                        input.toIntOrNull()?.let { margin ->
                            intentDispatcher(
                                SettingsIntent.OnMarginHorizontalChanged(
                                    margin.coerceIn(MIN_HORIZONTAL_MARGIN, MAX_HORIZONTAL_MARGIN),
                                ),
                            )
                            true
                        } ?: false
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                StepperSetting(
                    label = stringResource(StringRes.settings_margin_vertical),
                    value = "${viewState.marginVertical}dp",
                    manualInputValue = viewState.marginVertical.toString(),
                    onDecrease = {
                        val newValue = (viewState.marginVertical - MARGIN_STEP)
                            .coerceAtLeast(MIN_VERTICAL_MARGIN)
                        intentDispatcher(SettingsIntent.OnMarginVerticalChanged(newValue))
                    },
                    onIncrease = {
                        val newValue = (viewState.marginVertical + MARGIN_STEP)
                            .coerceAtMost(MAX_VERTICAL_MARGIN)
                        intentDispatcher(SettingsIntent.OnMarginVerticalChanged(newValue))
                    },
                    onManualValueSubmit = { input ->
                        input.toIntOrNull()?.let { margin ->
                            intentDispatcher(
                                SettingsIntent.OnMarginVerticalChanged(
                                    margin.coerceIn(MIN_VERTICAL_MARGIN, MAX_VERTICAL_MARGIN),
                                ),
                            )
                            true
                        } ?: false
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                ScrollModeSelector(
                    selectedMode = viewState.scrollMode,
                    onModeSelected = { intentDispatcher(SettingsIntent.OnScrollModeChanged(it)) },
                )

                Spacer(modifier = Modifier.height(16.dp))

                FullscreenModeSwitch(
                    isEnabled = viewState.fullscreenMode,
                    onToggle = { intentDispatcher(SettingsIntent.OnFullscreenModeChanged(it)) },
                )
            }

            // Progress: all reading telemetry and progress bar controls.
            ExpandableSettingsSection(
                title = stringResource(StringRes.settings_section_progress),
                description = stringResource(StringRes.settings_section_progress_description),
                isExpanded = viewState.isSectionExpanded(SettingsSection.PROGRESS),
                onToggle = { intentDispatcher(SettingsIntent.OnSectionToggled(SettingsSection.PROGRESS)) },
                scrollState = scrollState,
                coroutineScope = coroutineScope,
                animationsEnabled = animationsEnabled,
            ) {
                ProgressBarModeSelector(
                    selectedMode = viewState.showProgressBar,
                    onModeSelected = { intentDispatcher(SettingsIntent.OnShowProgressBarChanged(it)) },
                )

                Spacer(modifier = Modifier.height(16.dp))

                ProgressBarPositionSelector(
                    selectedPosition = viewState.progressBarPosition,
                    onPositionSelected = {
                        intentDispatcher(SettingsIntent.OnProgressBarPositionChanged(it))
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                ChapterProgressDisplayModeSelector(
                    selectedMode = viewState.chapterProgressDisplayMode,
                    onModeSelected = {
                        intentDispatcher(SettingsIntent.OnChapterProgressDisplayModeChanged(it))
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                ShowTotalProgressSwitch(
                    isEnabled = viewState.showTotalProgress,
                    onToggle = { intentDispatcher(SettingsIntent.OnShowTotalProgressChanged(it)) },
                )

                Spacer(modifier = Modifier.height(16.dp))

                ProgressIndicatorModeSelector(
                    selectedMode = viewState.progressIndicatorMode,
                    onModeSelected = {
                        intentDispatcher(SettingsIntent.OnProgressIndicatorModeChanged(it))
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                ShowCurrentTimeSwitch(
                    isEnabled = viewState.showCurrentTime,
                    onToggle = { intentDispatcher(SettingsIntent.OnShowCurrentTimeChanged(it)) },
                )

                Spacer(modifier = Modifier.height(16.dp))

                ShowReadingTimeSwitch(
                    isEnabled = viewState.showReadingTime,
                    onToggle = { intentDispatcher(SettingsIntent.OnShowReadingTimeChanged(it)) },
                )
            }

            // Navigation: page-turn gestures and button behavior.
            ExpandableSettingsSection(
                title = stringResource(StringRes.settings_section_navigation),
                description = stringResource(StringRes.settings_section_navigation_description),
                isExpanded = viewState.isSectionExpanded(SettingsSection.NAVIGATION),
                onToggle = { intentDispatcher(SettingsIntent.OnSectionToggled(SettingsSection.NAVIGATION)) },
                scrollState = scrollState,
                coroutineScope = coroutineScope,
                animationsEnabled = animationsEnabled,
            ) {
                TapNavigationEnabledSwitch(
                    isEnabled = viewState.tapNavigationEnabled,
                    onToggle = { intentDispatcher(SettingsIntent.OnTapNavigationEnabledChanged(it)) },
                )

                if (viewState.tapNavigationEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))

                    TapActionSelector(
                        title = stringResource(StringRes.settings_left_tap_action),
                        selectedAction = viewState.leftTapAction,
                        onActionSelected = { intentDispatcher(SettingsIntent.OnLeftTapActionChanged(it)) },
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    TapActionSelector(
                        title = stringResource(StringRes.settings_right_tap_action),
                        selectedAction = viewState.rightTapAction,
                        onActionSelected = { intentDispatcher(SettingsIntent.OnRightTapActionChanged(it)) },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column {
                    StepperSetting(
                        label = stringResource(StringRes.settings_double_tap_timeout),
                        value = "${viewState.doubleTapTimeoutMs}ms",
                        manualInputValue = viewState.doubleTapTimeoutMs.toString(),
                        onDecrease = {
                            val newValue = (viewState.doubleTapTimeoutMs - DOUBLE_TAP_TIMEOUT_STEP)
                                .coerceAtLeast(MIN_DOUBLE_TAP_TIMEOUT)
                            intentDispatcher(SettingsIntent.OnDoubleTapTimeoutChanged(newValue))
                        },
                        onIncrease = {
                            val newValue = (viewState.doubleTapTimeoutMs + DOUBLE_TAP_TIMEOUT_STEP)
                                .coerceAtMost(MAX_DOUBLE_TAP_TIMEOUT)
                            intentDispatcher(SettingsIntent.OnDoubleTapTimeoutChanged(newValue))
                        },
                        onManualValueSubmit = { input ->
                            input.toIntOrNull()?.let { timeout ->
                                intentDispatcher(
                                    SettingsIntent.OnDoubleTapTimeoutChanged(
                                        timeout.coerceIn(MIN_DOUBLE_TAP_TIMEOUT, MAX_DOUBLE_TAP_TIMEOUT),
                                    ),
                                )
                                true
                            } ?: false
                        },
                    )
                    Text(
                        text = stringResource(StringRes.settings_double_tap_timeout_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                VolumeButtonsEnabledSwitch(
                    isEnabled = viewState.volumeButtonsEnabled,
                    onToggle = { intentDispatcher(SettingsIntent.OnVolumeButtonsEnabledChanged(it)) },
                )

                if (viewState.volumeButtonsEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))

                    VolumeButtonActionSelector(
                        title = stringResource(StringRes.settings_volume_up_action),
                        selectedAction = viewState.volumeUpAction,
                        onActionSelected = { intentDispatcher(SettingsIntent.OnVolumeUpActionChanged(it)) },
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    VolumeButtonActionSelector(
                        title = stringResource(StringRes.settings_volume_down_action),
                        selectedAction = viewState.volumeDownAction,
                        onActionSelected = { intentDispatcher(SettingsIntent.OnVolumeDownActionChanged(it)) },
                    )
                }
            }

            // ReadAloud: audio playback and sentence highlight behavior.
            ExpandableSettingsSection(
                title = stringResource(StringRes.settings_section_readaloud),
                description = stringResource(StringRes.settings_section_readaloud_description),
                isExpanded = viewState.isSectionExpanded(SettingsSection.READALOUD),
                onToggle = { intentDispatcher(SettingsIntent.OnSectionToggled(SettingsSection.READALOUD)) },
                scrollState = scrollState,
                coroutineScope = coroutineScope,
                animationsEnabled = animationsEnabled,
            ) {
                if (isTtsSupported) {
                    SettingsSwitch(
                        label = stringResource(StringRes.settings_tts_enabled),
                        description = stringResource(StringRes.settings_tts_enabled_description),
                        checked = viewState.ttsEnabled,
                        onCheckedChange = { enabled ->
                            intentDispatcher(SettingsIntent.OnTtsEnabledChanged(enabled))
                        },
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                SettingsSwitch(
                    label = stringResource(StringRes.settings_keep_screen_on_during_audio),
                    description = stringResource(StringRes.settings_keep_screen_on_during_audio_description),
                    checked = viewState.keepScreenOnDuringAudio,
                    onCheckedChange = {
                        intentDispatcher(SettingsIntent.OnKeepScreenOnDuringAudioChanged(it))
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                AudioProgressBarModeSelector(
                    selectedMode = viewState.showAudioProgressBar,
                    onModeSelected = { intentDispatcher(SettingsIntent.OnShowAudioProgressBarChanged(it)) },
                )

                Spacer(modifier = Modifier.height(16.dp))

                HighlightStyleSelector(
                    selectedStyle = viewState.highlightStyle,
                    onStyleSelected = { intentDispatcher(SettingsIntent.OnHighlightStyleChanged(it)) },
                )
                if (viewState.highlightStyle == HighlightStyle.HIGHLIGHT ||
                    viewState.highlightStyle == HighlightStyle.HIGHLIGHT_UNDERLINE
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    ColorSelector(
                        title = stringResource(StringRes.settings_highlight_color),
                        selectedColor = viewState.highlightColor,
                        onColorSelected = { intentDispatcher(SettingsIntent.OnHighlightColorChanged(it)) },
                    )
                }
                if (viewState.highlightStyle == HighlightStyle.UNDERLINE ||
                    viewState.highlightStyle == HighlightStyle.HIGHLIGHT_UNDERLINE
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    ColorSelector(
                        title = stringResource(StringRes.settings_underline_color),
                        selectedColor = viewState.underlineColor,
                        onColorSelected = { intentDispatcher(SettingsIntent.OnUnderlineColorChanged(it)) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(FLOATING_PREVIEW_SPACE))
        }

        FloatingReaderSettingsPreview(
            viewState = viewState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = FLOATING_PREVIEW_SPACE,
                ),
        )
    }
}

private val FLOATING_PREVIEW_HEIGHT = 132.dp
private val FLOATING_PREVIEW_SPACE = 172.dp

@Composable
private fun SettingsSheetHeader(
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(StringRes.reader_settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(StringRes.general_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Preset highlight colors for quick selection */
private val PresetHighlightColors = listOf(
    0x80FFEB3B.toInt(), // Yellow
    0x8081C784.toInt(), // Green
    0x8064B5F6.toInt(), // Blue
    0x80F48FB1.toInt(), // Pink
    0x80FFB74D.toInt(), // Orange
)

@Composable
private fun CoreReaderSettingsPanel(
    viewState: SettingsViewState,
    animationsEnabled: Boolean,
    onThemeSelected: (ReaderThemeUiModel) -> Unit,
    onFontSizeChanged: (Float) -> Unit,
    onFontFamilySelected: (FontFamilyUiModel) -> Unit,
    onAddFont: () -> Unit,
    onFontsToggled: () -> Unit,
    onScrollModeSelected: (Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(8.dp),
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(StringRes.settings_section_core),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(StringRes.settings_section_core_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            ThemeSelector(
                selectedTheme = viewState.theme,
                onThemeSelected = onThemeSelected,
            )

            Spacer(modifier = Modifier.height(16.dp))

            StepperSetting(
                label = stringResource(StringRes.settings_font_size),
                value = "${(viewState.fontSize * 100).roundToInt()}%",
                manualInputValue = "${(viewState.fontSize * 100).roundToInt()}",
                onDecrease = {
                    onFontSizeChanged(
                        (viewState.fontSize.toFloat() - FONT_SIZE_STEP).coerceAtLeast(MIN_FONT_SIZE),
                    )
                },
                onIncrease = {
                    onFontSizeChanged(
                        (viewState.fontSize.toFloat() + FONT_SIZE_STEP).coerceAtMost(MAX_FONT_SIZE),
                    )
                },
                onManualValueSubmit = { input ->
                    input.toFloatOrNull()?.let { percent ->
                        onFontSizeChanged((percent / 100f).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))
                        true
                    } ?: false
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            FontFamilySelector(
                selectedFontFamily = viewState.fontFamily,
                customFonts = viewState.customFonts,
                onFontFamilySelected = onFontFamilySelected,
                onAddFont = onAddFont,
                isExpanded = viewState.isFontsExpanded,
                onToggle = onFontsToggled,
                animationsEnabled = animationsEnabled,
            )

            Spacer(modifier = Modifier.height(16.dp))

            ScrollModeSelector(
                selectedMode = viewState.scrollMode,
                onModeSelected = onScrollModeSelected,
            )
        }
    }
}

private const val MIN_FONT_SIZE = 0.5f
private const val MAX_FONT_SIZE = 3.0f
private const val FONT_SIZE_STEP = 0.05f
private const val MIN_FONT_WEIGHT = 0.5f
private const val MAX_FONT_WEIGHT = 2.0f
private const val FONT_WEIGHT_STEP = 0.1f
private const val MIN_LINE_HEIGHT = 1.0f
private const val MAX_LINE_HEIGHT = 2.5f
private const val LINE_HEIGHT_STEP = 0.1f
private const val MIN_PARAGRAPH_SPACING = 0.0
private const val MAX_PARAGRAPH_SPACING = 2.0
private const val PARAGRAPH_SPACING_STEP = 0.05
private const val MIN_HORIZONTAL_MARGIN = 0
private const val MAX_HORIZONTAL_MARGIN = 48
private const val MIN_VERTICAL_MARGIN = 0
private const val MAX_VERTICAL_MARGIN = 64
private const val MARGIN_STEP = 4
private const val MIN_DOUBLE_TAP_TIMEOUT = 200
private const val MAX_DOUBLE_TAP_TIMEOUT = 800
private const val DOUBLE_TAP_TIMEOUT_STEP = 50

@Composable
private fun StepperSetting(
    label: String,
    value: String,
    manualInputValue: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onManualValueSubmit: (String) -> Boolean,
) {
    var showManualDialog by remember { mutableStateOf(false) }
    var inputValue by remember {
        mutableStateOf(manualInputValue.toTextFieldValueWithCursorAtEnd())
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperButton(
                text = "-",
                onClick = onDecrease,
            )
            Text(
                text = value,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .clickable {
                        inputValue = manualInputValue.toTextFieldValueWithCursorAtEnd()
                        showManualDialog = true
                    }
                    .padding(vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            StepperButton(
                text = "+",
                onClick = onIncrease,
            )
        }
    }

    if (showManualDialog) {
        LaunchedEffect(showManualDialog) {
            kotlinx.coroutines.delay(120)
            focusRequester.requestFocus()
            keyboardController?.show()
            kotlinx.coroutines.delay(250)
            keyboardController?.show()
        }

        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text(text = label) },
            text = {
                TextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    modifier = Modifier.focusRequester(focusRequester),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onManualValueSubmit(inputValue.text)) {
                            showManualDialog = false
                        }
                    },
                ) {
                    Text(stringResource(StringRes.general_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text(stringResource(StringRes.general_cancel))
                }
            },
        )
    }
}

private fun String.toTextFieldValueWithCursorAtEnd(): TextFieldValue =
    TextFieldValue(
        text = this,
        selection = TextRange(length),
    )

@Composable
private fun StepperButton(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ColorSelector(
    title: String,
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
) {
    var showColorPickerDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Preset color swatches
            PresetHighlightColors.forEach { colorArgb ->
                ColorSwatch(
                    colorArgb = colorArgb,
                    isSelected = colorArgb == selectedColor,
                    onClick = { onColorSelected(colorArgb) },
                )
            }
            // Custom color picker button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (selectedColor !in PresetHighlightColors) {
                            Color(selectedColor)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .border(
                        width = if (selectedColor !in PresetHighlightColors) 3.dp else 1.dp,
                        color = if (selectedColor !in PresetHighlightColors) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    )
                    .clickable { showColorPickerDialog = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(StringRes.settings_custom_color),
                    tint = if (selectedColor !in PresetHighlightColors) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (showColorPickerDialog) {
        ColorPickerDialog(
            title = title,
            initialColor = selectedColor,
            onColorSelected = { colorArgb ->
                onColorSelected(colorArgb)
                showColorPickerDialog = false
            },
            onDismiss = { showColorPickerDialog = false },
        )
    }
}

@Composable
private fun ColorSwatch(
    colorArgb: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(colorArgb))
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(StringRes.settings_selected),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ColorPickerDialog(
    title: String,
    initialColor: Int,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialComposeColor = Color(initialColor)
    val controller = rememberColorPickerController()
    var selectedColor by remember { mutableStateOf(initialComposeColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = title)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Color preview
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(selectedColor)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
                Spacer(modifier = Modifier.height(16.dp))

                // HSV Color Picker
                HsvColorPicker(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    controller = controller,
                    onColorChanged = { colorEnvelope ->
                        // Only update when user interacts, not on initial composition
                        if (colorEnvelope.fromUser) {
                            selectedColor = colorEnvelope.color
                        }
                    },
                    initialColor = initialComposeColor,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Convert Color to ARGB Int
                    val argb = ((selectedColor.alpha * 255).toInt() shl 24) or
                            ((selectedColor.red * 255).toInt() shl 16) or
                            ((selectedColor.green * 255).toInt() shl 8) or
                            (selectedColor.blue * 255).toInt()
                    onColorSelected(argb)
                },
            ) {
                Text(stringResource(StringRes.general_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(StringRes.general_cancel))
            }
        },
    )
}

@Composable
private fun HighlightStyleSelector(
    selectedStyle: HighlightStyle,
    onStyleSelected: (HighlightStyle) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(StringRes.settings_highlight_style),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HighlightStyle.entries.forEach { style ->
                HighlightStyleChip(
                    style = style,
                    isSelected = style == selectedStyle,
                    onClick = { onStyleSelected(style) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HighlightStyleChip(
    style: HighlightStyle,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (style) {
        HighlightStyle.HIGHLIGHT -> stringResource(StringRes.settings_highlight_style_highlight)
        HighlightStyle.HIGHLIGHT_UNDERLINE -> stringResource(
            StringRes.settings_highlight_style_highlight_underline,
        )

        HighlightStyle.UNDERLINE -> stringResource(StringRes.settings_highlight_style_underline)
    }

    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun FloatingReaderSettingsPreview(
    viewState: SettingsViewState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            .padding(12.dp),
    ) {
        ReaderSettingsPreview(
            viewState = viewState,
            previewHeight = FLOATING_PREVIEW_HEIGHT,
        )
    }
}

@Composable
private fun ReaderSettingsPreview(
    viewState: SettingsViewState,
    modifier: Modifier = Modifier,
    previewHeight: Dp = 150.dp,
) {
    val (backgroundColor, textColor) = when (viewState.theme) {
        ReaderThemeUiModel.LIGHT -> Color(0xFFFFFEFA) to Color(0xFF24211D)
        ReaderThemeUiModel.DARK -> Color(0xFF171717) to Color(0xFFECE7DE)
        ReaderThemeUiModel.SEPIA -> Color(0xFFF2E6CB) to Color(0xFF3B2E22)
        ReaderThemeUiModel.SYSTEM -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
    }
    val previewTextAlign = when (viewState.textAlign) {
        ReaderTextAlignUiModel.START -> TextAlign.Start
        ReaderTextAlignUiModel.END -> TextAlign.End
        ReaderTextAlignUiModel.CENTER -> TextAlign.Center
        ReaderTextAlignUiModel.JUSTIFY -> TextAlign.Justify
    }
    val previewFontSize = (16f * viewState.fontSize.toFloat()).coerceIn(11f, 26f).sp
    val paragraphGap = with(LocalDensity.current) {
        (previewFontSize.value * viewState.paragraphSpacing).sp.toDp()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(StringRes.settings_reader_preview),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(
                    horizontal = viewState.marginHorizontal.coerceIn(8, 48).dp,
                    vertical = viewState.marginVertical.coerceIn(8, 40).dp,
                ),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(paragraphGap),
            ) {
                previewParagraphs().forEach { paragraph ->
                    Text(
                        text = paragraph,
                        style = TextStyle(
                            color = textColor,
                            fontFamily = viewState.fontFamily.toWeightedPreviewFontFamily(
                                fontWeight = viewState.fontWeight,
                            ),
                            fontWeight = viewState.fontWeight.toPreviewFontWeight(),
                            fontSize = previewFontSize,
                            lineHeight = (previewFontSize.value * viewState.lineHeight).sp,
                            textAlign = previewTextAlign,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun previewParagraphs(): List<String> =
    stringResource(StringRes.settings_reader_preview_sample).split("\n\n")

private fun Double.toPreviewFontWeight(): FontWeight =
    FontWeight((400 * this).roundToInt().coerceIn(1, 1000))

private fun Float.normalizedFontWeight(): Double {
    val rounded = (this * 20).roundToInt() / 20.0
    return if (rounded == 1.0) 1.0 else rounded
}

private fun Float.roundToSingleDecimal(): Float =
    (this * 10).roundToInt() / 10f

private fun Float.toSingleDecimalString(): String =
    ((this * 10).roundToInt() / 10.0).toString()

@Composable
private fun <T> OutlinedChoiceGroup(
    title: String,
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    optionLabel: @Composable (T) -> String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedChoiceRow(
            options = options,
            selectedOption = selectedOption,
            onOptionSelected = onOptionSelected,
            optionLabel = optionLabel,
        )
    }
}

@Composable
private fun <T> OutlinedChoiceRow(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    optionLabel: @Composable (T) -> String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val selected = option == selectedOption
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = RoundedCornerShape(4.dp),
                    )
                    .clickable { onOptionSelected(option) }
                    .padding(horizontal = 6.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = optionLabel(option),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SettingsAnimatedVisibility(
    visible: Boolean,
    animationsEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (animationsEnabled) {
        AnimatedVisibility(
            visible = visible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            content()
        }
    } else if (visible) {
        content()
    }
}

@Composable
private fun ExpandableSettingsSection(
    title: String,
    description: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    coroutineScope: CoroutineScope? = null,
    animationsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val targetRotation = if (isExpanded) 180f else 0f
    val animatedRotation by animateFloatAsState(
        targetValue = targetRotation,
        label = "settings_section_arrow_rotation",
    )
    val rotationAngle = if (animationsEnabled) animatedRotation else targetRotation

    var sectionPosition by remember { mutableStateOf(0) }

    // Auto-scroll when section expands
    LaunchedEffect(isExpanded) {
        if (isExpanded && scrollState != null && coroutineScope != null) {
            coroutineScope.launch {
                if (animationsEnabled) {
                    kotlinx.coroutines.delay(100)
                }
                scrollState.animateScrollTo(sectionPosition)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(4.dp),
            )
            .onGloballyPositioned { coordinates ->
                sectionPosition = coordinates.positionInParent().y.toInt()
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) {
                    stringResource(StringRes.books_detail_show_less)
                } else {
                    stringResource(StringRes.books_detail_show_more)
                },
                modifier = Modifier.rotate(rotationAngle),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        SettingsAnimatedVisibility(
            visible = isExpanded,
            animationsEnabled = animationsEnabled,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(0.dp),
                    )
                    .padding(14.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ThemeSelector(
    selectedTheme: ReaderThemeUiModel,
    onThemeSelected: (ReaderThemeUiModel) -> Unit,
) {
    OutlinedChoiceGroup(
        title = stringResource(StringRes.settings_theme),
        options = ReaderThemeUiModel.entries,
        selectedOption = selectedTheme,
        onOptionSelected = onThemeSelected,
        optionLabel = { it.toDisplayString() },
    )
}

@Composable
private fun FontFamilySelector(
    selectedFontFamily: FontFamilyUiModel,
    customFonts: List<FontFamilyUiModel>,
    onFontFamilySelected: (FontFamilyUiModel) -> Unit,
    onAddFont: () -> Unit,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    animationsEnabled: Boolean,
) {
    val targetRotation = if (isExpanded) 180f else 0f
    val animatedRotation by animateFloatAsState(
        targetValue = targetRotation,
        label = "settings_font_arrow_rotation",
    )
    val rotationAngle = if (animationsEnabled) animatedRotation else targetRotation

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(8.dp),
                )
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(StringRes.settings_font_family),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = selectedFontFamily.toDisplayString(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = selectedFontFamily.toPreviewFontFamily(),
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) {
                    stringResource(StringRes.books_detail_show_less)
                } else {
                    stringResource(StringRes.books_detail_show_more)
                },
                modifier = Modifier.rotate(rotationAngle),
            )
        }

        SettingsAnimatedVisibility(
            visible = isExpanded,
            animationsEnabled = animationsEnabled,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                (FontFamilyUiModel.BUILT_IN + customFonts).forEach { fontFamily ->
                    FontFamilyOptionRow(
                        fontFamily = fontFamily,
                        isSelected = selectedFontFamily.cssValue == fontFamily.cssValue,
                        onClick = { onFontFamilySelected(fontFamily) },
                    )
                }
                TextButton(onClick = onAddFont) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(StringRes.settings_font_family_add),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = stringResource(StringRes.settings_font_family_add))
                }
            }
        }
    }
}

@Composable
private fun FontFamilyOptionRow(
    fontFamily: FontFamilyUiModel,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.outline
    }
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fontFamily.toDisplayString(),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = fontFamily.toPreviewFontFamily(),
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fontFamily.isCustom) {
                Text(
                    text = stringResource(StringRes.settings_font_family_custom),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isSelected) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(StringRes.settings_selected),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(StringRes.settings_selected),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun FontFamilyUiModel.toDisplayString(): String = when (this) {
    FontFamilyUiModel.DEFAULT -> stringResource(StringRes.settings_font_family_default)
    FontFamilyUiModel.SERIF -> stringResource(StringRes.settings_font_family_serif)
    FontFamilyUiModel.SANS_SERIF -> stringResource(StringRes.settings_font_family_sans_serif)
    FontFamilyUiModel.CURSIVE -> stringResource(StringRes.settings_font_family_cursive)
    FontFamilyUiModel.FANTASY -> stringResource(StringRes.settings_font_family_fantasy)
    FontFamilyUiModel.MONOSPACE -> stringResource(StringRes.settings_font_family_monospace)
    FontFamilyUiModel.ACCESSIBLE_DFA -> stringResource(StringRes.settings_font_family_accessible_dfa)
    FontFamilyUiModel.IA_WRITER_DUOSPACE -> stringResource(
        StringRes.settings_font_family_ia_writer_duospace,
    )

    FontFamilyUiModel.OPEN_DYSLEXIC -> stringResource(StringRes.settings_font_family_open_dyslexic)
    else -> displayName ?: cssValue
}

@Composable
private fun TextAlignSelector(
    selectedAlign: ReaderTextAlignUiModel,
    onAlignSelected: (ReaderTextAlignUiModel) -> Unit,
) {
    OutlinedChoiceGroup(
        title = stringResource(StringRes.settings_text_align),
        options = ReaderTextAlignUiModel.entries,
        selectedOption = selectedAlign,
        onOptionSelected = onAlignSelected,
        optionLabel = { it.toDisplayString() },
    )
}

/**
 * Scroll mode selector with three options:
 * - Auto (null): Let the publication/reader decide based on EPUB metadata
 * - Scroll (true): Force continuous scrolling mode
 * - Paginated (false): Force page-by-page reading
 */
@Composable
private fun ScrollModeSelector(
    selectedMode: Boolean?,
    onModeSelected: (Boolean?) -> Unit,
) {
    // Options: null = Auto, false = Paginated, true = Scroll
    val options = listOf<Boolean?>(null, false, true)

    OutlinedChoiceGroup(
        title = stringResource(StringRes.settings_scroll_mode),
        options = options,
        selectedOption = selectedMode,
        onOptionSelected = onModeSelected,
        optionLabel = { it.toScrollModeDisplayString() },
    )
}

@Composable
private fun Boolean?.toScrollModeDisplayString(): String = when (this) {
    null -> stringResource(StringRes.settings_scroll_mode_auto)
    false -> stringResource(StringRes.settings_scroll_mode_paginated)
    true -> stringResource(StringRes.settings_scroll_mode_scroll)
}

/**
 * Selector for progress bar visibility mode.
 * Options:
 * - Always (true): Progress bar is always visible
 * - On Tap (null): Progress bar shows/hides with controls when tapping
 * - Never (false): Progress bar is never visible
 */
@Composable
private fun ProgressBarModeSelector(
    selectedMode: Boolean?,
    onModeSelected: (Boolean?) -> Unit,
) {
    // Options: true = Always, null = On Tap, false = Never
    val options = listOf<Boolean?>(true, null, false)

    OutlinedChoiceGroup(
        title = stringResource(StringRes.settings_progress_bar),
        options = options,
        selectedOption = selectedMode,
        onOptionSelected = onModeSelected,
        optionLabel = { it.toProgressBarModeDisplayString() },
    )
}

@Composable
private fun Boolean?.toProgressBarModeDisplayString(): String = when (this) {
    true -> stringResource(StringRes.settings_progress_bar_always)
    null -> stringResource(StringRes.settings_progress_bar_on_tap)
    false -> stringResource(StringRes.settings_progress_bar_never)
}

/**
 * Selector for audio progress bar visibility (seek bar in ReadAloud mode).
 * Options:
 * - On Tap (null): Audio progress bar shows/hides with controls when tapping (default)
 * - Never (false): Audio progress bar is never visible
 */
@Composable
private fun AudioProgressBarModeSelector(
    selectedMode: Boolean?,
    onModeSelected: (Boolean?) -> Unit,
) {
    // Options: null = On Tap (default), false = Never
    val options = listOf<Boolean?>(null, false)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(StringRes.settings_audio_progress_bar),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(StringRes.settings_audio_progress_bar_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedChoiceRow(
            options = options,
            selectedOption = selectedMode,
            onOptionSelected = onModeSelected,
            optionLabel = { it.toProgressBarModeDisplayString() },
        )
    }
}

/**
 * Selector for progress bar position.
 * Options:
 * - Top: Progress bar at the top, overlaid with toolbar when visible
 * - Bottom: Progress bar at the bottom (default)
 */
@Composable
private fun ProgressBarPositionSelector(
    selectedPosition: ProgressBarPosition,
    onPositionSelected: (ProgressBarPosition) -> Unit,
) {
    OutlinedChoiceGroup(
        title = stringResource(StringRes.settings_progress_bar_position),
        options = ProgressBarPosition.entries,
        selectedOption = selectedPosition,
        onOptionSelected = onPositionSelected,
        optionLabel = { it.toDisplayString() },
    )
}

@Composable
private fun ProgressBarPosition.toDisplayString(): String = when (this) {
    ProgressBarPosition.TOP -> stringResource(StringRes.settings_progress_bar_position_top)
    ProgressBarPosition.BOTTOM -> stringResource(StringRes.settings_progress_bar_position_bottom)
}

/**
 * Selector for chapter progress display mode.
 * Options:
 * - None: No chapter progress info shown
 * - Percentage: Show chapter progress as percentage
 * - Pages (Relative): Show page numbers based on viewport
 * - Position (Fixed): Show EPUB position
 */
@Composable
private fun ChapterProgressDisplayModeSelector(
    selectedMode: ChapterProgressDisplayMode,
    onModeSelected: (ChapterProgressDisplayMode) -> Unit,
) {
    OutlinedChoiceGroup(
        title = stringResource(StringRes.settings_chapter_progress),
        options = ChapterProgressDisplayMode.entries,
        selectedOption = selectedMode,
        onOptionSelected = onModeSelected,
        optionLabel = { it.toDisplayString() },
    )
}

@Composable
private fun ChapterProgressDisplayMode.toDisplayString(): String = when (this) {
    ChapterProgressDisplayMode.NONE -> stringResource(StringRes.settings_chapter_progress_none)
    ChapterProgressDisplayMode.PERCENTAGE ->
        stringResource(StringRes.settings_chapter_progress_percentage)

    ChapterProgressDisplayMode.RELATIVE ->
        stringResource(StringRes.settings_chapter_progress_relative)

    ChapterProgressDisplayMode.FIXED -> stringResource(StringRes.settings_chapter_progress_fixed)
}

/**
 * Switch to toggle total progress display.
 */
@Composable
private fun ShowTotalProgressSwitch(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(StringRes.settings_show_total_progress),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
        )
    }
}

/**
 * Switch to toggle fullscreen mode (hide status bar while reading).
 */
@Composable
private fun FullscreenModeSwitch(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(StringRes.settings_fullscreen_mode),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
            )
        }
        Text(
            text = stringResource(StringRes.settings_fullscreen_mode_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Switch to toggle current time display in the progress bar.
 */
@Composable
private fun ShowCurrentTimeSwitch(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(StringRes.settings_show_current_time),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
            )
        }
        Text(
            text = stringResource(StringRes.settings_show_current_time_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Switch to toggle remaining reading time display in the progress bar.
 */
@Composable
private fun ShowReadingTimeSwitch(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(StringRes.settings_show_reading_time),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
            )
        }
        Text(
            text = stringResource(StringRes.settings_show_reading_time_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Switch to enable/disable tap navigation.
 * When enabled, tapping the left or right side of the screen turns pages.
 */
@Composable
private fun TapNavigationEnabledSwitch(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(StringRes.settings_tap_navigation_enabled),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
            )
        }
        Text(
            text = stringResource(StringRes.settings_tap_navigation_enabled_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Selector for tap action.
 * Options:
 * - Next Page: Navigate to the next page
 * - Previous Page: Navigate to the previous page
 */
@Composable
private fun TapActionSelector(
    title: String,
    selectedAction: NavigationAction,
    onActionSelected: (NavigationAction) -> Unit,
) {
    NavigationActionSelector(
        title = title,
        selectedAction = selectedAction,
        onActionSelected = onActionSelected,
    )
}

/**
 * Switch to enable/disable volume button navigation.
 * When enabled, volume buttons can be used to turn pages in ebooks.
 * Note: This only applies to ebooks, not read-aloud mode.
 */
@Composable
private fun VolumeButtonsEnabledSwitch(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(StringRes.settings_volume_buttons_enabled),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
            )
        }
        Text(
            text = stringResource(StringRes.settings_volume_buttons_enabled_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Selector for volume button action.
 * Options:
 * - Next Page: Navigate to the next page
 * - Previous Page: Navigate to the previous page
 */
@Composable
private fun VolumeButtonActionSelector(
    title: String,
    selectedAction: NavigationAction,
    onActionSelected: (NavigationAction) -> Unit,
) {
    NavigationActionSelector(
        title = title,
        selectedAction = selectedAction,
        onActionSelected = onActionSelected,
    )
}

@Composable
private fun NavigationActionSelector(
    title: String,
    selectedAction: NavigationAction,
    onActionSelected: (NavigationAction) -> Unit,
) {
    OutlinedChoiceGroup(
        title = title,
        options = NavigationAction.entries,
        selectedOption = selectedAction,
        onOptionSelected = onActionSelected,
        optionLabel = {
            when (it) {
                NavigationAction.NEXT_PAGE ->
                    stringResource(StringRes.settings_navigation_action_next_page)

                NavigationAction.PREVIOUS_PAGE ->
                    stringResource(StringRes.settings_navigation_action_previous_page)
            }
        },
    )
}

/**
 * Selector for progress indicator mode.
 * Options:
 * - None: No progress indicator shown
 * - Chapter: Show chapter progress indicator
 * - Book: Show book (total) progress indicator
 */
@Composable
private fun ProgressIndicatorModeSelector(
    selectedMode: ProgressIndicatorMode,
    onModeSelected: (ProgressIndicatorMode) -> Unit,
) {
    OutlinedChoiceGroup(
        title = stringResource(StringRes.settings_progress_indicator),
        options = ProgressIndicatorMode.entries,
        selectedOption = selectedMode,
        onOptionSelected = onModeSelected,
        optionLabel = { it.toDisplayString() },
    )
}

@Composable
private fun ProgressIndicatorMode.toDisplayString(): String = when (this) {
    ProgressIndicatorMode.NONE -> stringResource(StringRes.settings_progress_indicator_none)
    ProgressIndicatorMode.CHAPTER -> stringResource(StringRes.settings_progress_indicator_chapter)
    ProgressIndicatorMode.BOOK -> stringResource(StringRes.settings_progress_indicator_book)
}

@Composable
private fun SettingsSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun ReaderThemeUiModel.toDisplayString(): String = when (this) {
    ReaderThemeUiModel.LIGHT -> stringResource(StringRes.settings_theme_light)
    ReaderThemeUiModel.DARK -> stringResource(StringRes.settings_theme_dark)
    ReaderThemeUiModel.SEPIA -> stringResource(StringRes.settings_theme_sepia)
    ReaderThemeUiModel.SYSTEM -> stringResource(StringRes.settings_theme_system)
}

@Composable
private fun ReaderTextAlignUiModel.toDisplayString(): String = when (this) {
    ReaderTextAlignUiModel.START -> stringResource(StringRes.settings_text_align_start)
    ReaderTextAlignUiModel.END -> stringResource(StringRes.settings_text_align_end)
    ReaderTextAlignUiModel.CENTER -> stringResource(StringRes.settings_text_align_center)
    ReaderTextAlignUiModel.JUSTIFY -> stringResource(StringRes.settings_text_align_justify)
}
