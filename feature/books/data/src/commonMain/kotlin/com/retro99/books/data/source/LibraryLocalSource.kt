package com.retro99.books.data.source

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.github.michaelbull.result.map
import com.retro99.books.data.EpubMetadata
import com.retro99.books.domain.BookFileProvenance
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.library.DeviceFileEntity
import kotlinx.coroutines.flow.Flow

/** Your library as stored on this device: books, their device files and Parrot files. */
interface LibraryLocalSource {
    /**
     * Adds a staged file to the library. Returns the book it belongs to and whether the
     * book is new.
     */
    suspend fun addStagedFile(file: ImportedFileCandidate): AppResult<AddedLibraryFile>

    /** Adds an imported file. Returns the book it belongs to (new or existing). */
    suspend fun addImportedFile(file: ImportedFileCandidate): AppResult<String> =
        addStagedFile(file).map { added -> added.libraryBookId }

    /**
     * Settles imports a dead process left half-done: an import whose rows were written is
     * kept, any other has its library file and cover removed. Safe to call at any time.
     */
    suspend fun reconcileInterruptedImports()

    /** The book that has a file on this device with exactly this content, if any. */
    suspend fun findBookWithDeviceFile(algorithm: String, hash: String): String?

    fun observeLibrary(): Flow<List<LibraryBookRecord>>

    suspend fun getLibraryBook(libraryBookId: String): LibraryBookRecord?

    suspend fun deleteBookFromDevice(libraryBookId: String): CompletableResult
}

/**
 * A file copied into app storage and read, but not yet part of the library.
 *
 * @param origin one of the `DeviceFileEntity.ORIGIN_*` values
 */
data class ImportedFileCandidate(
    val stagedPath: String,
    val mediaType: String,
    val fileSize: Long,
    val contentHash: String,
    val contentHashAlgorithm: String,
    val metadata: EpubMetadata,
    val origin: String = DeviceFileEntity.ORIGIN_IMPORT,
    val provenance: BookFileProvenance? = null,
)

/** The book a staged file ended up in. */
data class AddedLibraryFile(
    val libraryBookId: String,
    val isNewBook: Boolean,
)

/** A library book and its copies, straight from the database. */
data class LibraryBookRecord(
    val libraryBookId: String,
    val title: String,
    val author: String?,
    val description: String?,
    val coverPath: String?,
    val publicationDate: String?,
    val addedAt: String,
    val lastOpenedAt: String?,
    val deviceFiles: List<DeviceFileEntity>,
    val parrotFiles: List<CloudBookFileEntity>,
    val isbn: String? = null,
)
