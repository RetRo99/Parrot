package com.retro99.server.local

import com.retro99.books.data.source.LibraryBookRecord
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.server.api.RemoteFileAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalBooksRepositoryTest {

    private data class Case(
        val name: String,
        val record: LibraryBookRecord,
        val parrotActive: Boolean,
        val expectedVisible: Boolean,
        val expectedAvailability: RemoteFileAvailability? = null,
        val expectedLocalPath: String? = null,
    )

    @Test
    fun `a library book carries the isbn of its record`() {
        // Given
        val record = record(device = deviceFile()).copy(isbn = "9780261102217")

        // When
        val book = record.toLibraryServerBook(serverId = "local", parrotActive = false)

        // Then
        assertEquals("9780261102217", book?.isbn)
    }

    @Test
    fun `library books are listed by where their copies are`() {
        // Given
        val cases = listOf(
            Case(
                name = "device only",
                record = record(device = deviceFile()),
                parrotActive = true,
                expectedVisible = true,
                expectedAvailability = RemoteFileAvailability.None,
                expectedLocalPath = DEVICE_PATH,
            ),
            Case(
                name = "Parrot Available only, active",
                record = record(parrot = parrotFile("available")),
                parrotActive = true,
                expectedVisible = true,
                expectedAvailability = RemoteFileAvailability.Available,
            ),
            Case(
                name = "Parrot Available only, inactive",
                record = record(parrot = parrotFile("available")),
                parrotActive = false,
                expectedVisible = false,
            ),
            Case(
                name = "device plus Parrot",
                record = record(device = deviceFile(), parrot = parrotFile("available")),
                parrotActive = true,
                expectedVisible = true,
                expectedAvailability = RemoteFileAvailability.Available,
                expectedLocalPath = DEVICE_PATH,
            ),
            Case(
                name = "device plus Parrot, inactive",
                record = record(device = deviceFile(), parrot = parrotFile("available")),
                parrotActive = false,
                expectedVisible = true,
                expectedAvailability = RemoteFileAvailability.None,
                expectedLocalPath = DEVICE_PATH,
            ),
            Case(
                name = "Parrot progress only, no files",
                record = record(),
                parrotActive = true,
                expectedVisible = false,
            ),
            Case(
                name = "Parrot UploadFailed, no device copy",
                record = record(parrot = parrotFile("upload_failed")),
                parrotActive = true,
                expectedVisible = false,
            ),
        )

        cases.forEach { case ->
            // When
            val book = case.record.toLibraryServerBook(serverId = "local", parrotActive = case.parrotActive)

            // Then
            if (!case.expectedVisible) {
                assertNull(book, case.name)
                return@forEach
            }
            val listed = requireNotNull(book) { case.name }
            assertEquals(BOOK_ID, listed.uuid, case.name)
            assertEquals(BOOK_ID, listed.libraryBookId, case.name)
            val resource = listed.mediaResources.single()
            assertEquals(case.expectedAvailability, resource.remoteAvailability, case.name)
            assertEquals(case.expectedLocalPath, resource.localPath, case.name)
        }
    }

    private fun record(
        device: DeviceFileEntity? = null,
        parrot: CloudBookFileEntity? = null,
    ) = LibraryBookRecord(
        libraryBookId = BOOK_ID,
        title = "Book",
        author = null,
        description = null,
        coverPath = null,
        publicationDate = null,
        addedAt = "2026-10-01T00:00:00Z",
        lastOpenedAt = null,
        deviceFiles = listOfNotNull(device),
        parrotFiles = listOfNotNull(parrot),
    )

    private fun deviceFile() = DeviceFileEntity(
        libraryBookId = BOOK_ID,
        mediaType = "ebook",
        filePath = DEVICE_PATH,
        fileSize = 10,
        contentHash = "hash",
        contentHashAlgorithm = "sha-256-v1",
        origin = DeviceFileEntity.ORIGIN_IMPORT,
        addedAt = "2026-10-01T00:00:00Z",
    )

    private fun parrotFile(status: String) = CloudBookFileEntity(
        libraryBookId = BOOK_ID,
        cloudBookFileId = "parrot-file",
        mediaType = "ebook",
        relativePath = "",
        fileName = "book.epub",
        status = status,
        sizeBytes = 10,
        contentHash = "hash",
        contentHashAlgorithm = "sha-256-v1",
        remoteRevision = 1,
        updatedAt = "now",
    )

    private companion object {
        const val BOOK_ID = "55555555-5555-4555-8555-555555555555"
        const val DEVICE_PATH = "/library/book_ebook.epub"
    }
}
