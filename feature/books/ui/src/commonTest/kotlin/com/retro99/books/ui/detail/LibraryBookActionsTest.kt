package com.retro99.books.ui.detail

import com.retro99.books.domain.model.BookHome
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.MediaResourceUiModel
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryBookActionsTest {

    private data class MediaCase(
        val onDevice: Boolean,
        val parrot: String,
        val parrotActive: Boolean,
        val expected: LibraryMediaActions,
    )

    @Test
    fun `media actions follow the detail table`() {
        // Given
        val none = LibraryMediaActions(
            open = false,
            download = false,
            removeDownload = false,
            addToParrot = false,
            retryUpload = false,
        )
        val open = none.copy(open = true)
        val cases = listOf(
            MediaCase(true, "None", true, open.copy(addToParrot = true)),
            MediaCase(true, "None", false, open),
            MediaCase(true, "UploadPending", true, open),
            MediaCase(true, "UploadPending", false, open),
            MediaCase(true, "Uploading", true, open),
            MediaCase(true, "Uploading", false, open),
            MediaCase(true, "Available", true, open.copy(removeDownload = true)),
            MediaCase(true, "Available", false, open.copy(removeDownload = true)),
            MediaCase(true, "UploadFailed", true, open.copy(retryUpload = true)),
            MediaCase(true, "UploadFailed", false, open),
            MediaCase(true, "Deleting", true, open),
            MediaCase(true, "Deleting", false, open),
            MediaCase(false, "Available", true, none.copy(download = true)),
            MediaCase(false, "Available", false, none.copy(download = true)),
            MediaCase(false, "None", true, none),
            MediaCase(false, "UploadPending", true, none),
            MediaCase(false, "Uploading", true, none),
            MediaCase(false, "UploadFailed", true, none),
            MediaCase(false, "Deleting", true, none),
        )

        cases.forEach { case ->
            // When
            val actions = resource(case.onDevice, case.parrot).libraryActions(case.parrotActive)

            // Then
            assertEquals(case.expected, actions, "for $case")
        }
    }

    @Test
    fun `book actions follow the whole book rules`() {
        // Given
        val cases = listOf(
            listOf(resource(true, "None")) to LibraryBookActions(false, true),
            listOf(resource(true, "UploadFailed")) to LibraryBookActions(false, true),
            listOf(resource(true, "Deleting")) to LibraryBookActions(false, true),
            listOf(resource(true, "Available")) to LibraryBookActions(true, false),
            listOf(resource(false, "Available")) to LibraryBookActions(true, false),
            listOf(resource(true, "Uploading")) to LibraryBookActions(false, false),
            listOf(resource(true, "UploadPending")) to LibraryBookActions(false, false),
            listOf(resource(true, "None"), resource(false, "Available", "readaloud")) to
                LibraryBookActions(true, false),
            listOf(resource(false, "UploadFailed")) to LibraryBookActions(false, false),
        )

        cases.forEach { (resources, expected) ->
            // When
            val actions = book(resources).bookActions()

            // Then
            assertEquals(expected, actions, "for $resources")
        }
    }

    private fun resource(onDevice: Boolean, parrot: String, mediaType: String = "ebook") =
        MediaResourceUiModel(
            mediaType = mediaType,
            localPath = if (onDevice) "/library/book_$mediaType.epub" else null,
            remoteAvailability = parrot,
            size = 1,
            contentHash = null,
            contentHashAlgorithm = null,
        )

    private fun book(resources: List<MediaResourceUiModel>) = BookUiModel.LibraryBook(
        uuid = "book",
        serverId = "local",
        serverType = null,
        title = "Book",
        description = null,
        coverUrl = null,
        author = null,
        publicationDate = null,
        addedAt = "2026-10-01T00:00:00Z",
        lastOpenedAt = null,
        home = BookHome.ThisDevice,
        mediaResources = resources,
    )
}
