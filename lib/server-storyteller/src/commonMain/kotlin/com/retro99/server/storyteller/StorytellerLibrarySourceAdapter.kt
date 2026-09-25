package com.retro99.server.storyteller

import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibrarySourceAdapter
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceSnapshotStatus
import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerBook
import com.retro99.server.api.library.ServerBookLibrarySourceAdapter
import org.koin.core.annotation.Single

@Single(binds = [LibrarySourceAdapter::class])
class StorytellerLibrarySourceAdapter : ServerBookLibrarySourceAdapter() {
    override val adapterId = LibraryAdapterId("storyteller")

    override fun snapshot(
        source: SourceBookRef,
        book: ServerBook,
        status: SourceSnapshotStatus,
    ): SourceBookSnapshot {
        val resources = if (book.mediaResources.isNotEmpty()) {
            book.mediaResources
        } else {
            buildList {
                addResource("ebook", book.ebookFilepath, book.ebookFileSize)
                addResource("audiobook", book.audiobookFilepath, book.audiobookFileSize)
                addResource("readaloud", book.readaloudFilepath, book.readaloudFileSize)
            }
        }
        return super.snapshot(source, book.copy(mediaResources = resources), status)
    }

    private fun MutableList<MediaResource>.addResource(
        mediaType: String,
        resourcePath: String?,
        sizeBytes: Long?,
    ) {
        if (resourcePath == null) return
        add(
            MediaResource(
                mediaType = mediaType,
                remoteAvailability = RemoteFileAvailability.Available,
                size = sizeBytes,
                nativeResourceId = resourcePath,
            ),
        )
    }
}
