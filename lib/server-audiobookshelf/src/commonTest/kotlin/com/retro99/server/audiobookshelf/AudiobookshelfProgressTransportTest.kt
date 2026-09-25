package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import com.retro99.server.audiobookshelf.model.AudiobookshelfMediaProgressApiModel
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressLocator
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressPushResult
import com.retro99.sync.domain.ProgressSnapshot
import io.ktor.http.HeadersBuilder
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import retro99.network.api.QueryParamsScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AudiobookshelfProgressTransportTest {
    @Test
    fun fetchMapsMediaProgressToRemoteSnapshot() = runTest {
        val client = RecordingNetworkClient(
            getResult = Ok(
                AudiobookshelfMediaProgressApiModel(
                    libraryItemId = "library-book-1",
                    progress = 0.25,
                    currentTime = 12.5,
                    lastUpdate = 42L,
                    ebookLocation = "chapter.xhtml",
                    ebookProgress = 0.25,
                ),
            ),
        )
        val result = AudiobookshelfProgressTransport(client)
            .fetchProgress(setOf("book-1"))

        assertEquals(listOf("GET:/api/me/progress/book-1"), client.calls)
        assertEquals(42L, result.getValue("book-1").snapshot.timestamp)
        assertEquals(12_500L, result.getValue("book-1").snapshot.audioTimestampMs)
        assertEquals("library-book-1", result.getValue("book-1").libraryBookId)
    }

    @Test
    fun fetchTreatsNotFoundAsNoSavedProgress() = runTest {
        val client = RecordingNetworkClient(
            getResult = Err(AppError.ApiError(code = 404, message = "Resource not found")),
        )

        val result = AudiobookshelfProgressTransport(client)
            .fetchProgress(setOf("unread-book"))

        assertEquals(emptyMap(), result)
        assertEquals(listOf("GET:/api/me/progress/unread-book"), client.calls)
    }

    @Test
    fun fetchStillFailsForErrorsOtherThanNotFound() = runTest {
        val client = RecordingNetworkClient(
            getResult = Err(AppError.ApiError(code = 401, message = "Authentication error")),
        )

        assertFailsWith<IllegalStateException> {
            AudiobookshelfProgressTransport(client).fetchProgress(setOf("book-1"))
        }
    }

    @Test
    fun pushMapsSnapshotToAudiobookshelfPatch() = runTest {
        val mutation = mutation()
        val client = RecordingNetworkClient(patchResult = Ok(Unit))

        val result = AudiobookshelfProgressTransport(client).pushProgress(listOf(mutation))

        assertEquals(listOf("PATCH:/api/me/progress/book-1"), client.calls)
        assertEquals(
            AudiobookshelfMediaProgressApiModel(
                libraryItemId = "library-book-1",
                duration = 50.0,
                progress = 0.25,
                currentTime = 12.5,
                lastUpdate = 100L,
                ebookLocation = "chapter.xhtml",
                ebookProgress = 0.25,
            ),
            client.patchBody,
        )
        assertIs<ProgressPushResult.Accepted>(result.single())
    }

    @Test
    fun failedPatchIsReturnedAsRetryableRejection() = runTest {
        val client = RecordingNetworkClient(
            patchResult = Err(AppError.NetworkError(IllegalStateException("offline"))),
        )

        val result = AudiobookshelfProgressTransport(client).pushProgress(listOf(mutation()))
        val rejected = assertIs<ProgressPushResult.Rejected>(result.single())

        assertTrue(rejected.reason.contains("offline"))
        assertEquals(null, rejected.retryAfterMillis)
    }

    private fun mutation() = ProgressMutation(
        mutationId = "mutation-1",
        entityId = "book-1",
        remoteBookId = "book-1",
        libraryBookId = "library-book-1",
        kind = ProgressKind.AUDIO,
        snapshot = ProgressSnapshot(
            timestamp = 100L,
            createdAt = null,
            updatedAt = "100",
            locator = ProgressLocator(
                href = "chapter.xhtml",
                type = null,
                title = null,
                target = null,
                cssSelector = null,
            ),
            audioTimestampMs = 12_500L,
            chapterIndex = null,
            progression = 0.25,
            totalChapters = null,
            totalDurationMs = 50_000L,
            totalProgression = 0.25,
            position = null,
        ),
        baseVersion = null,
        observedAt = "100",
    )
}

class AudiobookshelfReaderRepositoryTest {
    @Test
    fun getRemotePositionTreatsNotFoundAsNoSavedProgress() = runTest {
        val client = RecordingNetworkClient(
            getResult = Err(AppError.ApiError(code = 404, message = "Resource not found")),
        )
        val repository = AudiobookshelfReaderRepository(
            networkClient = client,
            localSource = UnusedPositionLocalSource(),
        )

        val result = repository.getRemotePosition("unread-book")

        assertEquals(Ok<ServerPosition?>(null), result)
        assertEquals(listOf("GET:/api/me/progress/unread-book"), client.calls)
    }
}

private class UnusedPositionLocalSource : ServerPositionLocalSource {
    override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> = Ok(null)

    override suspend fun savePosition(position: ServerPosition): CompletableResult = Ok(Unit)

    override suspend fun savePositionWithSync(
        position: ServerPosition,
        remoteAccountId: String,
    ): CompletableResult = Ok(Unit)

    override suspend fun getAllPositions(): AppResult<List<ServerPosition>> = Ok(emptyList())

    override suspend fun deletePosition(bookUuid: String): CompletableResult = Ok(Unit)

    override fun observePosition(bookUuid: String): Flow<ServerPosition?> = flowOf(null)

    override fun observeAllPositions(): Flow<List<ServerPosition>> = flowOf(emptyList())
}

internal class RecordingNetworkClient(
    override val serverId: String = "audiobookshelf-1",
    override val baseUrl: String = "https://audiobookshelf.example",
    var getResult: AppResult<Any?> = Ok(null),
    var patchResult: AppResult<Any?> = Ok(Unit),
) : ServerNetworkClient {
    val calls = mutableListOf<String>()
    var patchBody: Any? = null

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> getWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> {
        calls += "GET:$path"
        return getResult as AppResult<T>
    }

    override suspend fun <T> postWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("POST is not used by Audiobookshelf progress tests")

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> patchWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> {
        calls += "PATCH:$path"
        patchBody = body
        return patchResult as AppResult<T>
    }

    override suspend fun <T> deleteWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("DELETE is not used by Audiobookshelf progress tests")

    override suspend fun <T> postFormWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        formData: Map<String, String>,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("POST form is not used by Audiobookshelf progress tests")

    override suspend fun downloadFile(
        path: String,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<ByteArray> = error("Downloads are not used by Audiobookshelf progress tests")

    override suspend fun downloadFileToPath(
        path: String,
        destinationPath: String,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<String> = error("Downloads are not used by Audiobookshelf progress tests")

    override suspend fun downloadFileToPathWithProgress(
        path: String,
        destinationPath: String,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<String> = error("Downloads are not used by Audiobookshelf progress tests")

    override fun close() = Unit
}
