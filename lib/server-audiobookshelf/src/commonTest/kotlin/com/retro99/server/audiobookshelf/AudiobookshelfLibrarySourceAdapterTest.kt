package com.retro99.server.audiobookshelf

import com.retro99.server.audiobookshelf.model.AudiobookshelfAudioFileApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfBookMetadataApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfEbookFileApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryItemApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfMediaApiModel
import com.retro99.server.audiobookshelf.model.toDomain
import com.retro99.server.api.RemoteFileAvailability
import kotlin.test.Test
import kotlin.test.assertEquals

class AudiobookshelfLibrarySourceAdapterTest {
    @Test
    fun mapsNativeInodesToSeparateMediaResources() {
        val book = AudiobookshelfLibraryItemApiModel(
            id = "item-1",
            media = AudiobookshelfMediaApiModel(
                metadata = AudiobookshelfBookMetadataApiModel(title = "Audio book"),
                ebookFile = AudiobookshelfEbookFileApiModel(
                    ino = "ebook-inode",
                    ebookFormat = "epub",
                    size = 100,
                ),
                audioFiles = listOf(
                    AudiobookshelfAudioFileApiModel(
                        index = 1,
                        ino = "audio-inode-1",
                        mimeType = "audio/mpeg",
                        metadata = null,
                    ),
                    AudiobookshelfAudioFileApiModel(
                        index = 2,
                        ino = "audio-inode-2",
                        mimeType = "audio/mpeg",
                        metadata = null,
                    ),
                ),
            ),
        ).toDomain(serverId = "connection", baseUrl = "https://books.example")

        assertEquals(
            listOf("ebook-inode", "audio-inode-1", "audio-inode-2"),
            book.mediaResources.map { resource -> resource.nativeResourceId },
        )
        assertEquals(
            listOf("ebook", "audiobook", "audiobook"),
            book.mediaResources.map { resource -> resource.mediaType },
        )
        assertEquals(
            List(3) { RemoteFileAvailability.Available },
            book.mediaResources.map { resource -> resource.remoteAvailability },
        )
    }
}
