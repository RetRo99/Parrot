package com.retro99.reader.ui.playback.auto

import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.github.michaelbull.result.getOrElse
import com.retro99.analytics.api.Analytics
import com.retro99.books.domain.model.BookType
import com.retro99.epub.implementation.smil.SmilParser
import com.retro99.reader.data.source.EbookFileDownloader
import com.retro99.reader.domain.usecase.PropagateToLinkedCopiesUseCase
import com.retro99.reader.domain.usecase.SaveReadingProgressUseCase
import com.retro99.reader.ui.media.DynamicPublicationDataSourceFactory
import com.retro99.reader.ui.media.HeadlessBookMetadata
import com.retro99.reader.ui.media.HeadlessMediaOverlayPlayer
import com.retro99.reader.ui.media.PublicationSmilContentProvider
import com.retro99.reader.ui.media.smil.SmilChapterIndex
import com.retro99.reader.ui.media.smil.SmilClipCache
import com.retro99.reader.ui.media.smil.SmilClipRepository
import com.retro99.reader.ui.media.smil.SmilLoadingManager
import com.retro99.reader.ui.media.smil.SmilQuickScanner
import com.retro99.reader.ui.playback.MAX_EMBEDDED_ARTWORK_BYTES
import com.retro99.reader.ui.service.EpubPublicationService
import com.retro99.sync.domain.usecase.SyncNowUseCase
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "čič123"

/**
 * Factory for creating [HeadlessPlaybackSession] instances.
 *
 * This factory handles all the setup required for headless (Android Auto) playback:
 * 1. Download the ebook if not present locally
 * 2. Open the EPUB publication
 * 3. Load saved reading position
 * 4. Create SMIL loading components
 * 5. Initialize the playback session
 *
 * Registered as @Single since it's stateless and can be shared.
 */
@Single
class HeadlessSessionFactory(
    @Provided private val publicationService: EpubPublicationService,
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val ebookFileDownloader: EbookFileDownloader,
    @Provided private val saveProgressUseCase: SaveReadingProgressUseCase,
    @Provided private val propagateToLinkedCopiesUseCase: PropagateToLinkedCopiesUseCase,
    @Provided private val syncNowUseCase: SyncNowUseCase,
    @Provided private val smilParser: SmilParser,
    @Provided private val quickScanner: SmilQuickScanner,
    @Provided private val clipRepository: SmilClipRepository,
    @Provided private val analytics: Analytics,
    @Provided private val dataSourceFactory: DynamicPublicationDataSourceFactory,
    @Provided private val serverTokenProvider: ServerTokenProvider,
) {

    /**
     * Creates a headless playback session for the specified book.
     *
     * @param serverId The server ID
     * @param bookUuid The book UUID
     * @param exoPlayer The ExoPlayer instance from MediaPlaybackService
     * @return A configured HeadlessPlaybackSession, or null if creation failed
     */
    suspend fun createSession(
        serverId: String,
        bookUuid: String,
        exoPlayer: ExoPlayer,
    ): HeadlessPlaybackSession? {
        Log.d(TAG, "HeadlessSessionFactory: Creating session for book=$bookUuid")

        // 1. Get book info and ebook path
        val booksRepo = repositoryProvider.getBooksRepository(serverId)
        if (booksRepo == null) {
            Log.e(TAG, "HeadlessSessionFactory: Server not found: $serverId")
            return null
        }

        val book = booksRepo.getBook(bookUuid).first().getOrElse { null }
        if (book == null) {
            Log.e(TAG, "HeadlessSessionFactory: Book not found: $bookUuid")
            return null
        }

        // 2. Get or download the ebook file (prefer readaloud since we're doing audio playback)
        var localEbookPath = ebookFileDownloader.getCachedEbookPath(bookUuid, BookType.READALOUD)
        if (localEbookPath == null) {
            Log.d(TAG, "HeadlessSessionFactory: Downloading ebook for $bookUuid")
            val downloadResult = ebookFileDownloader.downloadEbook(
                ebookFilePath = book.readaloudFilepath ?: book.ebookFilepath ?: "",
                bookUuid = bookUuid,
                bookType = BookType.READALOUD,
                serverId = serverId,
            )
            localEbookPath = downloadResult.getOrElse { null }
            if (localEbookPath == null) {
                Log.e(TAG, "HeadlessSessionFactory: Failed to download ebook")
                return null
            }
        }

        // 3. Open the EPUB publication
        Log.d(TAG, "HeadlessSessionFactory: Opening publication at $localEbookPath")
        val publication = publicationService.openPublication(
            filePath = localEbookPath!!, // Safe - we checked null above
            serverId = serverId,
            bookUuid = bookUuid,
            bookType = BookType.READALOUD,
        ).getOrElse { null }

        if (publication == null) {
            Log.e(TAG, "HeadlessSessionFactory: Failed to open publication")
            return null
        }

        // 4. Get saved reading position
        val readerRepo = repositoryProvider.getReaderRepository(serverId)
        val savedPosition = readerRepo?.getPosition(bookUuid)?.getOrElse { null }
        val initialChapterHref = savedPosition?.locatorHref
        val initialPositionMs = savedPosition?.audioTimestampMs

        Log.d(TAG, "HeadlessSessionFactory: Saved position - chapter=$initialChapterHref, posMs=$initialPositionMs")

        // 5. Configure data source factory for this publication
        dataSourceFactory.setPublication(publication.publication)

        // 6. Create SMIL components (not from Koin - manually instantiated for headless use)
        val smilContentProvider = PublicationSmilContentProvider(publication, analytics)
        val smilChapterIndex = SmilChapterIndex()
        val smilClipCache = SmilClipCache()
        val smilLoadingManager = SmilLoadingManager(
            smilParser = smilParser,
            quickScanner = quickScanner,
            analytics = analytics,
            index = smilChapterIndex,
            cache = smilClipCache,
            clipRepository = clipRepository,
            contentProvider = smilContentProvider,
        )

        // 7. Download cover artwork for Android Auto display
        val coverArtwork = downloadCoverImage(book.coverUrl, serverId)
        Log.d(TAG, "HeadlessSessionFactory: Cover artwork=${coverArtwork?.size ?: 0} bytes")

        // 8. Create book metadata for notifications
        val bookMetadata = HeadlessBookMetadata(
            title = book.title,
            author = book.authors.joinToString(", "),
            coverArtwork = coverArtwork,
        )

        // 9. Create HeadlessMediaOverlayPlayer
        val headlessPlayer = HeadlessMediaOverlayPlayer(
            epubPublication = publication,
            analytics = analytics,
            smilLoadingManager = smilLoadingManager,
            exoPlayer = exoPlayer,
            bookMetadata = bookMetadata,
        )

        // 10. Create and return the session
        Log.d(TAG, "HeadlessSessionFactory: Session created successfully")
        return HeadlessPlaybackSession(
            serverId = serverId,
            bookUuid = bookUuid,
            bookTitle = book.title,
            coverUrl = book.coverUrl,
            publication = publication,
            player = headlessPlayer,
            smilLoadingManager = smilLoadingManager,
            saveProgressUseCase = saveProgressUseCase,
            propagateToLinkedCopiesUseCase = propagateToLinkedCopiesUseCase,
            syncNowUseCase = syncNowUseCase,
            analytics = analytics,
            exoPlayer = exoPlayer,
            initialChapterHref = initialChapterHref,
            initialPositionMs = initialPositionMs,
        )
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
                            "HeadlessSessionFactory: skipping large cover ($contentLength bytes): $coverUrl",
                        )
                        return@withContext null
                    }
                    val bytes = connection.inputStream.use { it.readBytes() }
                    Log.d(TAG, "HeadlessSessionFactory: downloaded cover ($coverUrl): ${bytes.size} bytes")
                    bytes.takeIf { it.size <= MAX_EMBEDDED_ARTWORK_BYTES }
                } else {
                    Log.e(TAG, "HeadlessSessionFactory: failed to download cover ($coverUrl): HTTP $responseCode")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "HeadlessSessionFactory: error downloading cover ($coverUrl)", e)
                null
            }
        }
    }
}
