package com.retro99.reader.ui.playback.auto

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.github.michaelbull.result.getOrElse
import com.retro99.analytics.api.Analytics
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.usecase.GetBookByUuidUseCase
import com.retro99.reader.data.source.EbookFileDownloader
import com.retro99.reader.domain.usecase.SaveReadingProgressUseCase
import com.retro99.reader.ui.media.DynamicPublicationDataSourceFactory
import com.retro99.reader.ui.media.HeadlessMediaOverlayPlayer
import com.retro99.reader.ui.media.smil.SmilChapterIndex
import com.retro99.reader.ui.media.smil.SmilClipCache
import com.retro99.reader.ui.media.smil.SmilLoadingManager
import com.retro99.reader.ui.media.smil.SmilParser
import com.retro99.reader.ui.media.smil.SmilQuickScanner
import com.retro99.reader.ui.service.EpubPublicationService
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "HeadlessSessionFactory"

/** Seek increment in milliseconds (10 seconds) */
private const val SEEK_INCREMENT_MS = 10_000L

/**
 * Metadata for the currently playing book, used for notifications and Android Auto display.
 */
data class BookMetadata(
    val title: String,
    val author: String,
    val coverArtwork: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BookMetadata) return false
        return title == other.title && author == other.author && coverArtwork.contentEquals(other.coverArtwork)
    }
    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + (coverArtwork?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Factory for creating [HeadlessPlaybackSession] instances.
 *
 * This factory:
 * 1. Opens the publication
 * 2. Loads saved position from server
 * 3. Fetches book metadata for notifications/Android Auto display
 * 4. Creates audio components (ExoPlayer, MediaOverlayPlayer, SmilLoadingManager)
 * 5. Returns a configured HeadlessPlaybackSession
 *
 * Uses @Factory annotation for Koin - creates a new instance each time,
 * avoiding ReaderScope dependency conflicts.
 */
@Factory
class HeadlessSessionFactory(
    @Provided private val context: Context,
    @Provided private val publicationService: EpubPublicationService,
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val saveReadingProgressUseCase: SaveReadingProgressUseCase,
    @Provided private val analytics: Analytics,
    @Provided private val ebookFileDownloader: EbookFileDownloader,
    @Provided private val smilParser: SmilParser,
    @Provided private val smilQuickScanner: SmilQuickScanner,
    @Provided private val getBookByUuidUseCase: GetBookByUuidUseCase,
    @Provided private val serverTokenProvider: ServerTokenProvider,
) {
    /**
     * Creates a new headless playback session for the given book.
     *
     * @param serverId The server ID the book belongs to
     * @param bookUuid The unique identifier of the book
     * @param exoPlayer The ExoPlayer to use for playback - should be the same player
     *                  that is connected to the MediaLibrarySession so Android Auto
     *                  can observe playback state
     * @param dynamicDataSourceFactory Optional factory to update with this book's Publication.
     *                                 When provided, allows ExoPlayer to read audio from the EPUB
     *                                 container when MediaSession calls setMediaItems().
     * @return A configured HeadlessPlaybackSession, or null if creation fails
     */
    @OptIn(UnstableApi::class)
    suspend fun createSession(
        serverId: String,
        bookUuid: String,
        exoPlayer: ExoPlayer,
        dynamicDataSourceFactory: DynamicPublicationDataSourceFactory? = null,
    ): HeadlessPlaybackSession? {
        val totalStart = System.currentTimeMillis()
        Log.d(TAG, "    ┌─── createSession BEGIN ───")
        Log.d(TAG, "    │ book=$bookUuid")

        // 1. Get the local file path (book must already be downloaded)
        Log.d(TAG, "    │ [+0ms] Step 1: Getting cached path...")
        val filePath = ebookFileDownloader.getCachedEbookPath(bookUuid, BookType.READALOUD)
            ?: run {
                Log.e(TAG, "    │ ERROR: Book not downloaded!")
                Log.d(TAG, "    └─── createSession END (FAILED) ───")
                analytics.logException(
                    IllegalStateException("Book not downloaded: $bookUuid"),
                    "HeadlessSessionFactory: Book not cached for headless playback"
                )
                return null
            }
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 1 done: $filePath")

        // 2. Open publication
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 2: Opening publication...")
        val pubStart = System.currentTimeMillis()
        val publication = publicationService.openPublication(
            filePath = filePath,
            serverId = serverId,
            bookUuid = bookUuid,
            bookType = BookType.READALOUD,
        ).getOrElse { error ->
            Log.e(TAG, "    │ ERROR: Failed to open: ${error.message}")
            Log.d(TAG, "    └─── createSession END (FAILED) ───")
            analytics.logException(
                Exception("Failed to open publication: ${error.message}"),
                "HeadlessSessionFactory: Failed to open publication for book=$bookUuid"
            )
            return null
        }
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 2 done (${System.currentTimeMillis() - pubStart}ms)")

        // 2.5. Update the dynamic data source factory with this book's Publication
        // This allows ExoPlayer to read audio from the EPUB when MediaSession calls setMediaItems()
        dynamicDataSourceFactory?.setPublication(publication.publication)
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 2.5: Updated dynamic data source factory")

        // 3. Load saved position
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 3: Loading position...")
        val posStart = System.currentTimeMillis()
        val readerRepository = repositoryProvider.getReaderRepository(serverId)
        val savedPosition = readerRepository?.getPosition(bookUuid)?.getOrElse { null }
        val initialChapterHref = savedPosition?.locatorHref
        val initialPositionMs = savedPosition?.audioTimestampMs
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 3 done (${System.currentTimeMillis() - posStart}ms) chapter=$initialChapterHref posMs=$initialPositionMs")

        // 3.5. Fetch book metadata (async, don't block if it fails)
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 3.5: Fetching book metadata...")
        val metadataStart = System.currentTimeMillis()
        val bookMetadata = fetchBookMetadata(serverId, bookUuid)
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 3.5 done (${System.currentTimeMillis() - metadataStart}ms) title=${bookMetadata?.title}")

        // 4. Create SMIL loading manager
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 4: Creating SMIL manager...")
        val smilContentProvider = HeadlessSmilContentProvider(publication, analytics)
        val smilIndex = SmilChapterIndex()
        val smilCache = SmilClipCache()
        val smilLoadingManager = SmilLoadingManager(
            smilParser = smilParser,
            quickScanner = smilQuickScanner,
            analytics = analytics,
            index = smilIndex,
            cache = smilCache,
            contentProvider = smilContentProvider,
        )
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 4 done")

        // 5. Create MediaOverlayPlayer
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 5: Creating player...")
        val player = HeadlessMediaOverlayPlayer(
            epubPublication = publication,
            analytics = analytics,
            smilLoadingManager = smilLoadingManager,
            exoPlayer = exoPlayer,
            bookMetadata = bookMetadata,
        )
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 5 done")

        // 6. Initialize player (builds SMIL index)
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 6: Initializing player...")
        val initStart = System.currentTimeMillis()
        player.initialize(initialChapterHref)
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 6 done (${System.currentTimeMillis() - initStart}ms)")

        // 7. Create session
        Log.d(TAG, "    │ [+${System.currentTimeMillis() - totalStart}ms] Step 7: Creating session...")
        val session = HeadlessPlaybackSession(
            serverId = serverId,
            bookUuid = bookUuid,
            publication = publication,
            player = player,
            smilLoadingManager = smilLoadingManager,
            saveProgressUseCase = saveReadingProgressUseCase,
            analytics = analytics,
            exoPlayer = exoPlayer,
            initialChapterHref = initialChapterHref,
            initialPositionMs = initialPositionMs,
        )

        Log.d(TAG, "    └─── createSession END total=${System.currentTimeMillis() - totalStart}ms ───")
        return session
    }

    /**
     * Fetches book metadata for display in notifications and Android Auto.
     */
    private suspend fun fetchBookMetadata(serverId: String, bookUuid: String): BookMetadata? {
        return try {
            val bookResult = getBookByUuidUseCase(serverId, bookUuid).first()
            val book = bookResult.getOrElse { return null }

            val author = when (book) {
                is BookDomainModel.StorytellerBook -> book.authors.joinToString(", ") { it.name }
                is BookDomainModel.LocalBook -> book.author ?: "Unknown Author"
            }

            // Download cover image
            val coverArtwork = downloadCoverImage(book)

            BookMetadata(
                title = book.title,
                author = author,
                coverArtwork = coverArtwork,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch book metadata", e)
            null
        }
    }

    /**
     * Downloads cover image for the book.
     */
    private suspend fun downloadCoverImage(book: BookDomainModel): ByteArray? {
        val coverUrl = book.coverUrl ?: return null
        val token = serverTokenProvider.getToken(book.serverId)

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(coverUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.requestMethod = "GET"

                if (token != null) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.use { it.readBytes() }
                } else {
                    Log.w(TAG, "Failed to download cover: HTTP $responseCode")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error downloading cover", e)
                null
            }
        }
    }
}

