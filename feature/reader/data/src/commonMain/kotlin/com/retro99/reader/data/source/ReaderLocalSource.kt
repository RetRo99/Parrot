package com.retro99.reader.data.source

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.model.BookType
import com.retro99.reader.data.model.CustomReaderFontLocalModel
import com.retro99.reader.data.model.PositionLocalModel
import com.retro99.reader.data.model.ReaderSettingsLocalModel
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import kotlinx.coroutines.flow.Flow

/**
 * Local source interface for reader operations.
 * Handles reading progress, settings, and file caching.
 *
 * Note: Publication opening/closing is handled by [EpubReaderController] in the UI layer,
 * as it requires platform-specific objects that cannot cross layer boundaries.
 */
interface ReaderLocalSource {

    /**
     * Gets the reading progress for a book.
     *
     * @param bookUuid The UUID of the book
     * @return The reading progress or null if not found
     */
    suspend fun getReadingProgress(bookUuid: String): AppResult<PositionLocalModel?>

    /**
     * Saves the reading progress for a book.
     *
     * @param progress The reading progress to save
     */
    suspend fun saveReadingProgress(progress: PositionLocalModel): CompletableResult

    /**
     * Gets the reader settings.
     *
     * @return Flow of reader settings
     */
    fun getReaderSettings(): Flow<ReaderSettingsLocalModel>

    /**
     * Saves the reader settings.
     *
     * @param settings The settings to save
     */
    suspend fun saveReaderSettings(settings: ReaderSettingsLocalModel): CompletableResult

    fun getCustomFonts(): Flow<List<CustomReaderFontLocalModel>>

    suspend fun saveCustomFonts(fonts: List<CustomReaderFontLocalModel>): CompletableResult

    /**
     * Checks if an ebook file exists locally.
     *
     * @param bookUuid The UUID of the book
     * @param bookType The type of book
     * @return True if the file exists locally
     */
    suspend fun isEbookCached(bookUuid: String, bookType: BookType): Boolean

    /**
     * Gets the local file path for a cached ebook.
     *
     * @param bookUuid The UUID of the book
     * @param bookType The type of book
     * @return The local file path or null if not cached
     */
    suspend fun getCachedEbookPath(bookUuid: String, bookType: BookType): String?

    /**
     * Deletes a cached ebook file.
     *
     * @param bookUuid The UUID of the book
     * @param bookType The type of book
     * @return True if the file was deleted successfully
     */
    suspend fun deleteEbookCache(bookUuid: String, bookType: BookType): Boolean

    /**
     * Gets the currently reading book info.
     *
     * @return The currently reading book info or null if none
     */
    fun getCurrentlyReading(): CurrentlyReadingDomainModel?
    fun observeCurrentlyReading(): Flow<CurrentlyReadingDomainModel?>

    /**
     * Sets the currently reading book.
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
     *
     * @return List of all cached positions
     */
    suspend fun getAllPositions(): AppResult<List<PositionLocalModel>>

}

