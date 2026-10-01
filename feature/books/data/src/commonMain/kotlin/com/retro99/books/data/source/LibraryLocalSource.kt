package com.retro99.books.data.source

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.data.EpubMetadata
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.library.DeviceFileEntity
import kotlinx.coroutines.flow.Flow

/** Your library as stored on this device: books, their device files and Parrot files. */
interface LibraryLocalSource {
    /** Adds an imported file. Returns the book it belongs to (new or existing). */
    suspend fun addImportedFile(file: ImportedFileCandidate): AppResult<String>

    fun observeLibrary(): Flow<List<LibraryBookRecord>>

    suspend fun getLibraryBook(libraryBookId: String): LibraryBookRecord?

    suspend fun deleteBookFromDevice(libraryBookId: String): CompletableResult
}

/** A file copied into app storage and read, but not yet part of the library. */
data class ImportedFileCandidate(
    val stagedPath: String,
    val mediaType: String,
    val fileSize: Long,
    val contentHash: String,
    val contentHashAlgorithm: String,
    val metadata: EpubMetadata,
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
)
