package com.retro99.books.ui.model

import com.retro99.base.server.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudBackupSelectionTest {
    @Test
    fun `selection candidates group imported EPUB formats and exclude cloud or active books`() {
        val candidates = listOf(
            localBook(
                "multi",
                resource("ebook", "import", size = 4_000_000),
                resource("readaloud", "import", size = 1_000_000),
            ),
            localBook("cloud", resource("ebook", "import", "Available", 3_000_000)),
            localBook("pending", resource("ebook", "import", "UploadPending", 3_000_000)),
            localBook("server-download", resource("ebook", "cloud_download", size = 3_000_000)),
            localBook("audiobook", resource("audiobook", "import", size = 3_000_000)),
            localBook("active", resource("ebook", "import", size = 3_000_000)),
        ).cloudBackupBooks(uploadingBookIds = setOf("active"))

        assertEquals(listOf("multi"), candidates.map(CloudBackupBook::id))
        assertEquals(listOf("ebook", "readaloud"), candidates.single().mediaTypes)
        assertEquals(5_000_000L, candidates.single().sizeBytes)

        val uploadTargets = listOf(
            localBook("cloud", resource("ebook", "import", "Available", 3_000_000)),
            localBook("pending", resource("ebook", "import", "UploadPending", 3_000_000)),
            localBook("download", resource("ebook", "cloud_download", size = 3_000_000)),
        ).cloudBackupUploadBookIds()
        assertEquals(setOf("cloud", "pending"), uploadTargets)
    }

    @Test
    fun `storage labels use decimal units and omit unnecessary decimal zero`() {
        assertEquals("14 MB", cloudStorageLabel(14_000_000))
        assertEquals("4.2 MB", cloudStorageLabel(4_200_000))
        assertEquals("3.8 GB", cloudStorageLabel(3_800_000_000))
    }

    private fun localBook(id: String, vararg resources: MediaResourceUiModel) = BookUiModel.LibraryBook(
        uuid = id,
        serverId = "local",
        serverType = ServerType.Local,
        title = "Book $id",
        description = null,
        coverUrl = null,
        author = null,
        publicationDate = null,
        addedAt = "2026-10-01T00:00:00Z",
        lastOpenedAt = null,
        home = com.retro99.books.domain.model.BookHome.ThisDevice,
        mediaResources = resources.toList(),
    )

    private fun resource(
        mediaType: String,
        origin: String,
        remoteAvailability: String = "None",
        size: Long,
    ) = MediaResourceUiModel(
        mediaType = mediaType,
        localPath = "/library/file.epub",
        remoteAvailability = remoteAvailability,
        size = size,
        contentHash = null,
        contentHashAlgorithm = null,
        localOrigin = origin,
    )
}
