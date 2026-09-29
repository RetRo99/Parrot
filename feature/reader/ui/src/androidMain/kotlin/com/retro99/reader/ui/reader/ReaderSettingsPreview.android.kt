package com.retro99.reader.ui.reader

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import com.retro99.base.ui.compose.Ember
import com.retro99.reader.domain.model.HighlightStyle
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.ui.model.toUiModel
import com.retro99.reader.ui.navigator.toEpubPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

private const val PREVIEW_FRAGMENT_TAG = "reader_settings_preview"
private const val PREVIEW_DECORATION_GROUP = "reader_settings_preview_highlight"
private const val SAMPLE_ASSET = "reader-preview/sample.epub"
private const val SAMPLE_HIGHLIGHT = "Words found their rhythm, and the room seemed to lean in."

/**
 * Android preview: a real Readium navigator showing a bundled sample EPUB. It applies the same
 * preferences as the reader and re-applies them on every change, so the page reflows exactly as
 * the reader would. It swallows all touches; it is a preview, not a reader.
 */
@OptIn(ExperimentalReadiumApi::class)
@Composable
internal actual fun ReaderSettingsPreviewPage(
    settings: ReaderSettingsDomainModel,
    showReadAloudHighlight: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return
    val publication by produceState<Publication?>(initialValue = null) {
        value = openSamplePublication(context)
    }
    val pageColor = Ember.colors.bg
    val preferences = remember(settings) { settings.toUiModel().toEpubPreferences() }
    var fragment by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val containerId = remember { View.generateViewId() }

    DisposableEffect(Unit) {
        onDispose {
            activity.supportFragmentManager.findFragmentByTag(PREVIEW_FRAGMENT_TAG)?.let { existing ->
                if (!activity.supportFragmentManager.isStateSaved) {
                    activity.supportFragmentManager.commit(allowStateLoss = true) { remove(existing) }
                }
            }
        }
    }

    LaunchedEffect(fragment, preferences) {
        fragment?.submitPreferences(preferences)
    }

    LaunchedEffect(fragment, showReadAloudHighlight, settings.highlightStyle, settings.highlightColor, settings.underlineColor) {
        val navigator = fragment ?: return@LaunchedEffect
        val samplePublication = publication ?: return@LaunchedEffect
        if (!showReadAloudHighlight) {
            navigator.applyDecorations(emptyList(), PREVIEW_DECORATION_GROUP)
            return@LaunchedEffect
        }
        val link = samplePublication.readingOrder.firstOrNull() ?: return@LaunchedEffect
        val base = samplePublication.locatorFromLink(link) ?: return@LaunchedEffect
        val locator = base.copy(text = Locator.Text(highlight = SAMPLE_HIGHLIGHT))
        val decorations = buildList {
            if (settings.highlightStyle != HighlightStyle.UNDERLINE) {
                add(Decoration("preview-highlight", locator, Decoration.Style.Highlight(tint = settings.highlightColor)))
            }
            if (settings.highlightStyle != HighlightStyle.HIGHLIGHT) {
                add(Decoration("preview-underline", locator, Decoration.Style.Underline(tint = settings.underlineColor)))
            }
        }
        navigator.applyDecorations(decorations, PREVIEW_DECORATION_GROUP)
    }

    Box(modifier = modifier.fillMaxSize().background(pageColor)) {
        val samplePublication = publication
        if (samplePublication != null) {
            val navigatorFactory = remember(samplePublication) { EpubNavigatorFactory(samplePublication) }
            val configuration = remember {
                EpubNavigatorFragment.Configuration(
                    decorationTemplates = createUserAlphaDecorationTemplates(),
                ).apply { registerBundledFonts() }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId } },
                update = { container ->
                    val fragmentManager = activity.supportFragmentManager
                    if (fragmentManager.isStateSaved || activity.isFinishing || activity.isDestroyed) {
                        return@AndroidView
                    }
                    var existing = fragmentManager.findFragmentByTag(PREVIEW_FRAGMENT_TAG) as? EpubNavigatorFragment
                    if (existing == null) {
                        fragmentManager.fragmentFactory = navigatorFactory.createFragmentFactory(
                            initialLocator = null,
                            initialPreferences = preferences,
                            configuration = configuration,
                        )
                        fragmentManager.commitNow(allowStateLoss = true) {
                            add(container.id, EpubNavigatorFragment::class.java, null, PREVIEW_FRAGMENT_TAG)
                        }
                        existing = fragmentManager.findFragmentByTag(PREVIEW_FRAGMENT_TAG) as? EpubNavigatorFragment
                    }
                    if (fragment !== existing) fragment = existing
                },
            )
        }

        // Preview only: swallow every touch.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { change -> change.consume() }
                        }
                    }
                },
        )
    }
}

/** Copies the bundled sample EPUB to the cache and opens it with Readium. */
private suspend fun openSamplePublication(context: android.content.Context): Publication? =
    withContext(Dispatchers.IO) {
        runCatching {
            val file = File(context.cacheDir, "reader-settings-sample.epub")
            context.assets.open(SAMPLE_ASSET).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
            val parser = DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null)
            val asset = assetRetriever.retrieve(file.toUrl(isDirectory = false)).getOrElse { return@runCatching null }
            PublicationOpener(parser).open(asset, allowUserInteraction = false).getOrElse { null }
        }.getOrNull()
    }
