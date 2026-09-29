package com.retro99.reader.ui.reader

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.retro99.base.ui.IntentDispatcher
import com.retro99.reader.ui.navigator.AndroidBookController
import com.retro99.reader.ui.navigator.BookController
import com.retro99.reader.ui.navigator.SentenceTapJsInterface
import com.retro99.reader.ui.navigator.toAndroidLocator
import com.retro99.reader.ui.navigator.toEpubPreferences
import com.retro99.reader.ui.publication.PublicationState
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.html.toCss
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.toUrl
import java.io.File

private const val NAVIGATOR_FRAGMENT_TAG_PREFIX = "epub_navigator_"

/**
 * Android implementation of EPUB reader using Readium's EpubNavigatorFragment.
 */
@Composable
internal actual fun EpubReaderViewInternal(
    bookUuid: String,
    publicationState: PublicationState,
    intentDispatcher: IntentDispatcher<ReaderIntent>,
    bookController: BookController,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: run {
        ReaderErrorView(message = "Error: Activity is not a FragmentActivity", modifier = modifier)
        return
    }

    // Use book-specific tag to avoid conflicts when switching between books
    val navigatorFragmentTag = remember(bookUuid) { "$NAVIGATOR_FRAGMENT_TAG_PREFIX$bookUuid" }

    val publication = publicationState.publication
    val readiumPublication = publication.publication

    val navigatorController = bookController as? AndroidBookController
    val customFontsKey = publicationState.customFonts.joinToString(separator = "|") {
        "${it.id}:${it.filePath}"
    }
    var appliedCustomFontsKey by remember(bookUuid) { mutableStateOf<String?>(null) }

    // Observe permission denied dialog state
//    val showPermissionDeniedDialog by navigatorController?.showPermissionDeniedDialog
//        ?.collectAsState(initial = false)
//        ?: remember { mutableStateOf(false) }
//    val showPermissionRationale by navigatorController?.showPermissionRationale
//        ?.collectAsState(initial = false)
//        ?: remember { mutableStateOf(false) }
//    val openAppSettings = rememberOpenAppSettings()

//    ObservePermissionDeniedDialog(
//        navigator = navigatorController,
//        showDialog = showPermissionDeniedDialog,
//        showRationale = showPermissionRationale,
//        onOpenSettings = openAppSettings,
//        onTryAgain = {
//            // User wants to try again - trigger play which will re-request permission
//            navigatorController?.playAudio()
//        },
//        onDismiss = { /* Dialog dismissed without opening settings */ },
//    )

    val navigatorFactory = remember(readiumPublication) {
        EpubNavigatorFactory(readiumPublication)
    }

    // Create navigator configuration with JavaScript interface for tap detection
    // Native code handles double-tap detection timing for consistent behavior
    // Use custom decoration templates that respect the user's alpha choice from the color picker
    // (Readium's default templates override alpha with 0.3, ignoring the user's selection)
    val navigatorConfiguration = remember(navigatorController, customFontsKey) {
        EpubNavigatorFragment.Configuration(
            decorationTemplates = createUserAlphaDecorationTemplates()
        ).apply {
            registerBundledFonts()
            registerCustomFonts(publicationState)
            // Register JavaScript interface for tap detection on sentences
            // Double-tap detection is handled natively in AndroidBookController
            navigatorController?.let { controller ->
                registerJavascriptInterface("SentenceTap") { _ ->
                    SentenceTapJsInterface(controller::onSentenceTap)
                }
            }
        }
    }

    val containerId = remember { View.generateViewId() }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Use lifecycle observer to properly clean up the fragment.
    // We remove the fragment during ON_DESTROY to clean up resources while keeping
    // the fragment visible during lock/unlock (ON_STOP/ON_START cycles).
    DisposableEffect(bookUuid, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                // Release media overlay player resources
                navigatorController?.close()
                val existingFragment = activity.supportFragmentManager
                    .findFragmentByTag(navigatorFragmentTag)
                if (existingFragment != null) {
                    // Use commitNow to ensure the fragment is removed synchronously
                    // before onSaveInstanceState is called
                    activity.supportFragmentManager.commitNow(allowStateLoss = true) {
                        remove(existingFragment)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Also clean up on dispose in case ON_STOP wasn't called
            navigatorController?.close()
            val existingFragment = activity.supportFragmentManager
                .findFragmentByTag(navigatorFragmentTag)
            if (existingFragment != null) {
                activity.supportFragmentManager.commit(allowStateLoss = true) {
                    remove(existingFragment)
                }
            }
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = containerId
            }
        },
        update = { containerView ->
            val fragmentManager = activity.supportFragmentManager
            if (fragmentManager.isStateSaved || activity.isFinishing || activity.isDestroyed) {
                return@AndroidView
            }

            // Only add fragment if it doesn't exist for THIS book
            var existingFragment = fragmentManager.findFragmentByTag(navigatorFragmentTag)
                    as? EpubNavigatorFragment
            if (existingFragment != null && appliedCustomFontsKey != customFontsKey) {
                navigatorController?.close()
                fragmentManager.commitNow(allowStateLoss = true) {
                    remove(existingFragment)
                }
                existingFragment = null
            }
            if (existingFragment == null) {
                // Use current settings and position from PublicationState
                // This ensures the fragment is created with up-to-date values on rotation
                val initialLocator = publicationState.position?.toAndroidLocator()
                fragmentManager.fragmentFactory = navigatorFactory.createFragmentFactory(
                    initialLocator = initialLocator,
                    initialPreferences = publicationState.settings.toEpubPreferences(),
                    configuration = navigatorConfiguration,
                )

                // Use commitNow to make the transaction synchronous
                // This ensures the fragment is immediately available after this call
                fragmentManager.commitNow(allowStateLoss = true) {
                    add(
                        containerView.id,
                        EpubNavigatorFragment::class.java,
                        null,
                        navigatorFragmentTag,
                    )
                }
                appliedCustomFontsKey = customFontsKey
            }

            // Create navigator controller if needed
            val navigatorFragment = (existingFragment
                ?: fragmentManager.findFragmentByTag(navigatorFragmentTag))
                    as? EpubNavigatorFragment

            navigatorFragment?.let {
                navigatorController?.init(
                    navigator = it,
                    hasMediaOverlays = publication.hasMediaOverlays,
                )
            }
        },
    )
}

@OptIn(ExperimentalReadiumApi::class)
internal fun EpubNavigatorFragment.Configuration.registerBundledFonts() {
    servedAssets += "reader-fonts/.*"
    bundledReaderFonts.forEach { font ->
        addFontFamilyDeclaration(FontFamily(font.cssFamily)) {
            addFontFace {
                addSource(font.assetPath, preload = true)
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(FontWeight.NORMAL)
            }
        }
    }
}

@OptIn(ExperimentalReadiumApi::class)
private fun EpubNavigatorFragment.Configuration.registerCustomFonts(
    publicationState: PublicationState,
) {
    publicationState.customFonts.forEach { font ->
        val fontUrl = File(font.filePath).toUrl(isDirectory = false)
        addFontFamilyDeclaration(FontFamily(font.cssFamily)) {
            addFontFace {
                addSource(fontUrl, preload = true)
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(FontWeight.NORMAL)
            }
        }
    }
}

private data class BundledReaderFont(
    val cssFamily: String,
    val assetPath: String,
)

private val bundledReaderFonts = listOf(
    BundledReaderFont(
        cssFamily = "Droid Sans",
        assetPath = "reader-fonts/bundled/DroidSans.ttf",
    ),
    BundledReaderFont(
        cssFamily = "Atkinson Hyperlegible",
        assetPath = "reader-fonts/bundled/AtkinsonHyperlegible-Regular.ttf",
    ),
    BundledReaderFont(
        cssFamily = "Literata",
        assetPath = "reader-fonts/bundled/Literata.ttf",
    ),
    BundledReaderFont(
        cssFamily = "Merriweather",
        assetPath = "reader-fonts/bundled/Merriweather.ttf",
    ),
    BundledReaderFont(
        cssFamily = "Source Serif 4",
        assetPath = "reader-fonts/bundled/SourceSerif4.ttf",
    ),
    BundledReaderFont(
        cssFamily = "Noto Sans",
        assetPath = "reader-fonts/bundled/NotoSans.ttf",
    ),
    BundledReaderFont(
        cssFamily = "Noto Serif",
        assetPath = "reader-fonts/bundled/NotoSerif.ttf",
    ),
)

/**
 * Creates decoration templates that respect the user's alpha choice from the color picker.
 *
 * Readium's default templates use `alpha = 0.3` which overrides any alpha value set in the
 * decoration's tint color. This function creates custom templates that use `toCss()` without
 * an alpha override, so the alpha from the color itself is used.
 */
internal fun createUserAlphaDecorationTemplates(): HtmlDecorationTemplates {
    val defaultTint = android.graphics.Color.YELLOW
    val lineWeight = 2
    val cornerRadius = 3

    return HtmlDecorationTemplates {
        set(
            Decoration.Style.Highlight::class,
            createHighlightTemplate(defaultTint, lineWeight, cornerRadius)
        )
        set(
            Decoration.Style.Underline::class,
            createUnderlineTemplate(defaultTint, lineWeight, cornerRadius)
        )
    }
}

private var classNamesId = 0
private fun createUniqueClassName(key: String): String = "r2-$key-${++classNamesId}"

/**
 * Creates a highlight template that uses the color's own alpha value.
 * This template only renders a background highlight, no underline.
 */
private fun createHighlightTemplate(
    defaultTint: Int,
    lineWeight: Int,
    cornerRadius: Int,
): HtmlDecorationTemplate {
    val className = createUniqueClassName("highlight")

    return HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOXES,
        element = { decoration ->
            val tint = (decoration.style as? Decoration.Style.Tinted)?.tint ?: defaultTint
            val css = buildString {
                // Use toCss() without alpha parameter to respect the color's own alpha
                append("background-color: ${tint.toCss()} !important;")
            }
            """<div class="$className" style="$css"/>"""
        },
        stylesheet = createHighlightStylesheet(className, cornerRadius)
    )
}

/**
 * Creates an underline template that uses the color's own alpha value.
 *
 * Unlike the highlight template, we set the border-color directly in the inline style
 * to ensure it overrides any CSS rules that might set a default color (e.g., white in dark theme).
 * Inline styles have the highest specificity, so this ensures the user's color is always used.
 */
private fun createUnderlineTemplate(
    defaultTint: Int,
    lineWeight: Int,
    cornerRadius: Int,
): HtmlDecorationTemplate {
    val className = createUniqueClassName("underline")

    return HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOXES,
        element = { decoration ->
            val tint = (decoration.style as? Decoration.Style.Tinted)?.tint ?: defaultTint
            val isActive = (decoration.style as? Decoration.Style.Activable)?.isActive ?: false
            val colorCss = tint.toCss()
            val css = buildString {
                if (isActive) {
                    // Use toCss() without alpha parameter to respect the color's own alpha
                    append("background-color: $colorCss !important;")
                }
                // Set border-color directly in inline style to override any CSS rules
                // (including Readium's dark theme CSS that might set border to white)
                append("border-color: $colorCss !important;")
            }
            """<div class="$className" style="$css"/>"""
        },
        stylesheet = createUnderlineStylesheet(className, lineWeight, cornerRadius)
    )
}

/**
 * Creates a stylesheet for highlight decorations.
 * Only includes background styling, no border/underline.
 */
private fun createHighlightStylesheet(
    className: String,
    cornerRadius: Int,
): String = """
    .$className {
        margin: 0px -1px 0 0;
        padding: 0 2px 0px 0;
        border-radius: ${cornerRadius}px;
        box-sizing: border-box;
    }
"""

/**
 * Creates a stylesheet for underline decorations.
 * The border-color is set directly in the inline style (not via CSS variable)
 * to ensure it overrides any CSS rules that might set a default color in dark theme.
 */
private fun createUnderlineStylesheet(
    className: String,
    lineWeight: Int,
    cornerRadius: Int,
): String = """
    .$className {
        margin: 0px -1px 0 0;
        padding: 0 2px 0px 0;
        border-radius: ${cornerRadius}px;
        box-sizing: border-box;
        /* border-color is set in inline style to override dark theme CSS */
        border-width: 0;
        border-style: solid;
    }

    /* Horizontal (default) */
    [data-writing-mode="horizontal-tb"].$className {
        border-bottom-width: ${lineWeight}px;
    }

    /* Vertical right-to-left */
    [data-writing-mode="vertical-rl"].$className,
    [data-writing-mode="sideways-rl"].$className {
        border-left-width: ${lineWeight}px;
    }

    /* Vertical left-to-right */
    [data-writing-mode="vertical-lr"].$className,
    [data-writing-mode="sideways-lr"].$className {
        border-right-width: ${lineWeight}px;
    }
"""
