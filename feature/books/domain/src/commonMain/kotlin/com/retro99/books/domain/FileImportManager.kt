package com.retro99.books.domain

import com.retro99.base.result.AppResult
import io.github.vinceglb.filekit.core.PlatformFile

/**
 * Imports EPUB files into your library.
 */
interface FileImportManager {
    /**
     * Copies the file into app storage, reads its metadata and adds it to your library.
     * A file whose content is already known is attached to the existing book.
     *
     * @return the book the file belongs to, new or existing
     */
    suspend fun importEpubFile(platformFile: PlatformFile): AppResult<ImportedBookFile>
}

/** The library book an imported file was added to, and the file's media type. */
data class ImportedBookFile(
    val libraryBookId: String,
    val mediaType: String,
)
