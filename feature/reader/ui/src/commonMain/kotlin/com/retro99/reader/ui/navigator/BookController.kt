package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.model.ChapterInfo
import com.retro99.reader.ui.model.LocatorState
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.ReaderSettingsUiModel
import com.retro99.reader.ui.reader.ReaderSearchResult
import com.retro99.reader.ui.reader.ReaderSearchBatch
import com.retro99.reader.ui.tts.TtsSentence
import kotlinx.coroutines.flow.Flow

/**
 * Result of checking sentence visibility on the current page.
 * Used for pre-emptive page turn logic during TTS playback.
 *
 * @property visibleFraction The fraction of the sentence visible on the current page (0.0 to 1.0).
 *                           1.0 means fully visible, 0.0 means entirely on next/previous page.
 * @property needsPageTurn True if the sentence is split and a page turn will be needed.
 */
data class SentenceVisibilityResult(
    val visibleFraction: Double,
    val needsPageTurn: Boolean,
) {
    companion object {
        /** Default result when visibility cannot be determined - assume fully visible */
        val FULLY_VISIBLE = SentenceVisibilityResult(
            visibleFraction = 1.0,
            needsPageTurn = false,
        )

        /** Result when element is not found or entirely on next page */
        val HIDDEN = SentenceVisibilityResult(
            visibleFraction = 0.0,
            needsPageTurn = true,
        )
    }
}

/**
 * Data class representing a double-tap event on a sentence element.
 *
 * @property fragmentId The ID of the tapped element (e.g., "chapter44.xhtml-sentence50")
 * @property chapterHref The href of the current chapter, if available
 */
data class SentenceDoubleTapEvent(
    val fragmentId: String,
    val chapterHref: String? = null,
)

interface BookController : AutoCloseable {

    /**
     * Whether this book has media overlays (ReadAloud capability).
     * Used to determine if audio-related features should be enabled.
     */
    val hasMediaOverlays: Boolean

    /**
     * Flow of current reading position/locator changes.
     * Emits whenever the user navigates to a new position.
     */
    val currentLocator: Flow<LocatorState>

    /**
     * Flow of double-tap events on sentence elements.
     * Emits when the user double-taps on a sentence in the EPUB content.
     * Used to start audio playback from a specific sentence in ReadAloud books.
     */
    val sentenceDoubleTapEvents: Flow<SentenceDoubleTapEvent>

    /**
     * Enables forwarding taps from addressable sentence elements to [sentenceDoubleTapEvents].
     */
    fun enableSentenceTapDetection() = Unit

    /**
     * Navigates to the next page.
     */
    fun goToNextPage()

    /**
     * Navigates to the previous page.
     */
    fun goToPreviousPage()

    /**
     * Navigates to a specific chapter by its href.
     *
     * @param href The href of the chapter to navigate to
     */
    fun goToChapter(href: String)

    fun goToLocator(locator: LocatorState)

    /**
     * Applies the given reader settings.
     *
     * @param settings The reader settings to apply
     */
    fun setSettings(settings: ReaderSettingsUiModel)

    /**
     * Navigates to a specific position in the publication.
     *
     * @param position The position to navigate to
     */
    fun goToPosition(position: PositionUiModel)

    /**
     * Applies a highlight decoration to the given locator and handles split sentences.
     *
     * For sentences that are split across pages, this method will:
     * 1. Apply the highlight immediately
     * 2. Schedule a page turn after the visible portion has been read
     *
     * @param locator The locator to highlight
     * @param sentenceDurationMs The duration of the sentence in milliseconds (for timing page turns)
     */
    suspend fun applyHighlightWithPageTurn(
        locator: LocatorState,
        sentenceDurationMs: Long,
    )

    /**
     * Checks the visibility of a sentence element on the current page.
     * Used for pre-emptive page turn logic during TTS playback.
     *
     * @param elementId The ID of the sentence element to check
     * @return The visibility result including visible fraction and whether page turn is needed
     */
    suspend fun checkSentenceVisibility(elementId: String): SentenceVisibilityResult

    /**
     * Gets the current chapter info (page position and word count) based on the actual viewport display.
     *
     * Unlike the EPUB position (which is based on fixed 1024-character blocks),
     * this returns the actual displayed page that changes based on font size,
     * margins, and viewport dimensions.
     *
     * Note: This is also included in the [currentLocator] flow emissions, but this
     * method can be used for manual refresh after settings changes when the layout
     * may have changed without a navigation event.
     *
     * @return The current chapter info, or null if it cannot be determined
     */
    suspend fun getChapterPageInfo(): ChapterInfo?

    /**
     * Gets the ID of the first visible sentence element in the current viewport.
     *
     * This is used for precise audio positioning when the user manually navigates
     * to a different page/chapter. By finding the first visible sentence, we can
     * start audio playback from the exact position the user is viewing.
     *
     * Sentence elements are identified by having IDs (e.g., "chapter44.xhtml-sentence50").
     *
     * @return The element ID of the first visible sentence, or null if not found
     */
    suspend fun getVisibleSentenceId(): String?

    /**
     * The text on screen from the first visible word to the last, for
     * recaps. Null when no text is visible: never a chapter-end fallback.
     */
    suspend fun getVisibleTextRange(): VisibleTextRange? = null

    /**
     * Returns whether the current chapter contains readable text without modifying its content.
     */
    suspend fun hasReadableContent(): Boolean = false

    /**
     * Returns the ordered list of sentences for the currently rendered chapter.
     *
     * Used by the on-device TTS pipeline to synthesize and highlight speech without
     * requiring media overlays. Platforms that do not support TTS return an empty list.
     */
    suspend fun getChapterSentences(): List<TtsSentence> = emptyList()

    /**
     * Start of each reading-order item as a fraction (0..1) of the whole book, used to draw
     * chapter ticks on the jump slider. Empty when the platform cannot compute it.
     */
    suspend fun chapterStartProgressions(): List<Double> = emptyList()

    /** Navigates to a fraction (0..1) of the whole book. Returns false when unsupported. */
    suspend fun goToTotalProgression(progression: Double): Boolean = false

    val isSearchable: Boolean get() = false
    val searchIgnoresCaseAndAccents: Boolean get() = false
    fun searchReadingOrder(): List<String> = emptyList()
    suspend fun searchChapterBoundaries(): List<com.retro99.reader.ui.reader.SearchChapterBoundary> = emptyList()

    /** Cold stream; closing collection must cancel native work and release its iterator. */
    fun search(query: String): Flow<ReaderSearchBatch> = kotlinx.coroutines.flow.flow {
        throw com.retro99.reader.ui.reader.BookNotSearchableException()
    }

    fun goToSearchResult(result: ReaderSearchResult) { throw UnsupportedOperationException() }

    /** Separate group from narration decorations. */
    fun decorateSearch(results: List<ReaderSearchResult>, selectedIndex: Int, accent: Int, soft: Int, onAccent: Int, eink: Boolean) = Unit
    fun clearSearchDecorations() = Unit
    suspend fun searchSentenceId(result: ReaderSearchResult): String? = null
}
