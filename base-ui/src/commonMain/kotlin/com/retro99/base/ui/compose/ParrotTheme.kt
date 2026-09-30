package com.retro99.base.ui.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import resources.icons.Res
import resources.icons.figtree_bold
import resources.icons.figtree_regular
import resources.icons.figtree_semibold
import resources.icons.fraunces_bold
import resources.icons.fraunces_italic
import resources.icons.fraunces_medium
import resources.icons.fraunces_medium_italic
import resources.icons.fraunces_semibold
import resources.icons.literata_regular

/** Fraunces, used for titles and italic section headings. */
@Composable
fun frauncesFamily(): FontFamily = FontFamily(
    Font(Res.font.fraunces_medium, FontWeight.Medium),
    Font(Res.font.fraunces_semibold, FontWeight.SemiBold),
    Font(Res.font.fraunces_bold, FontWeight.Bold),
    Font(Res.font.fraunces_italic, FontWeight.Normal, FontStyle.Italic),
    Font(Res.font.fraunces_medium_italic, FontWeight.Medium, FontStyle.Italic),
)

/** Figtree, used for UI text. */
@Composable
fun figtreeFamily(): FontFamily = FontFamily(
    Font(Res.font.figtree_regular, FontWeight.Normal),
    Font(Res.font.figtree_semibold, FontWeight.SemiBold),
    Font(Res.font.figtree_bold, FontWeight.Bold),
)

/** Literata, used for the read-along sample on the Welcome screen. */
@Composable
fun literataFamily(): FontFamily = FontFamily(
    Font(Res.font.literata_regular, FontWeight.Normal),
)

/** Ember text styles for custom layouts. Material components use the mapped [Typography]. */
@Immutable
data class EmberType(
    val screenTitle: TextStyle,
    val cardTitle: TextStyle,
    val section: TextStyle,
    val bookTitle: TextStyle,
    val author: TextStyle,
    val meta: TextStyle,
    val label: TextStyle,
    val eyebrow: TextStyle,
    val navLabel: TextStyle,
)

private fun emberType(
    style: EmberStyle,
    serif: FontFamily,
    sans: FontFamily,
): EmberType = EmberType(
    screenTitle = TextStyle(
        fontFamily = serif,
        fontWeight = style.titleWeight,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    cardTitle = TextStyle(
        fontFamily = serif,
        fontWeight = style.titleWeight,
        fontSize = 21.sp,
        lineHeight = 24.sp,
    ),
    section = TextStyle(
        fontFamily = serif,
        fontWeight = style.italicWeight,
        fontStyle = FontStyle.Italic,
        fontSize = 19.sp,
    ),
    bookTitle = TextStyle(
        fontFamily = serif,
        fontWeight = style.titleWeight,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    author = TextStyle(
        fontFamily = serif,
        fontWeight = style.italicWeight,
        fontStyle = FontStyle.Italic,
        fontSize = 14.sp,
    ),
    meta = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    ),
    label = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
    ),
    eyebrow = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.76.sp,
    ),
    navLabel = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
    ),
)

/**
 * Material color scheme derived from the Ember tokens, so stock components
 * (dialogs, text fields, sheets) follow the theme.
 */
fun emberColorScheme(mode: EmberMode): ColorScheme {
    val colors = mode.colors()
    return if (mode == EmberMode.Night) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.navActive,
            onPrimaryContainer = colors.navActiveContent,
            secondary = colors.accentText,
            onSecondary = colors.onAccent,
            secondaryContainer = colors.navActive,
            onSecondaryContainer = colors.navActiveContent,
            tertiary = colors.accentText,
            onTertiary = colors.onAccent,
            tertiaryContainer = colors.navActive,
            onTertiaryContainer = colors.navActiveContent,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.bg,
            onSurface = colors.ink,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.ink2,
            surfaceContainerLowest = colors.nav,
            surfaceContainerLow = colors.bg,
            surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surface,
            surfaceContainerHighest = colors.track,
            outline = colors.ink2,
            outlineVariant = colors.line,
            error = colors.error,
            onError = colors.onError,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.navActive,
            onPrimaryContainer = colors.navActiveContent,
            secondary = colors.accentText,
            onSecondary = colors.onAccent,
            secondaryContainer = colors.navActive,
            onSecondaryContainer = colors.navActiveContent,
            tertiary = colors.accentText,
            onTertiary = colors.onAccent,
            tertiaryContainer = colors.navActive,
            onTertiaryContainer = colors.navActiveContent,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.bg,
            onSurface = colors.ink,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.ink2,
            surfaceContainerLowest = colors.nav,
            surfaceContainerLow = colors.bg,
            surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surface,
            surfaceContainerHighest = colors.track,
            outline = colors.ink2,
            outlineVariant = colors.line,
            error = colors.error,
            onError = colors.onError,
        )
    }
}

/**
 * Shared Material type scale: Fraunces for headings, Figtree for everything else.
 */
private fun emberTypography(
    serif: FontFamily,
    sans: FontFamily,
    titleWeight: FontWeight,
): Typography = Typography(
    headlineSmall = TextStyle(
        fontFamily = serif,
        fontWeight = titleWeight,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = serif,
        fontWeight = titleWeight,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

val LocalEmberColors = staticCompositionLocalOf { EmberNightColors }
val LocalEmberStyle = staticCompositionLocalOf { EmberNightStyle }
val LocalEmberType = staticCompositionLocalOf<EmberType> {
    error("Wrap your UI in ParrotTheme { }")
}

/** Accessors for the Ember design tokens of the current theme. */
object Ember {
    val colors: EmberColors
        @Composable get() = LocalEmberColors.current
    val style: EmberStyle
        @Composable get() = LocalEmberStyle.current
    val type: EmberType
        @Composable get() = LocalEmberType.current
}

/**
 * App-wide theme. Provides the Ember tokens and maps them onto [MaterialTheme];
 * e-ink mode also turns ripples off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParrotTheme(
    mode: EmberMode = EmberMode.Night,
    content: @Composable () -> Unit,
) {
    val colors = mode.colors()
    val style = mode.style()
    val serif = frauncesFamily()
    val sans = figtreeFamily()
    val emberType = remember(style, serif, sans) { emberType(style, serif, sans) }
    val typography = remember(style, serif, sans) {
        emberTypography(serif, sans, style.titleWeight)
    }
    val colorScheme = remember(mode) { emberColorScheme(mode) }

    CompositionLocalProvider(
        LocalEmberColors provides colors,
        LocalEmberStyle provides style,
        LocalEmberType provides emberType,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
        ) {
            CompositionLocalProvider(
                LocalRippleConfiguration provides
                    if (style.animations) RippleConfiguration() else null,
                content = content,
            )
        }
    }
}
