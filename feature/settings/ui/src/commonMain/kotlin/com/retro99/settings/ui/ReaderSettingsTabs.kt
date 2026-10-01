package com.retro99.settings.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberSwitchRow
import com.retro99.reader.domain.model.ChapterProgressDisplayMode
import com.retro99.reader.domain.model.HighlightStyle
import com.retro99.reader.domain.model.NavigationAction
import com.retro99.reader.domain.model.ProgressBarPosition
import com.retro99.reader.domain.model.ProgressIndicatorMode
import com.retro99.settings.ui.model.FontFamilyUiModel
import com.retro99.settings.ui.model.ReaderTextAlignUiModel
import com.retro99.settings.ui.model.ReaderThemeUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.reader_alignment
import resources.translations.reader_audio_progress_bar
import resources.translations.reader_boldness
import resources.translations.reader_double_tap_speed
import resources.translations.reader_eink_underline_hint
import resources.translations.reader_font
import resources.translations.reader_full_screen
import resources.translations.reader_full_screen_sub
import resources.translations.reader_keep_formatting
import resources.translations.reader_keep_formatting_sub
import resources.translations.reader_keep_screen_on
import resources.translations.reader_keep_screen_on_sub
import resources.translations.reader_line_spacing
import resources.translations.reader_location
import resources.translations.reader_mode_pages
import resources.translations.reader_never
import resources.translations.reader_on_tap
import resources.translations.reader_page_color
import resources.translations.reader_page_turns
import resources.translations.reader_page_turns_auto_note
import resources.translations.reader_page_turns_pages_note
import resources.translations.reader_page_turns_scroll_note
import resources.translations.reader_paragraph_spacing
import resources.translations.reader_position
import resources.translations.reader_progress_line
import resources.translations.reader_progress_line_nothing
import resources.translations.reader_read_aloud_note
import resources.translations.reader_show_book_progress
import resources.translations.reader_show_book_progress_sub
import resources.translations.reader_show_time
import resources.translations.reader_show_time_left
import resources.translations.reader_show_time_sub
import resources.translations.reader_side_margins
import resources.translations.reader_simplify_styles
import resources.translations.reader_simplify_styles_sub
import resources.translations.reader_style_both
import resources.translations.reader_swap_direction
import resources.translations.reader_swap_sides
import resources.translations.reader_tap_left
import resources.translations.reader_tap_middle
import resources.translations.reader_tap_right
import resources.translations.reader_tap_to_turn
import resources.translations.reader_tap_to_turn_sub
import resources.translations.reader_text_size
import resources.translations.reader_tts
import resources.translations.reader_tts_sub
import resources.translations.reader_vertical_margins
import resources.translations.reader_volume_mapping
import resources.translations.reader_volume_turn
import resources.translations.reader_volume_turn_sub
import resources.translations.reader_zone_menu
import resources.translations.reader_zone_next
import resources.translations.reader_zone_previous
import resources.translations.settings_chapter_progress
import resources.translations.settings_chapter_progress_none
import resources.translations.settings_chapter_progress_percentage
import resources.translations.settings_chapter_progress_relative
import resources.translations.settings_highlight_color
import resources.translations.settings_highlight_style
import resources.translations.settings_highlight_style_highlight
import resources.translations.settings_highlight_style_underline
import resources.translations.settings_progress_bar
import resources.translations.settings_progress_bar_always
import resources.translations.settings_progress_bar_position_bottom
import resources.translations.settings_progress_bar_position_top
import resources.translations.settings_progress_indicator_book
import resources.translations.settings_progress_indicator_chapter
import resources.translations.settings_scroll_mode_auto
import resources.translations.settings_scroll_mode_scroll
import resources.translations.settings_show_reading_time
import resources.translations.settings_show_reading_time_description
import resources.translations.settings_text_align_center
import resources.translations.settings_text_align_end
import resources.translations.settings_text_align_justify
import resources.translations.settings_text_align_start
import resources.translations.settings_tts_enabled
import resources.translations.settings_underline_color
import kotlin.math.roundToInt
import resources.translations.settings_update_linked_copies

private val TabPadding = 24.dp

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

/** Preset highlight colors for quick selection (ARGB). */
private val PresetHighlightColors = listOf(
    0x80FFEB3B.toInt(), // Yellow
    0x8081C784.toInt(), // Green
    0x8064B5F6.toInt(), // Blue
    0x80F48FB1.toInt(), // Pink
    0x80FFB74D.toInt(), // Orange
)

/** Grays used instead of the colors on e-ink screens. */
private val EinkHighlightColors = listOf(
    0xFFE0E0E0.toInt(),
    0xFFC0C0C0.toInt(),
    0xFFA0A0A0.toInt(),
    0xFF707070.toInt(),
    0xFF404040.toInt(),
)

private fun Float.roundToSingleDecimal(): Float = (this * 10).roundToInt() / 10f

private fun Double.roundToTwoDecimals(): Double = (this * 100).roundToInt() / 100.0

@Composable
private fun TabColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        content()
    }
}

@Composable
private fun Padded(content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = TabPadding)) { content() }
}

/** Switch rows already have 16dp inner padding, so they get 8dp to line up with the 24dp grid. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    EmberSwitchRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------------------------

@Composable
internal fun TextTab(
    viewState: SettingsViewState,
    intentDispatcher: IntentDispatcher<SettingsIntent>,
    onAddFont: () -> Unit,
) {
    val isEink = Ember.style.isEink
    val enabledThemes = if (isEink) setOf(ReaderThemeUiModel.LIGHT) else ReaderThemeUiModel.entries.toSet()

    TabColumn {
        Padded {
            ReaderSectionLabel(stringResource(StringRes.reader_page_color))
            PageColorTiles(
                selected = viewState.theme,
                enabledThemes = enabledThemes,
                onSelected = { intentDispatcher(SettingsIntent.OnThemeChanged(it)) },
            )
        }

        Padded {
            StepperRow(
                title = stringResource(StringRes.reader_text_size),
                value = "${(viewState.fontSize * 100).roundToInt()}%",
                hint = textSizeHint(viewState.fontSize),
                onDecrease = {
                    val value = (viewState.fontSize.toFloat() - FONT_SIZE_STEP).coerceAtLeast(MIN_FONT_SIZE)
                    intentDispatcher(SettingsIntent.OnFontSizeChanged(value.toDouble().roundToTwoDecimals()))
                },
                onIncrease = {
                    val value = (viewState.fontSize.toFloat() + FONT_SIZE_STEP).coerceAtMost(MAX_FONT_SIZE)
                    intentDispatcher(SettingsIntent.OnFontSizeChanged(value.toDouble().roundToTwoDecimals()))
                },
                decreaseContent = { StepperLetter(14) },
                increaseContent = { StepperLetter(22) },
            )
        }

        Column {
            Padded { ReaderSectionLabel(stringResource(StringRes.reader_font)) }
            FontChips(
                fonts = FontFamilyUiModel.BUILT_IN + viewState.customFonts,
                selected = viewState.fontFamily,
                onSelected = { intentDispatcher(SettingsIntent.OnFontFamilyChanged(it)) },
                onAddFont = onAddFont,
            )
        }

        Padded {
            StepperRow(
                title = stringResource(StringRes.reader_boldness),
                value = "${(viewState.fontWeight * 100).roundToInt()}%",
                hint = boldnessHint(viewState.fontWeight),
                onDecrease = {
                    val value = (viewState.fontWeight.toFloat() - FONT_WEIGHT_STEP).coerceAtLeast(MIN_FONT_WEIGHT)
                    intentDispatcher(SettingsIntent.OnFontWeightChanged(value.toDouble().roundToTwoDecimals()))
                },
                onIncrease = {
                    val value = (viewState.fontWeight.toFloat() + FONT_WEIGHT_STEP).coerceAtMost(MAX_FONT_WEIGHT)
                    intentDispatcher(SettingsIntent.OnFontWeightChanged(value.toDouble().roundToTwoDecimals()))
                },
            )
        }

        SwitchRow(
            title = stringResource(StringRes.reader_keep_formatting),
            subtitle = stringResource(StringRes.reader_keep_formatting_sub),
            checked = viewState.publisherStyles,
            onCheckedChange = { intentDispatcher(SettingsIntent.OnPublisherStylesChanged(it)) },
        )

        if (!viewState.publisherStyles) {
            Padded {
                StepperRow(
                    title = stringResource(StringRes.reader_line_spacing),
                    value = viewState.lineHeight.toString(),
                    hint = lineSpacingHint(viewState.lineHeight),
                    onDecrease = {
                        val value = (viewState.lineHeight - LINE_HEIGHT_STEP).coerceAtLeast(MIN_LINE_HEIGHT)
                        intentDispatcher(SettingsIntent.OnLineHeightChanged(value.roundToSingleDecimal()))
                    },
                    onIncrease = {
                        val value = (viewState.lineHeight + LINE_HEIGHT_STEP).coerceAtMost(MAX_LINE_HEIGHT)
                        intentDispatcher(SettingsIntent.OnLineHeightChanged(value.roundToSingleDecimal()))
                    },
                )
            }
            Padded {
                StepperRow(
                    title = stringResource(StringRes.reader_paragraph_spacing),
                    value = "${(viewState.paragraphSpacing * 100).roundToInt()}%",
                    hint = paragraphSpacingHint(viewState.paragraphSpacing),
                    onDecrease = {
                        val value = (viewState.paragraphSpacing - PARAGRAPH_SPACING_STEP)
                            .coerceAtLeast(MIN_PARAGRAPH_SPACING)
                        intentDispatcher(SettingsIntent.OnParagraphSpacingChanged(value.roundToTwoDecimals()))
                    },
                    onIncrease = {
                        val value = (viewState.paragraphSpacing + PARAGRAPH_SPACING_STEP)
                            .coerceAtMost(MAX_PARAGRAPH_SPACING)
                        intentDispatcher(SettingsIntent.OnParagraphSpacingChanged(value.roundToTwoDecimals()))
                    },
                )
            }
            Padded {
                ReaderSectionLabel(stringResource(StringRes.reader_alignment))
                SegmentedPill(
                    options = ReaderTextAlignUiModel.entries,
                    selected = viewState.textAlign,
                    label = { align -> alignLabel(align) },
                    onSelected = { intentDispatcher(SettingsIntent.OnTextAlignChanged(it)) },
                )
            }
        }

        SwitchRow(
            title = stringResource(StringRes.reader_simplify_styles),
            subtitle = stringResource(StringRes.reader_simplify_styles_sub),
            checked = viewState.textNormalization,
            onCheckedChange = { intentDispatcher(SettingsIntent.OnTextNormalizationChanged(it)) },
        )
    }
}

@Composable
private fun alignLabel(align: ReaderTextAlignUiModel): String = stringResource(
    when (align) {
        ReaderTextAlignUiModel.START -> StringRes.settings_text_align_start
        ReaderTextAlignUiModel.END -> StringRes.settings_text_align_end
        ReaderTextAlignUiModel.CENTER -> StringRes.settings_text_align_center
        ReaderTextAlignUiModel.JUSTIFY -> StringRes.settings_text_align_justify
    },
)

// ---------------------------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------------------------

@Composable
internal fun PageTab(
    viewState: SettingsViewState,
    intentDispatcher: IntentDispatcher<SettingsIntent>,
) {
    TabColumn {
        Padded {
            ReaderSectionLabel(stringResource(StringRes.reader_page_turns))
            SegmentedPill(
                options = listOf<Boolean?>(null, false, true),
                selected = viewState.scrollMode,
                label = { mode -> scrollModeLabel(mode) },
                onSelected = { intentDispatcher(SettingsIntent.OnScrollModeChanged(it)) },
            )
            Text(
                text = stringResource(
                    when (viewState.scrollMode) {
                        null -> StringRes.reader_page_turns_auto_note
                        false -> StringRes.reader_page_turns_pages_note
                        true -> StringRes.reader_page_turns_scroll_note
                    },
                ),
                style = Ember.type.meta,
                color = Ember.colors.ink2,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Padded {
            StepperRow(
                title = stringResource(StringRes.reader_side_margins),
                value = viewState.marginHorizontal.toString(),
                hint = marginHint(viewState.marginHorizontal),
                onDecrease = {
                    val value = (viewState.marginHorizontal - MARGIN_STEP).coerceAtLeast(MIN_HORIZONTAL_MARGIN)
                    intentDispatcher(SettingsIntent.OnMarginHorizontalChanged(value))
                },
                onIncrease = {
                    val value = (viewState.marginHorizontal + MARGIN_STEP).coerceAtMost(MAX_HORIZONTAL_MARGIN)
                    intentDispatcher(SettingsIntent.OnMarginHorizontalChanged(value))
                },
            )
        }

        Padded {
            StepperRow(
                title = stringResource(StringRes.reader_vertical_margins),
                value = viewState.marginVertical.toString(),
                hint = marginHint(viewState.marginVertical),
                onDecrease = {
                    val value = (viewState.marginVertical - MARGIN_STEP).coerceAtLeast(MIN_VERTICAL_MARGIN)
                    intentDispatcher(SettingsIntent.OnMarginVerticalChanged(value))
                },
                onIncrease = {
                    val value = (viewState.marginVertical + MARGIN_STEP).coerceAtMost(MAX_VERTICAL_MARGIN)
                    intentDispatcher(SettingsIntent.OnMarginVerticalChanged(value))
                },
            )
        }

        SwitchRow(
            title = stringResource(StringRes.reader_full_screen),
            subtitle = stringResource(StringRes.reader_full_screen_sub),
            checked = viewState.fullscreenMode,
            onCheckedChange = { intentDispatcher(SettingsIntent.OnFullscreenModeChanged(it)) },
        )
    }
}

@Composable
private fun scrollModeLabel(mode: Boolean?): String = stringResource(
    when (mode) {
        null -> StringRes.settings_scroll_mode_auto
        false -> StringRes.reader_mode_pages
        true -> StringRes.settings_scroll_mode_scroll
    },
)

// ---------------------------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------------------------

@Composable
internal fun ProgressTab(
    viewState: SettingsViewState,
    intentDispatcher: IntentDispatcher<SettingsIntent>,
) {
    TabColumn {
        Padded {
            ReaderSectionLabel(stringResource(StringRes.settings_progress_bar))
            SegmentedPill(
                options = listOf<Boolean?>(true, null, false),
                selected = viewState.showProgressBar,
                label = { mode ->
                    stringResource(
                        when (mode) {
                            true -> StringRes.settings_progress_bar_always
                            null -> StringRes.reader_on_tap
                            false -> StringRes.reader_never
                        },
                    )
                },
                onSelected = { intentDispatcher(SettingsIntent.OnShowProgressBarChanged(it)) },
            )
        }

        Padded {
            ReaderSectionLabel(stringResource(StringRes.reader_position))
            SegmentedPill(
                options = ProgressBarPosition.entries,
                selected = viewState.progressBarPosition,
                label = { position ->
                    stringResource(
                        when (position) {
                            ProgressBarPosition.TOP -> StringRes.settings_progress_bar_position_top
                            ProgressBarPosition.BOTTOM -> StringRes.settings_progress_bar_position_bottom
                        },
                    )
                },
                onSelected = { intentDispatcher(SettingsIntent.OnProgressBarPositionChanged(it)) },
            )
        }

        Padded {
            ReaderSectionLabel(stringResource(StringRes.settings_chapter_progress))
            SegmentedPill(
                options = ChapterProgressDisplayMode.entries,
                selected = viewState.chapterProgressDisplayMode,
                label = { mode ->
                    stringResource(
                        when (mode) {
                            ChapterProgressDisplayMode.NONE -> StringRes.settings_chapter_progress_none
                            ChapterProgressDisplayMode.PERCENTAGE -> StringRes.settings_chapter_progress_percentage
                            ChapterProgressDisplayMode.RELATIVE -> StringRes.settings_chapter_progress_relative
                            ChapterProgressDisplayMode.FIXED -> StringRes.reader_location
                        },
                    )
                },
                onSelected = { intentDispatcher(SettingsIntent.OnChapterProgressDisplayModeChanged(it)) },
            )
        }

        Padded {
            ReaderSectionLabel(stringResource(StringRes.reader_progress_line))
            SegmentedPill(
                options = ProgressIndicatorMode.entries,
                selected = viewState.progressIndicatorMode,
                label = { mode ->
                    stringResource(
                        when (mode) {
                            ProgressIndicatorMode.NONE -> StringRes.reader_progress_line_nothing
                            ProgressIndicatorMode.CHAPTER -> StringRes.settings_progress_indicator_chapter
                            ProgressIndicatorMode.BOOK -> StringRes.settings_progress_indicator_book
                        },
                    )
                },
                onSelected = { intentDispatcher(SettingsIntent.OnProgressIndicatorModeChanged(it)) },
            )
        }

        SwitchRow(
            title = stringResource(StringRes.reader_show_book_progress),
            subtitle = stringResource(StringRes.reader_show_book_progress_sub),
            checked = viewState.showTotalProgress,
            onCheckedChange = { intentDispatcher(SettingsIntent.OnShowTotalProgressChanged(it)) },
        )
        SwitchRow(
            title = stringResource(StringRes.reader_show_time),
            subtitle = stringResource(StringRes.reader_show_time_sub),
            checked = viewState.showCurrentTime,
            onCheckedChange = { intentDispatcher(SettingsIntent.OnShowCurrentTimeChanged(it)) },
        )
        SwitchRow(
            title = stringResource(StringRes.reader_show_time_left),
            subtitle = stringResource(StringRes.settings_show_reading_time_description),
            checked = viewState.showReadingTime,
            onCheckedChange = { intentDispatcher(SettingsIntent.OnShowReadingTimeChanged(it)) },
        )
        SwitchRow(
            title = stringResource(StringRes.settings_update_linked_copies),
            subtitle = null,
            checked = viewState.updateLinkedCopies,
            onCheckedChange = { enabled ->
                intentDispatcher(SettingsIntent.OnUpdateLinkedCopiesChanged(enabled))
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------------

@Composable
internal fun ControlsTab(
    viewState: SettingsViewState,
    intentDispatcher: IntentDispatcher<SettingsIntent>,
) {
    TabColumn {
        SwitchRow(
            title = stringResource(StringRes.reader_tap_to_turn),
            subtitle = stringResource(StringRes.reader_tap_to_turn_sub),
            checked = viewState.tapNavigationEnabled,
            onCheckedChange = { intentDispatcher(SettingsIntent.OnTapNavigationEnabledChanged(it)) },
        )

        Padded {
            TapZoneDiagram(
                enabled = viewState.tapNavigationEnabled,
                leftAction = viewState.leftTapAction,
                rightAction = viewState.rightTapAction,
            )
            SwapPill(
                enabled = viewState.tapNavigationEnabled,
                onClick = {
                    intentDispatcher(SettingsIntent.OnLeftTapActionChanged(viewState.rightTapAction))
                    intentDispatcher(SettingsIntent.OnRightTapActionChanged(viewState.leftTapAction))
                },
                modifier = Modifier.padding(top = 14.dp),
            )
        }

        Padded {
            StepperRow(
                title = stringResource(StringRes.reader_double_tap_speed),
                value = "${viewState.doubleTapTimeoutMs} ms",
                hint = doubleTapHint(viewState.doubleTapTimeoutMs),
                onDecrease = {
                    val value = (viewState.doubleTapTimeoutMs - DOUBLE_TAP_TIMEOUT_STEP)
                        .coerceAtLeast(MIN_DOUBLE_TAP_TIMEOUT)
                    intentDispatcher(SettingsIntent.OnDoubleTapTimeoutChanged(value))
                },
                onIncrease = {
                    val value = (viewState.doubleTapTimeoutMs + DOUBLE_TAP_TIMEOUT_STEP)
                        .coerceAtMost(MAX_DOUBLE_TAP_TIMEOUT)
                    intentDispatcher(SettingsIntent.OnDoubleTapTimeoutChanged(value))
                },
            )
        }

        SwitchRow(
            title = stringResource(StringRes.reader_volume_turn),
            subtitle = stringResource(StringRes.reader_volume_turn_sub),
            checked = viewState.volumeButtonsEnabled,
            onCheckedChange = { intentDispatcher(SettingsIntent.OnVolumeButtonsEnabledChanged(it)) },
        )

        if (viewState.volumeButtonsEnabled) {
            Padded {
                Text(
                    text = stringResource(
                        StringRes.reader_volume_mapping,
                        actionName(viewState.volumeUpAction),
                        actionName(viewState.volumeDownAction),
                    ),
                    style = Ember.type.meta,
                    color = Ember.colors.ink2,
                )
                SwapPill(
                    enabled = true,
                    label = stringResource(StringRes.reader_swap_direction),
                    onClick = {
                        intentDispatcher(SettingsIntent.OnVolumeUpActionChanged(viewState.volumeDownAction))
                        intentDispatcher(SettingsIntent.OnVolumeDownActionChanged(viewState.volumeUpAction))
                    },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun actionName(action: NavigationAction): String = stringResource(
    when (action) {
        NavigationAction.NEXT_PAGE -> StringRes.reader_zone_next
        NavigationAction.PREVIOUS_PAGE -> StringRes.reader_zone_previous
    },
)

@Composable
private fun SwapPill(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(StringRes.reader_swap_sides),
) {
    val colors = Ember.colors
    val style = Ember.style

    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .height(44.dp)
            .clip(CircleShape)
            .border(style.border, colors.chipBorder, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.SwapHoriz,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.accentText,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = Ember.type.label.copy(fontSize = 15.sp),
            color = colors.accentText,
        )
    }
}

/** A 150dp box split into left, middle and right tap zones, showing what each one does. */
@Composable
private fun TapZoneDiagram(
    enabled: Boolean,
    leftAction: NavigationAction,
    rightAction: NavigationAction,
) {
    val colors = Ember.colors
    val style = Ember.style
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(colors.surface)
            .border(if (style.isEink) 2.dp else style.border, colors.chipBorder, shape),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            TapZone(
                modifier = Modifier
                    .weight(0.36f)
                    .fillMaxSize()
                    .background(if (style.isEink) Color.Transparent else colors.bg),
                action = leftAction,
                caption = stringResource(StringRes.reader_tap_left),
            )
            TapZone(
                modifier = Modifier.weight(0.28f).fillMaxSize(),
                action = null,
                caption = stringResource(StringRes.reader_tap_middle),
            )
            TapZone(
                modifier = Modifier
                    .weight(0.36f)
                    .fillMaxSize()
                    .background(if (style.isEink) Color.Transparent else colors.bg),
                action = rightAction,
                caption = stringResource(StringRes.reader_tap_right),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dashedDividerAt(0.36f, colors.chipBorder)
                .dashedDividerAt(0.64f, colors.chipBorder),
        )
    }
}

@Composable
private fun TapZone(
    modifier: Modifier,
    action: NavigationAction?,
    caption: String,
) {
    val colors = Ember.colors

    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (action) {
            NavigationAction.PREVIOUS_PAGE -> Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = colors.ink,
            )

            NavigationAction.NEXT_PAGE -> Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = colors.ink,
            )

            null -> Unit
        }
        Text(
            text = if (action != null) actionName(action) else stringResource(StringRes.reader_zone_menu),
            style = Ember.type.meta.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = if (action != null) colors.ink else colors.ink2,
            textAlign = TextAlign.Center,
        )
        Text(
            text = caption,
            style = Ember.type.meta,
            color = colors.ink2,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Read aloud
// ---------------------------------------------------------------------------------------------

@Composable
internal fun ReadAloudTab(
    viewState: SettingsViewState,
    intentDispatcher: IntentDispatcher<SettingsIntent>,
) {
    val isEink = Ember.style.isEink
    val presets = if (isEink) EinkHighlightColors else PresetHighlightColors
    val style = viewState.highlightStyle
    val showsHighlight = style == HighlightStyle.HIGHLIGHT || style == HighlightStyle.HIGHLIGHT_UNDERLINE
    val showsUnderline = style == HighlightStyle.UNDERLINE || style == HighlightStyle.HIGHLIGHT_UNDERLINE

    TabColumn {
        if (isTtsSupported) {
            SwitchRow(
                title = stringResource(StringRes.reader_tts),
                subtitle = stringResource(StringRes.reader_tts_sub),
                checked = viewState.ttsEnabled,
                onCheckedChange = { intentDispatcher(SettingsIntent.OnTtsEnabledChanged(it)) },
            )
        }
        SwitchRow(
            title = stringResource(StringRes.reader_keep_screen_on),
            subtitle = stringResource(StringRes.reader_keep_screen_on_sub),
            checked = viewState.keepScreenOnDuringAudio,
            onCheckedChange = { intentDispatcher(SettingsIntent.OnKeepScreenOnDuringAudioChanged(it)) },
        )

        Padded {
            ReaderSectionLabel(stringResource(StringRes.reader_audio_progress_bar))
            SegmentedPill(
                options = listOf<Boolean?>(null, false),
                selected = viewState.showAudioProgressBar,
                label = { mode ->
                    stringResource(if (mode == null) StringRes.reader_on_tap else StringRes.reader_never)
                },
                onSelected = { intentDispatcher(SettingsIntent.OnShowAudioProgressBarChanged(it)) },
            )
        }

        Padded {
            ReaderSectionLabel(stringResource(StringRes.settings_highlight_style))
            SegmentedPill(
                options = HighlightStyle.entries,
                selected = style,
                label = { option ->
                    stringResource(
                        when (option) {
                            HighlightStyle.HIGHLIGHT -> StringRes.settings_highlight_style_highlight
                            HighlightStyle.UNDERLINE -> StringRes.settings_highlight_style_underline
                            HighlightStyle.HIGHLIGHT_UNDERLINE -> StringRes.reader_style_both
                        },
                    )
                },
                onSelected = { intentDispatcher(SettingsIntent.OnHighlightStyleChanged(it)) },
            )
        }

        if (showsHighlight) {
            Padded {
                val title = stringResource(StringRes.settings_highlight_color)
                ReaderSectionLabel(title)
                HighlightColorSwatches(
                    title = title,
                    presets = presets,
                    selectedColor = viewState.highlightColor,
                    onColorSelected = { intentDispatcher(SettingsIntent.OnHighlightColorChanged(it)) },
                )
            }
        }
        if (showsUnderline) {
            Padded {
                val title = stringResource(StringRes.settings_underline_color)
                ReaderSectionLabel(title)
                HighlightColorSwatches(
                    title = title,
                    presets = presets,
                    selectedColor = viewState.underlineColor,
                    onColorSelected = { intentDispatcher(SettingsIntent.OnUnderlineColorChanged(it)) },
                )
            }
        }

        Padded {
            if (isEink) {
                Text(
                    text = stringResource(StringRes.reader_eink_underline_hint),
                    style = Ember.type.meta,
                    color = Ember.colors.ink2,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                text = stringResource(StringRes.reader_read_aloud_note),
                style = Ember.type.meta,
                color = Ember.colors.ink2,
            )
        }
    }
}
