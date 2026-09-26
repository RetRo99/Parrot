package com.retro99.base.ui.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// E-ink optimized color scheme
// Best practices for e-ink:
// - High contrast (pure black on white)
// - Limited grayscale palette (e-ink typically supports 16 levels)
// - Avoid gradients and animations
// - Use light gray (#F0F0F0) for subtle surface differentiation
private val EinkLightGray = Color(0xFFF0F0F0)
private val EinkDarkGray = Color(0xFF333333)

val EinkColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = EinkLightGray,
    onPrimaryContainer = Color.Black,
    secondary = EinkDarkGray,
    onSecondary = Color.White,
    secondaryContainer = EinkLightGray,
    onSecondaryContainer = Color.Black,
    tertiary = EinkDarkGray,
    onTertiary = Color.White,
    tertiaryContainer = EinkLightGray,
    onTertiaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = EinkLightGray,
    onSurfaceVariant = EinkDarkGray,
    outline = EinkDarkGray,
    outlineVariant = EinkLightGray,
    error = Color.Black,
    onError = Color.White,
    errorContainer = EinkLightGray,
    onErrorContainer = Color.Black,
)

// Parrot brand palette: a calm reading green, tuned for high contrast in both schemes.
val ParrotLightColorScheme = lightColorScheme(
    primary = Color(0xFF2E6B52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0D4),
    onPrimaryContainer = Color(0xFF073826),
    secondary = Color(0xFF4E6356),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E8DA),
    onSecondaryContainer = Color(0xFF0C281B),
    tertiary = Color(0xFF3C6472),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC6E7F5),
    onTertiaryContainer = Color(0xFF0A3340),
    background = Color(0xFFF9FBF7),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFF9FBF7),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDDE5DD),
    onSurfaceVariant = Color(0xFF3F4941),
    outline = Color(0xFF6F7971),
    outlineVariant = Color(0xFFBFC9C0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val ParrotDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8FD6B4),
    onPrimary = Color(0xFF073826),
    primaryContainer = Color(0xFF1E4F39),
    onPrimaryContainer = Color(0xFFB7F0D4),
    secondary = Color(0xFFB7CCBB),
    onSecondary = Color(0xFF1F3529),
    secondaryContainer = Color(0xFF354B3E),
    onSecondaryContainer = Color(0xFFD6E8DA),
    tertiary = Color(0xFFA6CDE0),
    onTertiary = Color(0xFF073442),
    tertiaryContainer = Color(0xFF234B59),
    onTertiaryContainer = Color(0xFFC6E7F5),
    background = Color(0xFF101512),
    onBackground = Color(0xFFE0E3DD),
    surface = Color(0xFF101512),
    onSurface = Color(0xFFE0E3DD),
    surfaceVariant = Color(0xFF3F4941),
    onSurfaceVariant = Color(0xFFBFC9C0),
    outline = Color(0xFF89938B),
    outlineVariant = Color(0xFF3F4941),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Shared type scale for the whole app. Slightly larger body/label styles than the
 * Material defaults so primary actions read as tappable and screens stay scannable.
 */
val ParrotTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

/**
 * App-wide theme. Wraps [MaterialTheme] with the Parrot color palette and type scale;
 * e-ink devices get a dedicated grayscale scheme.
 */
@Composable
fun ParrotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    eink: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        eink -> EinkColorScheme
        darkTheme -> ParrotDarkColorScheme
        else -> ParrotLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ParrotTypography,
        content = content,
    )
}
