package com.retro99.reader.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import io.ktor.http.HeadersBuilder
import io.ktor.util.reflect.TypeInfo

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookType
import com.retro99.reader.data.download.DownloadStateHolder
import com.retro99.reader.data.source.EbookFileDownloader
import com.retro99.reader.domain.model.DownloadState
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerNetworkClientProvider
import retro99.network.api.QueryParamsScope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class BookDownloadManagerImplTest {

    @Test
    fun cancelDuringPendingRetryRegistrationCancelsTheReplacement() = runTest {
        // Given
        val firstDownloadStarted = CompletableDeferred<Unit>()
        val firstDownloadCleanupStarted = CompletableDeferred<Unit>()
        val releaseFirstDownloadCleanup = CompletableDeferred<Unit>()
        val retryDownloadStarted = CompletableDeferred<Unit>()
        val retryDownloadCancelled = CompletableDeferred<Unit>()
        val finalDownloadStarted = CompletableDeferred<Unit>()
        val cancelRetryOnProgress = CompletableDeferred<Unit>()
        val retryCancellationStarted = CompletableDeferred<Unit>()
        val retryCancellationFinished = CompletableDeferred<Unit>()
        val downloadStateHolder = DownloadStateHolder()
        val networkClient = RetryRaceNetworkClient(
            firstDownloadStarted = firstDownloadStarted,
            firstDownloadCleanupStarted = firstDownloadCleanupStarted,
            releaseFirstDownloadCleanup = releaseFirstDownloadCleanup,
            retryDownloadStarted = retryDownloadStarted,
            retryDownloadCancelled = retryDownloadCancelled,
            finalDownloadStarted = finalDownloadStarted,
        )
        val manager = BookDownloadManagerImpl(
            fileDownloader = EbookFileDownloader(FakeNetworkClientProvider(networkClient)),
            downloadStateHolder = downloadStateHolder,
            analytics = FakeAnalytics(),
        )

        val stateObserver = backgroundScope.launch(
            UnconfinedTestDispatcher(testScheduler),
        ) {
            downloadStateHolder.observeDownloadState(BOOK_UUID, BookType.EBOOK)
                .collect { state ->
                    if (state is DownloadState.Downloading &&
                        cancelRetryOnProgress.isCompleted &&
                        !retryCancellationStarted.isCompleted
                    ) {
                        retryCancellationStarted.complete(Unit)
                        manager.cancelDownload(BOOK_UUID, BookType.EBOOK)
                        retryCancellationFinished.complete(Unit)
                    }
                }
        }

        try {
            // When
            manager.startDownload(
                bookUuid = BOOK_UUID,
                bookType = BookType.EBOOK,
                filePath = FIRST_PATH,
                bookTitle = "First attempt",
                serverId = SERVER_ID,
            )
            withRealTimeout { firstDownloadStarted.await() }

            val firstCancellation = backgroundScope.launch {
                manager.cancelDownload(BOOK_UUID, BookType.EBOOK)
            }
            withRealTimeout { firstDownloadCleanupStarted.await() }

            manager.startDownload(
                bookUuid = BOOK_UUID,
                bookType = BookType.EBOOK,
                filePath = RETRY_PATH,
                bookTitle = "Replacement attempt",
                serverId = SERVER_ID,
            )
            cancelRetryOnProgress.complete(Unit)
            releaseFirstDownloadCleanup.complete(Unit)

            withRealTimeout { retryCancellationFinished.await() }
            withRealTimeout { firstCancellation.join() }

            // Then
            assertEquals(
                DownloadState.Idle,
                manager.getDownloadState(BOOK_UUID, BookType.EBOOK),
            )
            assertEquals(0, networkClient.activeDownloads.value)
            assertEquals(
                retryDownloadStarted.isCompleted,
                retryDownloadCancelled.isCompleted,
            )

            // When
            manager.startDownload(
                bookUuid = BOOK_UUID,
                bookType = BookType.EBOOK,
                filePath = FINAL_PATH,
                bookTitle = "Final attempt",
                serverId = SERVER_ID,
            )

            // Then
            withRealTimeout { finalDownloadStarted.await() }
            withRealTimeout {
                downloadStateHolder.observeDownloadState(BOOK_UUID, BookType.EBOOK)
                    .first { state -> state is DownloadState.Cached }
            }
        } finally {
            releaseFirstDownloadCleanup.complete(Unit)
            manager.cancelDownload(BOOK_UUID, BookType.EBOOK)
            stateObserver.cancelAndJoin()
        }
    }

    private suspend fun <T> withRealTimeout(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(TEST_TIMEOUT_MILLIS) { block() }
        }

    private class FakeNetworkClientProvider(
        private val networkClient: ServerNetworkClient,
    ) : ServerNetworkClientProvider {

        override fun create(serverConfig: ServerConfig): ServerNetworkClient = networkClient

        override suspend fun createForServerId(serverId: String): ServerNetworkClient? =
            networkClient.takeIf { serverId == SERVER_ID }
    }

    private class RetryRaceNetworkClient(
        private val firstDownloadStarted: CompletableDeferred<Unit>,
        private val firstDownloadCleanupStarted: CompletableDeferred<Unit>,
        private val releaseFirstDownloadCleanup: CompletableDeferred<Unit>,
        private val retryDownloadStarted: CompletableDeferred<Unit>,
        private val retryDownloadCancelled: CompletableDeferred<Unit>,
        private val finalDownloadStarted: CompletableDeferred<Unit>,
    ) : ServerNetworkClient {

        val activeDownloads = MutableStateFlow(0)

        override val serverId: String = SERVER_ID
        override val baseUrl: String = "https://example.test"

        override suspend fun <T> getWithTypeInfo(
            path: String,
            typeInfo: TypeInfo,
            queryBuilder: QueryParamsScope.() -> Unit,
            headers: HeadersBuilder.() -> Unit,
        ): AppResult<T> = unsupportedResult()

        override suspend fun <T> postWithTypeInfo(
            path: String,
            typeInfo: TypeInfo,
            body: Any?,
            queryBuilder: QueryParamsScope.() -> Unit,
            headers: HeadersBuilder.() -> Unit,
        ): AppResult<T> = unsupportedResult()

        override suspend fun <T> patchWithTypeInfo(
            path: String,
            typeInfo: TypeInfo,
            body: Any?,
            queryBuilder: QueryParamsScope.() -> Unit,
            headers: HeadersBuilder.() -> Unit,
        ): AppResult<T> = unsupportedResult()

        override suspend fun <T> deleteWithTypeInfo(
            path: String,
            typeInfo: TypeInfo,
            body: Any?,
            queryBuilder: QueryParamsScope.() -> Unit,
            headers: HeadersBuilder.() -> Unit,
        ): AppResult<T> = unsupportedResult()

        override suspend fun <T> postFormWithTypeInfo(
            path: String,
            typeInfo: TypeInfo,
            formData: Map<String, String>,
            queryBuilder: QueryParamsScope.() -> Unit,
            headers: HeadersBuilder.() -> Unit,
        ): AppResult<T> = unsupportedResult()

        override suspend fun downloadFile(
            path: String,
            queryBuilder: QueryParamsScope.() -> Unit,
            headers: HeadersBuilder.() -> Unit,
        ): AppResult<ByteArray> = unsupportedResult()

        override suspend fun downloadFileToPath(
            path: String,
            destinationPath: String,
            queryBuilder: QueryParamsScope.() -> Unit,
            headers: HeadersBuilder.() -> Unit,
        ): AppResult<String> = unsupportedResult()

        override suspend fun downloadFileToPathWithProgress(
            path: String,
            destinationPath: String,
            onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
            queryBuilder: QueryParamsScope.() -> Unit,
            headers: HeadersBuilder.() -> Unit,
        ): AppResult<String> {
            return when (path) {
                FIRST_PATH -> {
                    firstDownloadStarted.complete(Unit)
                    trackDownload {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                firstDownloadCleanupStarted.complete(Unit)
                                releaseFirstDownloadCleanup.await()
                            }
                        }
                    }
                }

                RETRY_PATH -> {
                    retryDownloadStarted.complete(Unit)
                    trackDownload {
                        try {
                            awaitCancellation()
                        } finally {
                            retryDownloadCancelled.complete(Unit)
                        }
                    }
                }

                FINAL_PATH -> {
                    finalDownloadStarted.complete(Unit)
                    trackDownload { Ok(destinationPath) }
                }

                else -> unsupportedResult()
            }
        }

        override fun close() = Unit

        private suspend fun <T> trackDownload(block: suspend () -> T): T {
            activeDownloads.update { count -> count + 1 }
            return try {
                block()
            } finally {
                activeDownloads.update { count -> count - 1 }
            }
        }
    }

    private class FakeAnalytics : Analytics {

        override fun logException(throwable: Throwable, message: String?) = Unit

        override fun logEvent(event: AnalyticsEvent) = Unit

        override fun setUserId(userId: String?) = Unit
    }

    private companion object {
        const val BOOK_UUID = "ios-cancel-retry-handoff-test"
        const val FIRST_PATH = "/first"
        const val RETRY_PATH = "/retry"
        const val FINAL_PATH = "/final"
        const val SERVER_ID = "ios-cancel-retry-handoff-server"
        const val TEST_TIMEOUT_MILLIS = 5_000L

        fun <T> unsupportedResult(): AppResult<T> = Err(
            AppError.UnknownError(UnsupportedOperationException("Not used in this test")),
        )
    }
}
