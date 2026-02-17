package com.retro99.reader.data

import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel

/**
 * Manager for importing EPUB files into the app.
 * Handles copying files to app storage, extracting metadata, and saving to database.
 */
interface FileImportManager {
    /**
     * Imports an EPUB file from the given bytes.
     *
     * This method:
     * 1. Writes the file bytes to app's internal storage
     * 2. Extracts metadata (title, author, cover) from the EPUB
     * 3. Saves the cover image to storage
     * 4. Creates and saves a LocalBook to the database
     *
     * @param fileBytes The raw bytes of the EPUB file (from file picker)
     * @param fileName The original file name
     * @return The imported book domain model, or an error if import fails
     */
    suspend fun importEpubFile(
        fileBytes: ByteArray,
        fileName: String,
    ): AppResult<BookDomainModel.LocalBook>

    /**
     * Gets the local file path for an imported book.
     *
     * @param uuid The UUID of the imported book
     * @return The local file path, or null if not found
     */
    fun getImportedBookPath(uuid: String): String?

    /**
     * Deletes an imported book's files from storage.
     *
     * @param uuid The UUID of the imported book
     * @return True if deletion was successful
     */
    fun deleteImportedBookFiles(uuid: String): Boolean
}

