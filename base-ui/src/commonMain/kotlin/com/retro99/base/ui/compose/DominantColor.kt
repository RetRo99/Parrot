package com.retro99.base.ui.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import coil3.Image
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

expect fun Image.dominantColor(): Color

internal expect fun ImageRequest.Builder.configureForPixelAccess(): ImageRequest.Builder

internal fun averageColorFromARGBPixels(pixels: IntArray): Color {
    if (pixels.isEmpty()) return Color.Black
    var redSum = 0L
    var greenSum = 0L
    var blueSum = 0L
    var count = 0L
    pixels.forEach { argb ->
        if (((argb ushr 24) and 0xFF) == 0) return@forEach
        redSum += (argb shr 16) and 0xFF
        greenSum += (argb shr 8) and 0xFF
        blueSum += argb and 0xFF
        count++
    }
    if (count == 0L) return Color.Black
    return Color(
        red = (redSum / count) / 255f,
        green = (greenSum / count) / 255f,
        blue = (blueSum / count) / 255f,
    )
}

internal fun averageColorFromRGBAPremulBytes(bytes: ByteArray): Color {
    if (bytes.size < 4) return Color.Black
    var redSum = 0L
    var greenSum = 0L
    var blueSum = 0L
    var count = 0L
    var i = 0
    while (i + 3 < bytes.size) {
        val alpha = bytes[i].toInt() and 0xFF
        if (alpha == 0) {
            i += 4
            continue
        }
        blueSum += bytes[i + 1].toInt() and 0xFF
        greenSum += bytes[i + 2].toInt() and 0xFF
        redSum += bytes[i + 3].toInt() and 0xFF
        count++
        i += 4
    }
    if (count == 0L) return Color.Black
    return Color(
        red = (redSum / count) / 255f,
        green = (greenSum / count) / 255f,
        blue = (blueSum / count) / 255f,
    )
}

internal val dominantColorCache = mutableMapOf<String, Color>()
internal val dominantColorCacheMutex = Mutex()

@Composable
fun rememberDominantColorState(
    url: String?,
    cacheKey: String?,
    defaultColor: Color,
): State<Color> {
    val effectiveKey = cacheKey ?: url
    val context: PlatformContext = LocalPlatformContext.current
    val imageLoader = remember { SingletonImageLoader.get(context) }
    val colorState = remember(effectiveKey) {
        val key = effectiveKey

        val cachedColor = key?.let { dominantColorCache[it] }
        if (cachedColor != null) {
            mutableStateOf(cachedColor)
        } else {
            val coilCached = key?.let {
                imageLoader.memoryCache?.get(MemoryCache.Key(it))?.image
            }
            val initial = if (coilCached != null) {
                runCatching { coilCached.dominantColor() }.getOrDefault(defaultColor)
            } else {
                defaultColor
            }
            if (coilCached != null && key != null && dominantColorCacheMutex.tryLock()) {
                try {
                    dominantColorCache[key] = initial
                } finally {
                    dominantColorCacheMutex.unlock()
                }
            }
            mutableStateOf(initial)
        }
    }
    LaunchedEffect(effectiveKey) {
        if (url.isNullOrBlank()) {
            colorState.value = defaultColor
            return@LaunchedEffect
        }
        val key = effectiveKey ?: url
        if (dominantColorCache.containsKey(key)) return@LaunchedEffect

        val request = ImageRequest.Builder(context)
            .data(url)
            .size(48, 48)
            .configureForPixelAccess()
            .apply {
                if (cacheKey != null) {
                    memoryCacheKey("${cacheKey}_dominant")
                    diskCacheKey("${cacheKey}_dominant")
                }
            }
            .build()
        val result = withContext(Dispatchers.IO) {
            runCatching { imageLoader.execute(request) }
        }
        val success = result.getOrNull() as? SuccessResult
        val image = success?.image
        val resolved = if (image != null) {
            withContext(Dispatchers.Default) {
                runCatching { image.dominantColor() }.getOrDefault(defaultColor)
            }
        } else {
            defaultColor
        }
        dominantColorCacheMutex.withLock {
            dominantColorCache[key] = resolved
        }
        colorState.value = resolved
    }
    return colorState
}

fun backdropColorScheme(seedColor: Color): ColorScheme {
    val isLightSeed = seedColor.luminance() > 0.5f
    return if (isLightSeed) lightBackdropScheme(seedColor) else darkBackdropScheme(seedColor)
}

private fun darkBackdropScheme(seed: Color): ColorScheme {
    val surface = seed.blend(Color.Black, 0.65f)
    val onSurface = Color.White
    val surfaceVariant = seed.blend(Color.Black, 0.4f)
    val onSurfaceVariant = Color.White.copy(alpha = 0.7f)
    // Action colors stay on the brand palette: a muted cover must never turn the
    // primary CTA grey, which reads as "disabled".
    val brand = emberColorScheme(EmberMode.Night)
    val primary = brand.primary
    val onPrimary = brand.onPrimary
    val primaryContainer = brand.primaryContainer
    val onPrimaryContainer = brand.onPrimaryContainer
    val secondary = brand.secondary
    val onSecondary = brand.onSecondary
    val secondaryContainer = brand.secondaryContainer
    val onSecondaryContainer = brand.onSecondaryContainer
    val tertiary = brand.tertiary
    val onTertiary = brand.onTertiary
    val tertiaryContainer = brand.tertiaryContainer
    val onTertiaryContainer = brand.onTertiaryContainer
    val background = surface
    val onBackground = Color.White
    val outline = Color.White.copy(alpha = 0.2f)
    val outlineVariant = Color.White.copy(alpha = 0.12f)
    val error = Color(0xFFCF6679)
    val onError = Color.Black
    val errorContainer = Color(0xFFB3261E)
    val onErrorContainer = Color.White
    val inverseSurface = Color.White.copy(alpha = 0.9f)
    val inverseOnSurface = Color.Black
    val inversePrimary = seed.blend(Color.White, 0.6f)
    val surfaceTint = primary
    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
    )
}

private fun lightBackdropScheme(seed: Color): ColorScheme {
    val surface = seed.blend(Color.White, 0.65f)
    val onSurface = Color.Black
    val surfaceVariant = seed.blend(Color.White, 0.5f)
    val onSurfaceVariant = Color.Black.copy(alpha = 0.65f)
    // Keep action colors on the brand palette so the primary CTA always reads
    // as tappable, whatever the cover looks like.
    val brand = emberColorScheme(EmberMode.Day)
    val primary = brand.primary
    val onPrimary = brand.onPrimary
    val primaryContainer = brand.primaryContainer
    val onPrimaryContainer = brand.onPrimaryContainer
    val secondary = brand.secondary
    val onSecondary = brand.onSecondary
    val secondaryContainer = brand.secondaryContainer
    val onSecondaryContainer = brand.onSecondaryContainer
    val tertiary = brand.tertiary
    val onTertiary = brand.onTertiary
    val tertiaryContainer = brand.tertiaryContainer
    val onTertiaryContainer = brand.onTertiaryContainer
    val background = surface
    val onBackground = Color.Black
    val outline = Color.Black.copy(alpha = 0.25f)
    val outlineVariant = Color.Black.copy(alpha = 0.12f)
    val error = Color(0xFFB3261E)
    val onError = Color.White
    val errorContainer = Color(0xFFF9DEDC)
    val onErrorContainer = Color(0xFF410E0B)
    val inverseSurface = Color.Black.copy(alpha = 0.9f)
    val inverseOnSurface = Color.White
    val inversePrimary = seed.blend(Color.White, 0.3f)
    val surfaceTint = primary
    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
    )
}

private fun Color.blend(other: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * f,
        green = green + (other.green - green) * f,
        blue = blue + (other.blue - blue) * f,
        alpha = alpha + (other.alpha - alpha) * f,
    )
}
