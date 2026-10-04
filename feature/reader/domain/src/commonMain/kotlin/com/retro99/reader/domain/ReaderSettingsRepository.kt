package com.retro99.reader.domain

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for app-wide reader operations.
 * These operations are not server-specific and apply globally.
 *
 * This follows the separation of concerns principle:
 * - Server-specific operations (position sync) use ServerReaderRepository via AuthenticatedRepositoryProvider
 * - App-wide operations (settings, caching, currently reading) use this interface
 */
interface ReaderSettingsRepository {

    /**
     * Prepares an ebook for reading by downloading it if necessary.
     * Returns the local file path that can be used to open the publication.
     *
     * @param bookUuid The UUID of the book to prepare
     * @param ebookFilePath The file path of the ebook on the server
     * @param bookType The type of book (determines the download format query)
     * @return The local file path of the downloaded ebook
     */
    suspend fun prepareEbook(
        bookUuid: String,
        ebookFilePath: String,
        bookType: BookType,
    ): AppResult<String>

    /**
     * Gets the reader settings.
     *
     * @return Flow of reader settings
     */
    fun getReaderSettings(): Flow<ReaderSettingsDomainModel>

    /**
     * Saves the reader settings.
     *
     * @param settings The settings to save
     */
    suspend fun saveReaderSettings(settings: ReaderSettingsDomainModel): CompletableResult

    /**
     * Gets custom fonts imported by the active user.
     */
    fun getCustomFonts(): Flow<List<CustomReaderFontDomainModel>>

    /**
     * Persists the active user's custom reader fonts.
     */
    suspend fun saveCustomFonts(fonts: List<CustomReaderFontDomainModel>): CompletableResult

    /**
     * Checks if an ebook file is cached locally.
     *
     * @param bookUuid The UUID of the book
     * @param bookType The type of book (EBOOK, READALOUD, etc.)
     * @return True if the ebook file exists locally
     */
    suspend fun isEbookCached(bookUuid: String, bookType: BookType): Boolean

    /**
     * Deletes a cached ebook file.
     *
     * @param bookUuid The UUID of the book
     * @param bookType The type of book (EBOOK, READALOUD, etc.)
     * @return True if the file was deleted successfully
     */
    suspend fun deleteEbookCache(bookUuid: String, bookType: BookType): Boolean

    /** The downloaded file of a server book, or null when it isn't on this device. */
    suspend fun getCachedMediaPath(bookUuid: String, bookType: BookType): String? = null

    /**
     * Gets the currently reading book info.
     * This is the last book that was read for at least the minimum required duration.
     *
     * @return The currently reading book info or null if none
     */
    fun getCurrentlyReading(): CurrentlyReadingDomainModel?

    fun observeCurrentlyReading(): Flow<CurrentlyReadingDomainModel?>

    /**
     * Sets the currently reading book.
     * Should only be called when a reading session meets the minimum duration requirement.
     *
     * @param currentlyReading The book info to set as currently reading
     */
    fun setCurrentlyReading(currentlyReading: CurrentlyReadingDomainModel)

    /**
     * Clears the currently reading book.
     */
    fun clearCurrentlyReading()

    /**
     * Gets all locally cached reading positions.
     * Used for displaying progress indicators in book lists.
     *
     * @return List of all cached positions
     */
    suspend fun getAllPositions(): AppResult<List<PositionDomainModel>>
}

