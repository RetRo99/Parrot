package com.retro99.books.ui.detail

import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.BookProgressInfoDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.model.toBookDomainModel
import com.retro99.server.api.ServerBook
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.MediaResourceUiModel
import com.retro99.books.ui.model.toUiModel
import com.retro99.reader.domain.model.DownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookDetailPresentationTest {
    @Test
    fun `download permission and remove permission respect each cloud availability`() {
        // Given
        val cases = listOf("None", "UploadPending", "Uploading", "UploadFailed", "Available")
        cases.forEach { availability ->
            listOf(false, true).forEach { onDevice ->
                val resource = resource(availability, onDevice)
                val book = book(listOf(resource))
                val state = BookDetailViewState(
                    book = book,
                    ebookDownloadState = if (onDevice) DownloadState.Cached else DownloadState.Idle,
                    libraryMediaActions = mapOf(BookType.EBOOK to resource.libraryActions(true)),
                    removableDownloadTypes = if (onDevice && availability == "Available")
                        setOf(BookType.EBOOK) else emptySet(),
                )
                // When
                val media = state.detailMedia().single()
                // Then
                assertEquals(onDevice, media.canOpen)
                assertEquals(!onDevice && availability == "Available", media.canDownload)
                assertEquals(onDevice && availability == "Available", media.canRemove)
                assertEquals(availability in setOf("UploadPending", "Uploading"),
                    media.awaitingUpload)
            }
        }
    }

    @Test
    fun `primary format size is not the sum of all downloads`() {
        // Given
        val ebook = resource("Available", false)
        val readalong = resource("Available", false).copy(mediaType = "readaloud", size = 178L)
        val state = BookDetailViewState(
            book = book(listOf(ebook, readalong)),
            libraryMediaActions = mapOf(
                BookType.EBOOK to ebook.libraryActions(true),
                BookType.READALOUD to readalong.libraryActions(true),
            ),
        )
        // When
        val media = state.detailMedia()
        // Then
        assertEquals(listOf(BookType.EBOOK, BookType.READALOUD), media.map { item -> item.type })
        assertEquals(4L, media.first().size)
        assertEquals(178L, media.last().size)
    }

    @Test
    fun `sub one percent domain conflict is preserved`() {
        // Given
        val domain = BookProgressInfoDomainModel("book", 0.42, 0.421, false, false, false)
        // When
        val ui = domain.toUiModel()
        // Then
        assertTrue(ui.hasConflict)
        assertFalse(domain.copy(remoteProgression = 0.42).toUiModel().hasConflict)
    }

    @Test
    fun `description cleanup and placeholder dates stay supported`() {
        // Given / When / Then
        assertEquals("Hello & goodbye.\n\nNext", plainTextDescription(
            "<p>Hello &amp; <b>goodbye</b>.</p><p>Next</p>",
        ))
        assertEquals(null, formatPublicationDate("0101-01-01"))
        assertEquals("2021", formatPublicationDate("2021-05-04T00:00:00Z"))
    }

    @Test
    fun `all simultaneous transfers are visible without duplicate completed status`() {
        // Given
        val ebook = transfer("ebook", "upload", "transferring")
        val narration = transfer("readaloud", "upload", "pending")
        val failed = transfer("audiobook", "download", "failed")
        // When
        val visible = visibleDetailTransfers(listOf(ebook, narration, failed,
            ebook.copy(transferId = "old", state = "completed"),
            ebook.copy(transferId = "cancelled", state = "cancelled")))
        // Then
        assertEquals(listOf(ebook, narration, failed), visible)
    }

    @Test
    fun `preparing narration cannot open or download and metadata reaches the UI`() {
        // Given
        val server = ServerBook(
            uuid = "server-book",
            serverId = "storyteller",
            title = "Book",
            description = null,
            coverUrl = null,
            authors = emptyList(),
            narrators = listOf("Narrator"),
            series = emptyList(),
            tags = emptyList(),
            hasEbook = true,
            hasAudiobook = false,
            hasReadaloud = true,
            ebookFilepath = "/ebook.epub",
            readaloudFilepath = "/readalong.epub",
            ebookFileSize = 4L,
            language = "en",
            rating = 4.3f,
            audioDurationMs = 60000L,
            readaloudStatus = "processing",
            readaloudStage = "alignment",
            readaloudStageProgress = 0.35,
        )
        val ui = server.toBookDomainModel().toUiModel()
        // When
        val media = BookDetailViewState(book = ui,
            readaloudDownloadState = DownloadState.Cached).detailMedia()
        // Then
        assertTrue(media.last().preparing)
        assertFalse(media.last().canOpen)
        assertFalse(media.last().canDownload)
        assertEquals(listOf("Narrator"), ui.narrators)
        assertEquals("en", ui.language)
        assertEquals(4.3f, ui.rating)
        assertEquals(60000L, ui.audioDurationMs)
        assertEquals(4L, ui.mediaSizes[BookType.EBOOK])
        assertFalse((ui as BookUiModel.StorytellerBook).copy(narrationStatus = "ready")
            .narrationIsPreparing())
    }

    @Test
    fun `comparison preserves the pending format and listen entry mode`() {
        // Given
        val state = BookDetailViewState(pendingOpenBookType = BookType.READALOUD,
            pendingListenMode = true)
        // When
        val comparing = state.beginPositionComparison()
        val returned = comparing.returnFromPositionComparison()
        // Then
        assertTrue(comparing.comparingLinkedPositions)
        assertEquals(BookType.READALOUD, comparing.pendingOpenBookType)
        assertTrue(comparing.pendingListenMode)
        assertEquals(state, returned)
    }

    private fun transfer(media: String, direction: String, state: String) = BookFileTransfer(
        transferId = "$media-$direction",
        serverId = "parrot-cloud",
        libraryBookId = "book",
        direction = direction,
        mediaType = media,
        state = state,
        bytesTransferred = 45L,
        totalBytes = 100L,
        attemptCount = 1,
        lastError = null,
    )

    private fun resource(availability: String, onDevice: Boolean) = MediaResourceUiModel(
        mediaType = "ebook",
        localPath = if (onDevice) "/library/file.epub" else null,
        remoteAvailability = availability,
        size = 4L,
        contentHash = null,
        contentHashAlgorithm = null,
    )

    @Test
    fun `phone summary names a single format and counts only downloaded formats`() {
        // Given
        val single = BookDetailViewState(
            book = book(listOf(resource("Available", true))),
            ebookDownloadState = DownloadState.Cached,
        ).detailMedia()
        // When / Then
        assertEquals(PhoneMediaSummary(BookType.EBOOK, 1, 4L), phoneMediaSummary(single))
        val multiple = single + single.single().copy(type = BookType.READALOUD, size = 178L)
        assertEquals(PhoneMediaSummary(null, 2, 182L), phoneMediaSummary(multiple))
        val downloading = multiple.last().copy(state = DownloadState.Downloading(0.45f))
        assertEquals(PhoneMediaSummary(BookType.EBOOK, 1, 4L),
            phoneMediaSummary(single + downloading))
        assertEquals(null, phoneMediaSummary(single.map { item -> item.copy(size = null) }).size)
    }

    private fun book(resources: List<MediaResourceUiModel>) = BookUiModel.LibraryBook(
        uuid = "book",
        serverId = "local",
        serverType = null,
        title = "Book",
        description = null,
        coverUrl = null,
        author = null,
        publicationDate = null,
        addedAt = "2026-10-01",
        lastOpenedAt = null,
        home = BookHome.ThisDevice,
        mediaResources = resources,
    )
}
