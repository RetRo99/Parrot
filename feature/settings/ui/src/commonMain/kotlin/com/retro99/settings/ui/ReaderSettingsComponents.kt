package com.retro99.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberSectionLabel
import com.retro99.settings.ui.model.FontFamilyUiModel
import com.retro99.settings.ui.model.ReaderThemeUiModel
import com.retro99.settings.ui.model.toPreviewFontFamily
import com.retro99.settings.ui.model.toWeightedPreviewFontFamily
import com.retro99.reader.domain.model.HighlightStyle
import com.retro99.settings.ui.model.ReaderTextAlignUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_cancel
import resources.translations.general_ok
import resources.translations.reader_decrease
import resources.translations.reader_hint_bold
import resources.translations.reader_hint_default
import resources.translations.reader_hint_fast
import resources.translations.reader_hint_large
import resources.translations.reader_hint_light
import resources.translations.reader_hint_loose
import resources.translations.reader_hint_medium
import resources.translations.reader_hint_narrow
import resources.translations.reader_hint_none
import resources.translations.reader_hint_normal
import resources.translations.reader_hint_relaxed
import resources.translations.reader_hint_small
import resources.translations.reader_hint_tight
import resources.translations.reader_hint_wide
import resources.translations.reader_increase
import resources.translations.reader_value_with_hint
import resources.translations.settings_custom_color
import resources.translations.settings_font_family_accessible_dfa
import resources.translations.settings_font_family_add
import resources.translations.settings_font_family_cursive
import resources.translations.settings_font_family_fantasy
import resources.translations.settings_font_family_ia_writer_duospace
import resources.translations.settings_font_family_monospace
import resources.translations.settings_font_family_open_dyslexic
import resources.translations.settings_font_family_sans_serif
import resources.translations.settings_font_family_serif
import resources.translations.reader_font_book
import resources.translations.reader_theme_match_app
import resources.translations.settings_reader_preview_sample
import resources.translations.settings_selected
import kotlin.math.roundToInt
import resources.translations.settings_theme_dark
import resources.translations.settings_theme_light
import resources.translations.settings_theme_sepia

/** Tabs of the reader settings sheet. */
internal enum class ReaderSettingsTab {
    TEXT,
    PAGE,
    PROGRESS,
    CONTROLS,
    READ_ALOUD,
}

/** Label above a group of controls, with the sheet's 24dp side padding. */
@Composable
internal fun ReaderSectionLabel(text: String, modifier: Modifier = Modifier) {
    EmberSectionLabel(text = text, modifier = modifier.padding(bottom = 8.dp))
}

/** Pill of equal segments; the selected one is filled. */
@Composable
internal fun <T> SegmentedPill(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.bg)
            .border(style.border, colors.chipBorder, CircleShape)
            .padding(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.accent else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelected(option) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = Ember.type.meta.copy(
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = if (isSelected) colors.onAccent else colors.ink2,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Title with a value line on the left and two round − / + buttons on the right. */
@Composable
internal fun StepperRow(
    title: String,
    value: String,
    hint: String?,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    decreaseContent: @Composable () -> Unit = { StepperIcon(isPlus = false) },
    increaseContent: @Composable () -> Unit = { StepperIcon(isPlus = true) },
) {
    val colors = Ember.colors

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
            )
            Text(
                text = if (hint != null) {
                    stringResource(StringRes.reader_value_with_hint, value, hint)
                } else {
                    value
                },
                style = Ember.type.meta,
                color = colors.ink2,
            )
        }
        StepperButton(
            description = stringResource(StringRes.reader_decrease, title),
            onClick = onDecrease,
            content = decreaseContent,
        )
        Spacer(modifier = Modifier.width(12.dp))
        StepperButton(
            description = stringResource(StringRes.reader_increase, title),
            onClick = onIncrease,
            content = increaseContent,
        )
    }
}

@Composable
private fun StepperButton(
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = Ember.colors
    val style = Ember.style

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.chip)
            .border(style.border, colors.chipBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun StepperIcon(isPlus: Boolean) {
    Icon(
        imageVector = if (isPlus) Icons.Outlined.Add else Icons.Outlined.Remove,
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint = Ember.colors.ink,
    )
}

/** Serif "A" used on the text size buttons. */
@Composable
internal fun StepperLetter(fontSizeSp: Int) {
    Text(
        text = "A",
        style = Ember.type.cardTitle.copy(fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp + 4).sp),
        color = Ember.colors.ink,
    )
}

/** Thin divider between groups inside a tab. */
@Composable
internal fun ReaderDivider() {
    HorizontalDivider(thickness = Ember.style.border, color = Ember.colors.line)
}

// ---- Plain-words hints for stepper values ----

@Composable
internal fun textSizeHint(fraction: Double): String = stringResource(
    when {
        fraction < 0.95 -> StringRes.reader_hint_small
        fraction > 1.05 -> StringRes.reader_hint_large
        else -> StringRes.reader_hint_default
    },
)

@Composable
internal fun boldnessHint(fraction: Double): String = stringResource(
    when {
        fraction < 0.95 -> StringRes.reader_hint_light
        fraction > 1.05 -> StringRes.reader_hint_bold
        else -> StringRes.reader_hint_normal
    },
)

@Composable
internal fun marginHint(value: Int): String = stringResource(
    when {
        value <= 8 -> StringRes.reader_hint_narrow
        value <= 24 -> StringRes.reader_hint_medium
        else -> StringRes.reader_hint_wide
    },
)

@Composable
internal fun doubleTapHint(timeoutMs: Int): String = stringResource(
    when {
        timeoutMs <= 250 -> StringRes.reader_hint_fast
        timeoutMs >= 400 -> StringRes.reader_hint_relaxed
        else -> StringRes.reader_hint_normal
    },
)

@Composable
internal fun lineSpacingHint(value: Float): String = stringResource(
    when {
        value < 1.3f -> StringRes.reader_hint_tight
        value > 1.7f -> StringRes.reader_hint_loose
        else -> StringRes.reader_hint_normal
    },
)

@Composable
internal fun paragraphSpacingHint(value: Double): String = stringResource(
    if (value <= 0.0) StringRes.reader_hint_none else StringRes.reader_hint_normal,
)

// ---- Page color tiles ----

private val LightPageColor = Color(0xFFFFFFFF)
private val LightPageText = Color(0xFF221A13)
private val SepiaPageColor = Color(0xFFF1E4D3)
private val SepiaPageText = Color(0xFF5B4636)
private val DarkPageColor = Color(0xFF1F1A14)
private val DarkPageText = Color(0xFFE8DCCB)

/** Four tiles that preview the page colors. [enabledThemes] limits the choice in E-ink mode. */
@Composable
internal fun PageColorTiles(
    selected: ReaderThemeUiModel,
    enabledThemes: Set<ReaderThemeUiModel>,
    onSelected: (ReaderThemeUiModel) -> Unit,
) {
    val tiles = listOf(
        ReaderThemeUiModel.LIGHT,
        ReaderThemeUiModel.SEPIA,
        ReaderThemeUiModel.DARK,
        ReaderThemeUiModel.SYSTEM,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tiles.forEach { theme ->
            PageColorTile(
                theme = theme,
                selected = theme == selected,
                enabled = theme in enabledThemes,
                onClick = { onSelected(theme) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PageColorTile(
    theme: ReaderThemeUiModel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val shape = RoundedCornerShape(12.dp)
    val label = when (theme) {
        ReaderThemeUiModel.LIGHT -> stringResource(StringRes.settings_theme_light)
        ReaderThemeUiModel.SEPIA -> stringResource(StringRes.settings_theme_sepia)
        ReaderThemeUiModel.DARK -> stringResource(StringRes.settings_theme_dark)
        ReaderThemeUiModel.SYSTEM -> stringResource(StringRes.reader_theme_match_app)
    }

    Column(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(shape)
                .drawBehind { drawPageColorPreview(theme) }
                .border(
                    width = if (selected) 2.5.dp else style.border,
                    color = if (selected) colors.accent else colors.chipBorder,
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Aa",
                style = Ember.type.cardTitle.copy(fontSize = 18.sp),
                color = pageTextColor(theme),
            )
        }
        Text(
            text = label,
            style = Ember.type.meta.copy(
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (selected) colors.ink else colors.ink2,
            modifier = Modifier.padding(top = 6.dp),
            maxLines = 1,
        )
    }
}

private fun pageTextColor(theme: ReaderThemeUiModel): Color = when (theme) {
    ReaderThemeUiModel.LIGHT -> LightPageText
    ReaderThemeUiModel.SEPIA -> SepiaPageText
    ReaderThemeUiModel.DARK -> DarkPageText
    ReaderThemeUiModel.SYSTEM -> SepiaPageText
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPageColorPreview(
    theme: ReaderThemeUiModel,
) {
    when (theme) {
        ReaderThemeUiModel.LIGHT -> drawRect(LightPageColor)
        ReaderThemeUiModel.SEPIA -> drawRect(SepiaPageColor)
        ReaderThemeUiModel.DARK -> drawRect(DarkPageColor)
        ReaderThemeUiModel.SYSTEM -> {
            drawRect(LightPageColor)
            val dark = Path().apply {
                moveTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            clipPath(dark) { drawRect(DarkPageColor) }
        }
    }
}

// ---- Font chips ----

/** Horizontally scrolling font chips, each name in its own font, ending with an "add font" chip. */
@Composable
internal fun FontChips(
    fonts: List<FontFamilyUiModel>,
    selected: FontFamilyUiModel,
    onSelected: (FontFamilyUiModel) -> Unit,
    onAddFont: () -> Unit,
) {
    val colors = Ember.colors
    val style = Ember.style

    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(fonts, key = { font -> font.id }) { font ->
            val isSelected = selected.cssValue == font.cssValue
            val shape = CircleShape
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(if (isSelected) colors.accent else Color.Transparent)
                    .border(style.border, if (isSelected) colors.accent else colors.chipBorder, shape)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelected(font) })
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fontDisplayName(font),
                    style = Ember.type.meta.copy(
                        fontSize = 16.sp,
                        fontFamily = font.toPreviewFontFamily(),
                    ),
                    color = if (isSelected) colors.onAccent else colors.ink,
                    maxLines = 1,
                )
            }
        }
        item(key = "add_font") {
            val label = stringResource(StringRes.settings_font_family_add)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .drawBehind {
                        drawCircle(
                            color = colors.ink2,
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                            ),
                        )
                    }
                    .clickable(role = Role.Button, onClick = onAddFont),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = label,
                    modifier = Modifier.size(20.dp),
                    tint = colors.ink2,
                )
            }
        }
    }
}

@Composable
internal fun fontDisplayName(font: FontFamilyUiModel): String = when (font) {
    FontFamilyUiModel.DEFAULT -> stringResource(StringRes.reader_font_book)
    FontFamilyUiModel.SERIF -> stringResource(StringRes.settings_font_family_serif)
    FontFamilyUiModel.SANS_SERIF -> stringResource(StringRes.settings_font_family_sans_serif)
    FontFamilyUiModel.CURSIVE -> stringResource(StringRes.settings_font_family_cursive)
    FontFamilyUiModel.FANTASY -> stringResource(StringRes.settings_font_family_fantasy)
    FontFamilyUiModel.MONOSPACE -> stringResource(StringRes.settings_font_family_monospace)
    FontFamilyUiModel.ACCESSIBLE_DFA -> stringResource(StringRes.settings_font_family_accessible_dfa)
    FontFamilyUiModel.IA_WRITER_DUOSPACE -> stringResource(StringRes.settings_font_family_ia_writer_duospace)
    FontFamilyUiModel.OPEN_DYSLEXIC -> stringResource(StringRes.settings_font_family_open_dyslexic)
    else -> font.displayName ?: font.cssValue
}

// ---- Highlight color swatches ----

/** Five preset swatches plus a "+" that opens the custom color picker. */
@Composable
internal fun HighlightColorSwatches(
    title: String,
    presets: List<Int>,
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
) {
    val colors = Ember.colors
    var showPicker by remember { mutableStateOf(false) }
    val isCustom = selectedColor !in presets

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        presets.forEach { argb ->
            val isSelected = argb == selectedColor
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .then(if (isSelected) Modifier.border(3.dp, colors.ink, CircleShape) else Modifier)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onColorSelected(argb) }),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = stringResource(StringRes.settings_selected),
                        modifier = Modifier.size(20.dp),
                        tint = colors.ink,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .then(if (isCustom) Modifier.background(Color(selectedColor)) else Modifier)
                .then(
                    if (isCustom) {
                        Modifier.border(3.dp, colors.ink, CircleShape)
                    } else {
                        Modifier.drawBehind {
                            drawCircle(
                                color = colors.ink2,
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                                ),
                            )
                        }
                    },
                )
                .clickable(role = Role.Button) { showPicker = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(StringRes.settings_custom_color),
                modifier = Modifier.size(20.dp),
                tint = if (isCustom) colors.ink else colors.ink2,
            )
        }
    }

    if (showPicker) {
        ColorPickerDialog(
            title = title,
            initialColor = selectedColor,
            onColorSelected = { argb ->
                onColorSelected(argb)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun ColorPickerDialog(
    title: String,
    initialColor: Int,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Ember.colors
    val initialComposeColor = Color(initialColor)
    val controller = rememberColorPickerController()
    var selectedColor by remember { mutableStateOf(initialComposeColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(text = title, style = Ember.type.cardTitle, color = colors.ink)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(selectedColor)
                        .border(2.dp, colors.chipBorder, CircleShape),
                )
                Spacer(modifier = Modifier.height(16.dp))
                HsvColorPicker(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    controller = controller,
                    onColorChanged = { colorEnvelope ->
                        // Only update when the user interacts, not on the initial composition.
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
                    val argb = ((selectedColor.alpha * 255).toInt() shl 24) or
                        ((selectedColor.red * 255).toInt() shl 16) or
                        ((selectedColor.green * 255).toInt() shl 8) or
                        (selectedColor.blue * 255).toInt()
                    onColorSelected(argb)
                },
            ) {
                Text(stringResource(StringRes.general_ok), color = colors.accentText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(StringRes.general_cancel), color = colors.accentText)
            }
        },
    )
}

/** Scrolling row of tab labels with an accent underline under the selected one. */
@Composable
internal fun ReaderTabRow(
    tabs: List<Pair<ReaderSettingsTab, String>>,
    selected: ReaderSettingsTab,
    onSelected: (ReaderSettingsTab) -> Unit,
) {
    val colors = Ember.colors
    val style = Ember.style

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            tabs.forEach { (tab, label) ->
                val isSelected = tab == selected
                Column(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelected(tab) })
                        .padding(horizontal = 12.dp)
                        .width(IntrinsicSize.Max),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.heightIn(min = 41.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = Ember.type.meta.copy(
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            ),
                            color = if (isSelected) colors.ink else colors.ink2,
                            maxLines = 1,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .height(3.dp)
                            .fillMaxWidth()
                            .background(if (isSelected) colors.accent else Color.Transparent),
                    )
                }
            }
        }
        HorizontalDivider(thickness = style.border, color = colors.line)
    }
}

/** Draws a dashed vertical line, used between the tap zones. */
internal fun Modifier.dashedDividerAt(fraction: Float, color: Color): Modifier = drawBehind {
    val x = size.width * fraction
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
    )
}
