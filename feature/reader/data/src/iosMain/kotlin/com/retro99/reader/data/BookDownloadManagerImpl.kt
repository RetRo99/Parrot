package com.retro99.reader.data

import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.base.result.log
import com.retro99.books.domain.model.BookType
import com.retro99.reader.data.download.DownloadStateHolder
import com.retro99.reader.data.source.EbookFileDownloader
import com.retro99.reader.data.source.isMultiFileDownload
import com.retro99.reader.domain.BookDownloadManager
import com.retro99.reader.domain.model.DownloadKey
import com.retro99.reader.domain.model.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * iOS implementation of [BookDownloadManager].
 *
 * Uses URLSession-based downloads through the NetworkClient.
 * iOS has better background handling for network requests, so downloads
 * can continue when the app is backgrounded.
 *
 * State is held in [DownloadStateHolder] which is shared with Android implementation.
 *
 * TODO: For full background download support that survives app termination,
 * enhance to use URLSession with background configuration.
 */
@Single(binds = [BookDownloadManager::class])
actual class BookDownloadManagerImpl(
    @Provided private val fileDownloader: EbookFileDownloader,
    @Provided private val downloadStateHolder: DownloadStateHolder,
    @Provided private val analytics: Analytics,
) : BookDownloadManager {

    private data class PendingDownload(
        val bookUuid: String,
        val bookType: BookType,
        val filePath: String,
        val bookTitle: String,
        val serverId: String,
    )

    /**
     * Application-scoped coroutine scope for downloads.
     * Uses SupervisorJob so one failed download doesn't cancel others.
     */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Tracks active download jobs so they can be cancelled.
     * Protected by [activeJobsMutex] for thread-safe access.
     */
    private val activeJobs = mutableMapOf<DownloadKey, Job>()
    private val cancellingKeys = mutableSetOf<DownloadKey>()
    private val pendingDownloads = mutableMapOf<DownloadKey, PendingDownload>()
    private val activeJobsMutex = Mutex()

    override fun observeDownloadState(
        bookUuid: String,
        bookType: BookType,
    ): Flow<DownloadState> {
        return downloadStateHolder.observeDownloadState(bookUuid, bookType)
            .map { state -> resolveState(state, bookUuid, bookType) }
            .distinctUntilChanged()
    }

    override fun observeAllDownloads(): Flow<Map<DownloadKey, DownloadState>> {
        return downloadStateHolder.observeAllDownloads()
    }

    override suspend fun startDownload(
        bookUuid: String,
        bookType: BookType,
        filePath: String,
        bookTitle: String,
        serverId: String,
    ) {
        val key = DownloadKey(bookUuid, bookType)
        val request = PendingDownload(bookUuid, bookType, filePath, bookTitle, serverId)

        activeJobsMutex.withLock {
            if (key in cancellingKeys) {
                pendingDownloads[key] = request
            } else {
                startDownloadLocked(key, request)
            }
        }
    }

    /** Must be called with [activeJobsMutex] held. */
    private suspend fun startDownloadLocked(
        key: DownloadKey,
        request: PendingDownload,
        isCancellationHandoff: Boolean = false,
    ) {
        if (key in activeJobs) {
            if (isCancellationHandoff) finishCancellationHandoff(key)
            return
        }

        val bookUuid = request.bookUuid
        val bookType = request.bookType
        if (isCancellationHandoff) {
            downloadStateHolder.clearCancelledState(bookUuid, bookType)
        }

        if (fileDownloader.isEbookCached(bookUuid, bookType)) {
            downloadStateHolder.markCached(bookUuid, bookType)
            if (isCancellationHandoff) finishCancellationHandoff(key)
            return
        }

        val currentState = downloadStateHolder.getDownloadState(bookUuid, bookType)
        if (currentState is DownloadState.Downloading) {
            if (isCancellationHandoff) finishCancellationHandoff(key)
            return
        }

        downloadStateHolder.clearCancelledState(bookUuid, bookType)
        downloadStateHolder.updateProgress(bookUuid, bookType, null)

        var activeJob: Job? = null
        val job = downloadScope.launch(start = CoroutineStart.LAZY) {
            try {
                fileDownloader.downloadEbookWithProgress(
                    ebookFilePath = request.filePath,
                    bookUuid = bookUuid,
                    bookType = bookType,
                    serverId = request.serverId,
                    onProgress = { bytesDownloaded, totalBytes ->
                        val progress = if (totalBytes != null && totalBytes > 0) {
                            (bytesDownloaded.toFloat() / totalBytes.toFloat())
                                .coerceIn(0f, 1f)
                        } else {
                            null
                        }
                        downloadStateHolder.updateProgress(bookUuid, bookType, progress)
                    },
                ).onSuccess {
                    downloadStateHolder.markCached(bookUuid, bookType)
                }.onFailure { error ->
                    error.log(
                        analytics,
                        "BookDownloadManager: Download failed for book=$bookUuid, " +
                            "type=$bookType",
                    )
                    downloadStateHolder.markFailed(bookUuid, bookType, error)
                }
            } finally {
                val wasCancelled = !currentCoroutineContext().isActive
                withContext(NonCancellable) {
                    if (wasCancelled) {
                        cleanCancelledDownload(request)
                    }
                    activeJobsMutex.withLock {
                        if (!wasCancelled && key in cancellingKeys) {
                            cleanCancelledDownload(request)
                        }
                        if (activeJobs[key] === activeJob) activeJobs.remove(key)
                        val pendingRequest = pendingDownloads[key]
                        if (key in cancellingKeys && pendingRequest == null) {
                            cancellingKeys.remove(key)
                        } else if (key in cancellingKeys && pendingRequest != null) {
                            startDownloadLocked(
                                key = key,
                                request = pendingRequest,
                                isCancellationHandoff = true,
                            )
                        }
                    }
                }
            }
        }

        activeJob = job
        activeJobs[key] = job
        if (isCancellationHandoff) {
            finishCancellationHandoff(key)
        }
        job.start()
    }

    private suspend fun cleanCancelledDownload(request: PendingDownload) {
        if (!request.filePath.isMultiFileDownload()) {
            fileDownloader.deleteEbookCache(request.bookUuid, request.bookType)
        }
        downloadStateHolder.markIdle(request.bookUuid, request.bookType)
    }

    private fun finishCancellationHandoff(key: DownloadKey) {
        pendingDownloads.remove(key)
        cancellingKeys.remove(key)
    }

    override suspend fun cancelDownload(bookUuid: String, bookType: BookType) {
        val key = DownloadKey(bookUuid, bookType)
        val activeJob = activeJobsMutex.withLock {
            pendingDownloads.remove(key)
            val job = activeJobs[key]
            if (job == null) {
                if (key !in cancellingKeys) {
                    downloadStateHolder.markIdle(bookUuid, bookType)
                }
            } else {
                cancellingKeys.add(key)
                job.cancel()
            }
            job
        }
        activeJob?.cancelAndJoin()
    }

    override fun clearError(bookUuid: String, bookType: BookType) {
        downloadStateHolder.clearError(bookUuid, bookType)
    }

    override fun getDownloadState(bookUuid: String, bookType: BookType): DownloadState {
        val state = downloadStateHolder.getDownloadState(bookUuid, bookType)
        return resolveState(state, bookUuid, bookType)
    }

    /**
     * Resolves the actual download state by checking if the file is cached
     * when the state is Idle.
     */
    private fun resolveState(
        state: DownloadState,
        bookUuid: String,
        bookType: BookType,
    ): DownloadState {
        return if (state is DownloadState.Idle &&
            fileDownloader.isEbookCached(bookUuid, bookType)
        ) {
            DownloadState.Cached
        } else {
            state
        }
    }

    override suspend fun deleteCache(bookUuid: String, bookType: BookType): Boolean {
        val deleted = fileDownloader.deleteEbookCache(bookUuid, bookType)
        if (deleted) {
            downloadStateHolder.markIdle(bookUuid, bookType)
        }
        return deleted
    }
}
