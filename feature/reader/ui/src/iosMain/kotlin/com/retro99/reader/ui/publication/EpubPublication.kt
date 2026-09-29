package com.retro99.reader.ui.publication

import com.retro99.books.domain.model.BookType
import com.retro99.reader.ui.bridge.EpubReaderBridge
import com.retro99.reader.ui.model.TocItemUiModel

/**
 * iOS implementation of EpubPublication.
 * Wraps the EpubReaderBridge which provides access to the Swift Readium implementation.
 *
 * Note: Reader settings and position are managed separately in [PublicationState],
 * which wraps this class and allows settings/position to be updated via `.copy()`.
 */
actual class EpubPublication(
    internal val bridge: EpubReaderBridge,
    actual val serverId: String,
    actual val bookUuid: String,
    actual val bookType: BookType = BookType.EBOOK,
) {

    /**
     * Whether this publication has media overlays (audio narration).
     */
    actual val hasMediaOverlays: Boolean
        get() = bridge.hasMediaOverlays()

    /** The Swift bridge does not expose the language yet; the sheet falls back to the phone's. */
    actual val language: String? = null

    /**
     * The table of contents for this publication.
     * Delegates to the Swift bridge to get the TOC from Readium.
     */
    actual val tableOfContents: List<TocItemUiModel>
        get() = bridge.getTableOfContents().map { tocItem ->
            TocItemUiModel(
                href = tocItem.href,
                title = tocItem.title,
                level = tocItem.level,
                children = emptyList(), // Flat list, children are separate entries
            )
        }
}
