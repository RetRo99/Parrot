package com.retro99.reader.ui.publication

import android.graphics.Bitmap
import com.retro99.analytics.api.Analytics
import com.retro99.books.domain.model.BookType
import com.retro99.reader.ui.model.TocItemUiModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Checks if this publication has media overlays (audio narration).
 *
 * This function checks three indicators:
 * 1. **Duration metadata**: Must be > 0 (not just non-null), as some EPUBs have duration=0.0 without actual audio
 * 2. **Audio resources**: Presence of audio files in the publication
 * 3. **SMIL resources**: Presence of SMIL files (Synchronized Multimedia Integration Language)
 *
 * Returns true if ANY of these conditions are met.
 *
 * @return true if the publication has media overlays, false otherwise
 */
private fun Publication.hasMediaOverlays(): Boolean {
    val duration = metadata.duration
    val hasDuration = duration != null && duration > 0.0
    val hasAudioResource = resources.any { it.mediaType?.isAudio == true }
    val hasSmilResource = resources.any {
        it.mediaType?.toString()?.contains("smil") == true ||
                it.href.toString().endsWith(".smil")
    }

    return hasDuration || hasAudioResource || hasSmilResource
}

/**
 * Android implementation of EpubPublication.
 * Wraps Readium's Publication object.
 *
 * Note: Reader settings and position are managed separately in [PublicationState],
 * which wraps this class and allows settings/position to be updated via `.copy()`.
 */
actual class EpubPublication(
    internal val publication: Publication,
    actual val serverId: String,
    actual val bookUuid: String,
    actual val bookType: BookType = BookType.EBOOK,
) : KoinComponent {

    private val analytics: Analytics by inject()

    /**
     * Whether this publication has media overlays (audio narration).
     * Uses the extension function from ReadiumPublicationExt.kt for consistent detection logic.
     */
    actual val hasMediaOverlays: Boolean
        get() = publication.hasMediaOverlays()

    actual val language: String?
        get() = publication.metadata.language?.code

    /**
     * The table of contents for this publication.
     * Converts Readium's Link-based TOC to a flat list with level information.
     */
    actual val tableOfContents: List<TocItemUiModel>
        get() = publication.tableOfContents.flatMapIndexed { _, link ->
            link.toTocItems(level = 0)
        }

    private fun Link.toTocItems(level: Int): List<TocItemUiModel> {
        val flattenedChildren = children.flatMap { it.toTocItems(level + 1) }
        val item = TocItemUiModel(
            href = href.toString(),
            title = title ?: href.toString(),
            level = level,
            children = flattenedChildren,
        )
        // Return flat list: this item followed by flattened children
        return listOf(item) + flattenedChildren
    }

    /**
     * Gets the cover image of the publication as a byte array.
     * Uses Readium's Publication.cover() extension function.
     * Returns null if no cover is available or if loading fails.
     *
     * The cover is compressed as PNG for best quality with transparency support.
     */
    suspend fun cover(): ByteArray? {
        return try {
            val bitmap = publication.cover() ?: return null

            // Compress to PNG byte array for Media3 notification
            ByteArrayOutputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.toByteArray()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            analytics.logException(e, "EpubPublication: Failed to load cover for book=$bookUuid")
            null
        }
    }
}

