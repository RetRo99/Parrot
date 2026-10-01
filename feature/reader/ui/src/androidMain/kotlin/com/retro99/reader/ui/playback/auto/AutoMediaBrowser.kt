package com.retro99.reader.ui.playback.auto

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.github.michaelbull.result.getOrElse
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.reader.domain.usecase.GetCachedReadAloudBooksUseCase
import com.retro99.reader.ui.playback.MAX_EMBEDDED_ARTWORK_BYTES
import com.retro99.reader.ui.playback.setArtworkDataIfSmall
import com.retro99.server.api.ServerTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import org.koin.core.annotation.Provided
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "čič123"

/**
 * Media IDs used for Android Auto browsing.
 */
object AutoMediaIds {
    const val ROOT = "[root]"
    const val BOOKS = "[books]"

    /** Prefix for book media IDs. Format: "book:{serverId}:{uuid}" */
    const val BOOK_PREFIX = "book:"

    fun bookId(serverId: String, uuid: String) = "$BOOK_PREFIX$serverId:$uuid"

    fun parseBookId(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith(BOOK_PREFIX)) return null
        val parts = mediaId.removePrefix(BOOK_PREFIX).split(":", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else null
    }
}

/**
 * Helper class for Android Auto media browsing.
 * Provides MediaItems for the LibraryCallback to return.
 */
@Single
class AutoMediaBrowser(
    @Provided private val getCachedReadAloudBooksUseCase: GetCachedReadAloudBooksUseCase,
    @Provided private val serverTokenProvider: ServerTokenProvider,
) {
    /**
     * Gets the root MediaItem for Android Auto browsing.
     */
    fun getRootItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(AutoMediaIds.ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setTitle("Parrot")
                    .build()
            )
            .build()
    }

    /**
     * Gets children for a given parent media ID.
     * 
     * @param parentId The parent media ID
     * @return List of MediaItems, or null if parentId is not recognized
     */
    suspend fun getChildren(parentId: String): List<MediaItem>? {
        Log.d(TAG, "AutoMediaBrowser.getChildren: parentId=$parentId")
        return when (parentId) {
            AutoMediaIds.ROOT -> getRootChildren()
            AutoMediaIds.BOOKS -> getBookItems()
            else -> {
                // Check if it's a book ID (for chapters)
                AutoMediaIds.parseBookId(parentId)?.let { (serverId, bookUuid) ->
                    // For now, we don't support chapter browsing - books are directly playable
                    // Chapters would require opening the EPUB which is expensive
                    null
                }
            }
        }
    }

    /**
     * Gets the top-level browsing categories.
     */
    private fun getRootChildren(): List<MediaItem> {
        return listOf(
            MediaItem.Builder()
                .setMediaId(AutoMediaIds.BOOKS)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("Downloaded Books")
                        .build()
                )
                .build()
        )
    }

    /**
     * Gets all cached ReadAloud books as playable MediaItems.
     */
    @OptIn(UnstableApi::class)
    private suspend fun getBookItems(): List<MediaItem> {
        val books = getCachedReadAloudBooksUseCase().getOrElse { emptyList() }
        Log.d(TAG, "AutoMediaBrowser.getBookItems: found ${books.size} cached books")

        return books.map { book ->
            val (serverId, uuid, title, author, coverUrl) = getBookInfo(book)

            // Download cover image and embed it directly as byte array.
            // This is necessary because Android Auto's image loader cannot handle
            // authenticated URLs (which require Bearer tokens).
            val artworkData = downloadCoverImage(coverUrl, serverId)
            Log.d(TAG, "AutoMediaBrowser: book '$title' artworkData=${artworkData?.size ?: 0} bytes")

            val metadataBuilder = MediaMetadata.Builder()
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setTitle(title)
                .setArtist(author)

            // Embed artwork data directly if available
            metadataBuilder.setArtworkDataIfSmall(artworkData, TAG)

            MediaItem.Builder()
                .setMediaId(AutoMediaIds.bookId(serverId, uuid))
                .setMediaMetadata(metadataBuilder.build())
                .build()
        }
    }

    /**
     * Downloads a cover image from the server with authentication.
     *
     * Android Auto's built-in image loader cannot handle authenticated URLs,
     * so we download the image ourselves and embed the raw bytes in the MediaMetadata.
     *
     * @param coverUrl The URL of the cover image
     * @param serverId The server ID to get the authentication token for
     * @return The image data as a byte array, or null if download failed
     */
    private suspend fun downloadCoverImage(coverUrl: String?, serverId: String): ByteArray? {
        if (coverUrl == null) return null

        val token = serverTokenProvider.getToken(serverId)

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
                    val contentLength = connection.contentLengthLong
                    if (contentLength > MAX_EMBEDDED_ARTWORK_BYTES) {
                        Log.w(
                            TAG,
                            "AutoMediaBrowser: skipping large cover ($contentLength bytes): $coverUrl",
                        )
                        return@withContext null
                    }
                    val bytes = connection.inputStream.use { it.readBytes() }
                    Log.d(TAG, "AutoMediaBrowser: downloaded cover ($coverUrl): ${bytes.size} bytes")
                    bytes.takeIf { it.size <= MAX_EMBEDDED_ARTWORK_BYTES }
                } else {
                    Log.e(TAG, "AutoMediaBrowser: failed to download cover ($coverUrl): HTTP $responseCode")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "AutoMediaBrowser: error downloading cover ($coverUrl)", e)
                null
            }
        }
    }

    /**
     * Extracts book info for MediaItem creation.
     */
    private fun getBookInfo(book: BookDomainModel): BookInfo {
        return when (book) {
            is BookDomainModel.StorytellerBook -> BookInfo(
                serverId = book.serverId,
                uuid = book.uuid,
                title = book.title,
                author = book.authors.firstOrNull()?.name,
                coverUrl = book.coverUrl,
            )
            is BookDomainModel.LibraryBook -> BookInfo(
                serverId = book.serverId,
                uuid = book.uuid,
                title = book.title,
                author = book.author,
                coverUrl = book.coverUrl,
            )
        }
    }

    private data class BookInfo(
        val serverId: String,
        val uuid: String,
        val title: String,
        val author: String?,
        val coverUrl: String?,
    )
}

