package com.retro99.base.ui.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Visual mode of the app. [Eink] is a high-contrast, motion-free light theme. */
enum class EmberMode { Night, Day, Eink }

/** Ember color tokens. Screens reference these by name and never use hex values. */
@Immutable
data class EmberColors(
    val bg: Color,
    val surface: Color,
    val nav: Color,
    val ink: Color,
    val ink2: Color,
    val line: Color,
    val accent: Color,
    val accentText: Color,
    val onAccent: Color,
    val navActive: Color,
    val navActiveContent: Color,
    val track: Color,
    val chip: Color,
    val chipBorder: Color,
    /** [Color.Transparent] means the cover has no border. */
    val coverBorder: Color,
    val error: Color,
    val onError: Color,
    /** Text and check color on a selected chip, segment or radio row. */
    val chipSelectedText: Color,
    /** Softer accent for secondary bars next to a highlighted one. */
    val mutedAccent: Color,
    /** Destructive actions such as clearing data. */
    val destructive: Color,
    /** Positive status such as a connected server. */
    val success: Color,
    /** Fill behind inline error messages. */
    val errorContainer: Color,
)

/** Per-mode style values that are not colors. */
@Immutable
data class EmberStyle(
    val titleWeight: FontWeight,
    val italicWeight: FontWeight,
    /** Dividers and chip outlines. */
    val border: Dp,
    /** Progress bar height for hero surfaces. */
    val progressHeight: Dp,
    /** Progress bar height for list rows. */
    val progressHeightSmall: Dp,
    val progressOutlined: Boolean,
    /** [0.dp] means no shadow. */
    val coverElevation: Dp,
    /** False on e-ink: no transitions and no ripples. */
    val animations: Boolean,
) {
    val isEink: Boolean get() = progressOutlined
}

val EmberNightColors = EmberColors(
    bg = Color(0xFF16110D),
    surface = Color(0xFF211A14),
    nav = Color(0xFF120E0A),
    ink = Color(0xFFF2E8DA),
    ink2 = Color(0xFFB3A594),
    line = Color(0xFF33291F),
    accent = Color(0xFFD98A4E),
    accentText = Color(0xFFE59A5F),
    onAccent = Color(0xFF1C0F05),
    navActive = Color(0xFF3A2615),
    navActiveContent = Color(0xFFE59A5F),
    track = Color(0xFF3A2E23),
    chip = Color(0xFF211A14),
    chipBorder = Color(0xFF3A2E23),
    coverBorder = Color(0xFF4A3B2C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    chipSelectedText = Color(0xFFF2C39A),
    mutedAccent = Color(0xFF7A5A40),
    destructive = Color(0xFFF08A7A),
    success = Color(0xFF9CC58A),
    errorContainer = Color(0xFF3D1C18),
)

val EmberDayColors = EmberColors(
    bg = Color(0xFFF7F1E8),
    surface = Color(0xFFFFFFFF),
    nav = Color(0xFFF1E8DB),
    ink = Color(0xFF221A13),
    ink2 = Color(0xFF5E5145),
    line = Color(0xFFE6DBCB),
    accent = Color(0xFFA9561F),
    accentText = Color(0xFF9A4D1A),
    onAccent = Color(0xFFFFFFFF),
    navActive = Color(0xFFF3D9C3),
    navActiveContent = Color(0xFF221A13),
    track = Color(0xFFEADFCF),
    chip = Color(0xFFFFFFFF),
    chipBorder = Color(0xFFE6DBCB),
    coverBorder = Color.Transparent,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    chipSelectedText = Color(0xFF9A4D1A),
    mutedAccent = Color(0xFFD9B597),
    destructive = Color(0xFFA8321F),
    success = Color(0xFF3E6B2E),
    errorContainer = Color(0xFFFBE6E3),
)

val EmberEinkColors = EmberColors(
    bg = Color.White,
    surface = Color.White,
    nav = Color.White,
    ink = Color.Black,
    ink2 = Color(0xFF222222),
    line = Color.Black,
    accent = Color.Black,
    accentText = Color.Black,
    onAccent = Color.White,
    navActive = Color.Black,
    navActiveContent = Color.White,
    track = Color.White,
    chip = Color.White,
    chipBorder = Color.Black,
    coverBorder = Color.Black,
    error = Color.Black,
    onError = Color.White,
    chipSelectedText = Color.White,
    mutedAccent = Color.White,
    destructive = Color.Black,
    success = Color.Black,
    errorContainer = Color.White,
)

val EmberNightStyle = EmberStyle(
    titleWeight = FontWeight.Medium,
    italicWeight = FontWeight.Normal,
    border = 1.dp,
    progressHeight = 4.dp,
    progressHeightSmall = 3.dp,
    progressOutlined = false,
    coverElevation = 12.dp,
    animations = true,
)

val EmberDayStyle = EmberNightStyle.copy(
    titleWeight = FontWeight.SemiBold,
    coverElevation = 8.dp,
)

val EmberEinkStyle = EmberStyle(
    titleWeight = FontWeight.Bold,
    italicWeight = FontWeight.Medium,
    border = 1.5.dp,
    progressHeight = 10.dp,
    progressHeightSmall = 8.dp,
    progressOutlined = true,
    coverElevation = 0.dp,
    animations = false,
)

fun EmberMode.colors(): EmberColors = when (this) {
    EmberMode.Night -> EmberNightColors
    EmberMode.Day -> EmberDayColors
    EmberMode.Eink -> EmberEinkColors
}

fun EmberMode.style(): EmberStyle = when (this) {
    EmberMode.Night -> EmberNightStyle
    EmberMode.Day -> EmberDayStyle
    EmberMode.Eink -> EmberEinkStyle
}
