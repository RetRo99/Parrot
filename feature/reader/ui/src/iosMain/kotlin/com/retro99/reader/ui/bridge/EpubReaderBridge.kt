package com.retro99.reader.ui.bridge

import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.ReaderSettingsUiModel
import platform.UIKit.UIViewController

data class EpubReaderCustomFont(
    val cssFamily: String,
    val filePath: String,
)

/**
 * Reader settings data class for iOS bridge.
 * This is a simple data holder that can be passed to Swift.
 * Add new settings properties here as needed.
 */
data class EpubReaderSettings(
    val fontSize: Double,
    val fontFamily: String,
    val fontWeight: Double,
    val textNormalization: Boolean,
    val theme: String,
    val lineHeight: Float,
    val paragraphSpacing: Double,
    val marginHorizontal: Int,
    val marginVertical: Int,
    val scrollMode: Boolean?,
    val textAlign: String,
    val publisherStyles: Boolean,
    // Highlight color as ARGB Int value
    val highlightColorArgb: Int,
    // Underline color as ARGB Int value
    val underlineColorArgb: Int,
    val highlightStyle: String,
    val initialPosition: PositionUiModel?,
    val customFonts: List<EpubReaderCustomFont>,
) {
    companion object {
        fun from(
            settings: ReaderSettingsUiModel,
            initialPosition: PositionUiModel? = null,
            customFonts: List<CustomReaderFontDomainModel> = emptyList(),
        ): EpubReaderSettings {
            return EpubReaderSettings(
                fontSize = settings.fontSize,
                fontFamily = settings.fontFamily,
                fontWeight = settings.fontWeight,
                textNormalization = settings.textNormalization,
                theme = settings.theme.name,
                lineHeight = settings.lineHeight,
                paragraphSpacing = settings.paragraphSpacing,
                marginHorizontal = settings.marginHorizontal,
                marginVertical = settings.marginVertical,
                scrollMode = settings.scrollMode,
                textAlign = settings.textAlign.name,
                publisherStyles = settings.publisherStyles,
                highlightColorArgb = settings.highlightColor,
                underlineColorArgb = settings.underlineColor,
                highlightStyle = settings.highlightStyle.name,
                initialPosition = initialPosition,
                customFonts = customFonts.map {
                    EpubReaderCustomFont(
                        cssFamily = it.cssFamily,
                        filePath = it.filePath,
                    )
                },
            )
        }
    }
}

/**
 * Bridge interface for iOS EPUB reader functionality.
 * This interface is implemented in Swift and registered at app startup.
 * The Kotlin code uses this bridge to delegate to Readium Swift.
 */
interface EpubReaderBridge {
    /**
     * Opens an EPUB publication from the given file path.
     * @param filePath The absolute path to the EPUB file
     * @param onSuccess Called when the publication is successfully opened
     * @param onError Called with an error message if opening fails
     */
    fun openPublication(
        filePath: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    )

    /**
     * Closes the currently open publication and releases resources.
     */
    fun closePublication()

    /**
     * Creates a UIViewController for displaying the EPUB content.
     * @param settings The initial reader settings to apply
     * @return A UIViewController that renders the EPUB, or null if no publication is open
     */
    fun createReaderViewController(settings: EpubReaderSettings): UIViewController?

    /**
     * Navigates to the next page/resource in the publication.
     */
    fun goToNextPage()

    /**
     * Navigates to the previous page/resource in the publication.
     */
    fun goToPreviousPage()

    /**
     * Navigates to a specific chapter by its href.
     * @param href The href of the chapter to navigate to
     */
    fun goToChapter(href: String)

    /**
     * Navigates to a specific position in the publication.
     * @param href The href of the resource
     * @param type The media type of the resource
     * @param progression The progression within the resource (0.0 to 1.0)
     * @param position The position index, if available
     * @param totalProgression The share of the whole book, used when [href] isn't one of the
     *   publication's resources (e.g. an Audiobookshelf CFI) instead of the start of the book
     */
    fun goToPosition(
        href: String,
        type: String,
        progression: Double?,
        position: Int?,
        totalProgression: Double?,
    )

    /**
     * Applies reader settings to the publication.
     * @param settings The reader settings to apply
     */
    fun setSettings(settings: EpubReaderSettings)

    /**
     * Sets a callback to be invoked when the reading position changes.
     * @param callback The callback to invoke with position data, or null to clear
     */
    fun setOnPositionChangedCallback(callback: ((PositionLocator) -> Unit)?)

    /**
     * Sets a callback to be invoked when the user taps on a sentence element.
     * Double-tap detection is handled natively in Kotlin for consistent timing control.
     *
     * @param callback The callback with the fragment ID of the tapped element,
     *                 or null to clear the callback
     */
    fun setOnSentenceTapCallback(callback: ((String) -> Unit)?)

    // Media playback methods for ReadAloud books

    /**
     * Whether the publication has media overlays (audio narration).
     * @return true if the publication has SMIL files or audio resources
     */
    fun hasMediaOverlays(): Boolean

    /**
     * Initializes the media overlay player.
     * Should be called before attempting to play audio.
     * @param onReady Called when initialization is complete
     */
    fun initializeMediaOverlays(onReady: () -> Unit)

    /**
     * Starts audio playback for the current chapter, optionally seeking to a specific position.
     * @param initialPositionMs Optional initial position in milliseconds to seek to before playing
     */
    fun playAudio(initialPositionMs: Long? = null)

    /**
     * Resumes audio playback from the current position without seeking.
     */
    fun resumeAudio()

    /**
     * Pauses audio playback.
     */
    fun pauseAudio()

    /**
     * Seeks to a specific audio position.
     * @param timestampMs The position in milliseconds
     */
    fun seekToAudioPosition(timestampMs: Long)

    /**
     * Sets the playback speed.
     * @param speed The playback speed (e.g., 0.5, 1.0, 1.5, 2.0)
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Skips forward by a fixed increment (10 seconds).
     * Uses the player's authoritative position.
     */
    fun skipForward()

    /**
     * Skips backward by a fixed increment (10 seconds).
     * Uses the player's authoritative position.
     */
    fun skipBackward()

    /**
     * Starts audio playback from a specific text fragment (sentence).
     * Used when user double-taps on a sentence to start playback from that point.
     *
     * @param fragmentId The fragment ID of the sentence (e.g., "chapter44.xhtml-sentence50")
     * @param chapterHref Optional chapter href. If null, uses the current chapter.
     */
    fun playFromFragment(fragmentId: String, chapterHref: String? = null)

    /**
     * Updates the audio position to match a given text fragment ID without starting playback.
     *
     * This is used when the user navigates while audio is not playing, so the seek bar
     * reflects where playback would start. The position is emitted through the playback
     * state callback.
     *
     * @param fragmentId The fragment ID of the sentence (e.g., "chapter44.xhtml-sentence50")
     */
    fun updatePositionForFragment(fragmentId: String)

    /**
     * Sets a callback to be invoked when the audio playback state changes.
     * @param callback The callback with isPlaying state and current position in ms
     */
    fun setOnPlaybackStateChangedCallback(callback: ((PlaybackState) -> Unit)?)

    /**
     * Sets a callback to be invoked when the audio locator changes.
     * @param callback The callback with locator details for highlighting
     */
    fun setOnAudioLocatorChangedCallback(callback: ((AudioLocator) -> Unit)?)

    /**
     * Sets a callback to be invoked when the media player is ready.
     * @param callback The callback to invoke when the player is ready
     */
    fun setOnMediaPlayerReadyCallback(callback: (() -> Unit)?)

    /**
     * Sets a callback to be invoked when the current chapter's audio playback completes.
     * Used to trigger auto-play of the next chapter.
     *
     * @param callback The callback with the href of the completed chapter, or null to clear
     */
    fun setOnChapterAudioCompletedCallback(callback: ((String) -> Unit)?)

    /**
     * Applies a highlight for the given audio locator in the navigator.
     */
    fun applyAudioHighlight(locator: AudioLocator)

    /**
     * Evaluates JavaScript in the navigator's WebView.
     *
     * This is used for checking sentence visibility by querying element positions
     * via getClientRects().
     *
     * @param script The JavaScript code to evaluate
     * @param callback Called with the result string, or null if evaluation failed
     */
    fun evaluateJavaScript(script: String, callback: (String?) -> Unit)

    /**
     * Gets the table of contents for the current publication.
     * @return A list of TOC items, or empty list if no publication is open
     */
    fun getTableOfContents(): List<TocItem>

    fun isSearchable(): Boolean
    fun searchReadingOrder(): List<String>
    fun searchChapterBoundaries(callback: (List<com.retro99.reader.ui.reader.SearchChapterBoundary>) -> Unit)
    fun search(query: String, token: String, onBatch: (List<SearchResultLocator>, Int, () -> Unit) -> Unit,
        onComplete: (Int) -> Unit, onError: (String, Boolean) -> Unit)
    fun cancelSearch(token: String)
    fun goToSearchLocator(locatorJson: String)
    fun decorateSearch(locators: List<String>, selectedIndex: Int, accent: Int, soft: Int, eink: Boolean)
    fun clearSearchDecorations()

    // Bookmarks and highlights. The system edit menu is suppressed; [callback] receives
    // true whenever the reader selects text or changes the selection.
    fun setOnSelectionChangedCallback(callback: ((Boolean) -> Unit)?)
    fun clearSelection()

    /**
     * Frame of the navigator's visible WebView relative to the navigator root, plus the root
     * size, all in points (points and CSS px are both dp here). Lets Kotlin map rects measured
     * inside the WebView onto the Compose space the selection toolbar is anchored in.
     */
    fun webViewGeometry(callback: (EpubWebViewGeometry?) -> Unit)

    /** Replaces every saved highlight on the page. */
    fun applySavedDecorations(decorations: List<SavedDecorationLocator>)

    /** Called with a saved item's id when the reader taps its highlight. */
    fun setOnSavedDecorationTapCallback(callback: ((String) -> Unit)?)
}

/**
 * Frame of the iOS navigator's visible WebView relative to the navigator root, and the root
 * size, in points. Produced by the Swift bridge; consumed by [com.retro99.reader.ui.navigator.IosBookController].
 */
data class EpubWebViewGeometry(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val rootWidth: Double,
    val rootHeight: Double,
)

data class SearchResultLocator(
    val href: String,
    val type: String,
    val title: String?,
    val progression: Double?,
    val position: Int?,
    val totalProgression: Double?,
    val before: String?,
    val match: String?,
    val after: String?,
    val index: Int,
    val locatorJson: String,
)

/**
 * Table of contents item data class for iOS bridge.
 * This is a simple data holder that can be passed from Swift to Kotlin.
 */
data class TocItem(
    val href: String,
    val title: String,
    val level: Int,
)

/**
 * Position locator data class for iOS bridge.
 * This is a simple data holder that can be passed from Swift to Kotlin.
 */
data class PositionLocator(
    val href: String,
    val type: String,
    val title: String?,
    val progression: Double?,
    val position: Int?,
    val totalProgression: Double?,
    val cssSelector: String? = null,
)

/**
 * Playback state data class for iOS bridge.
 * This is a simple data holder that can be passed from Swift to Kotlin.
 */
data class PlaybackState(
    val isPlaying: Boolean,
    val currentPositionMs: Long,
    val durationMs: Long?,
)

/**
 * Audio locator data class for iOS bridge.
 * This is a simple data holder that can be passed from Swift to Kotlin.
 *
 * @param sentenceDurationMs The duration of the current sentence in milliseconds.
 *                           Used for pre-emptive page turn calculations.
 */
data class AudioLocator(
    val href: String,
    val type: String,
    val title: String?,
    val progression: Double?,
    val position: Int?,
    val totalProgression: Double?,
    val fragment: String?,
    val sentenceDurationMs: Long,
)

/**
 * Registry for the EPUB reader bridge.
 * Swift code registers its implementation here at app startup.
 */
object EpubReaderBridgeRegistry {
    private var bridge: EpubReaderBridge? = null

    /**
     * Registers the Swift implementation of the EPUB reader bridge.
     * Should be called from Swift during app initialization.
     */
    fun register(bridge: EpubReaderBridge) {
        this.bridge = bridge
    }

    /**
     * Gets the registered bridge implementation.
     * @return The bridge, or null if not registered
     */
    fun getBridge(): EpubReaderBridge? = bridge

    /**
     * Checks if a bridge has been registered.
     */
    fun isRegistered(): Boolean = bridge != null
}

/** A saved highlight for the page, anchored by its text. */
data class SavedDecorationLocator(
    val id: String,
    val href: String,
    val type: String,
    val progression: Double?,
    val before: String?,
    val highlight: String,
    val after: String?,
    /** Fill blended onto the text, ARGB, alpha included. A transparent fill (e-ink) is only there
     *  to carry the taps: the rules and edge bars are drawn by the page script. */
    val fill: Int,
    /** The fill lands on a dark page: it lightens onto the glyphs instead of darkening them. */
    val darkPage: Boolean = false,
)
