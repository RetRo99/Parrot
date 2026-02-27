package com.retro99.reader.ui.playback.auto

import android.util.Log
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.github.michaelbull.result.getOrElse
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.usecase.GetReadaloudBooksUseCase
import com.retro99.server.api.ServerTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided
import java.net.HttpURLConnection
import java.net.URL

/**
 * Provides media items for Android Auto browsing.
 * Fetches read-aloud books and converts them to MediaItems.
 */
@Factory
class BooksMediaProvider(
    @Provided private val getReadaloudBooksUseCase: GetReadaloudBooksUseCase,
    @Provided private val serverTokenProvider: ServerTokenProvider,
) {
    companion object {
        private const val TAG = "BooksMediaProvider"

        // Media IDs for content hierarchy
        const val ROOT_ID = "root"
        const val READALOUD_BOOKS_ID = "readaloud_books"

        // Extra keys for MediaItem bundles
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_BOOK_UUID = "book_uuid"
        const val EXTRA_BOOK_TYPE = "book_type"
    }

    /**
     * Returns the root browsable items (categories).
     */
    fun getRootItems(): List<MediaItem> {
        return listOf(
            MediaItem.Builder()
                .setMediaId(READALOUD_BOOKS_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Read-Aloud Books")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                        .build()
                )
                .build()
        )
    }

    /**
     * Fetches all read-aloud books and converts them to MediaItems.
     */
    suspend fun getReadaloudBooks(): List<MediaItem> {
        val booksResult = getReadaloudBooksUseCase().first()

        val books = booksResult.getOrElse { emptyList() }
        return books.map { book -> bookToMediaItem(book) }
    }

    /**
     * Downloads the cover image and returns it as a byte array.
     * This is needed for Android Auto since it can't handle authenticated URLs in browse view.
     * By embedding the image data directly, we bypass Android Auto's image loader.
     */
    private suspend fun downloadCoverImage(book: BookDomainModel): ByteArray? {
        val coverUrl = book.coverUrl
        if (coverUrl == null) {
            Log.w(TAG, "Book ${book.title} has null coverUrl")
            return null
        }

        val token = serverTokenProvider.getToken(book.serverId)

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(coverUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.requestMethod = "GET"

                // Add authentication header if token is available
                if (token != null) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    Log.d(TAG, "Downloaded cover for '${book.title}': ${bytes.size} bytes")
                    bytes
                } else {
                    Log.e(TAG, "Failed to download cover for '${book.title}': HTTP $responseCode")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading cover for '${book.title}'", e)
                null
            }
        }
    }

    private suspend fun bookToMediaItem(book: BookDomainModel): MediaItem {
        val authors = when (book) {
            is BookDomainModel.StorytellerBook ->
                book.authors.joinToString(", ") { it.name }

            is BookDomainModel.LocalBook ->
                book.author ?: "Unknown Author"
        }

        // Download cover image and embed it directly as byte array
        // This bypasses Android Auto's image loader which can't handle authenticated URLs
        val artworkData = downloadCoverImage(book)
        Log.d(TAG, "MediaItem '${book.title}' artworkData: ${artworkData?.size ?: 0} bytes")

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(authors)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setExtras(
                bundleOf(
                    EXTRA_SERVER_ID to book.serverId,
                    EXTRA_BOOK_UUID to book.uuid,
                    EXTRA_BOOK_TYPE to BookType.READALOUD.value
                )
            )

        // Set artwork data if available
        if (artworkData != null) {
            metadataBuilder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        return MediaItem.Builder()
            .setMediaId("book:${book.serverId}:${book.uuid}")
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }
}

