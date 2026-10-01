package com.retro99.server.storyteller

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.storyteller.model.StorytellerPositionApiModel
import com.retro99.server.storyteller.model.toStorytellerApiModel
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressLocator
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressPushResult
import com.retro99.sync.domain.ProgressSnapshot
import io.ktor.http.HeadersBuilder
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.test.runTest
import retro99.network.api.QueryParamsScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorytellerProgressTransportTest {
    @Test
    fun fetchMapsTheV2PositionEndpointToRemoteProgress() = runTest {
        val apiModel = StorytellerPositionApiModel(timestamp = 42L)
        val client = RecordingNetworkClient(
            getResult = Ok(apiModel),
        )
        val transport = StorytellerProgressTransport(client)

        val result = transport.fetchProgress(setOf("book-1"))

        assertEquals(listOf("GET:/api/v2/books/book-1/positions"), client.calls)
        assertEquals(42L, result.getValue("book-1").snapshot.timestamp)
        assertEquals("book-1", result.getValue("book-1").remoteBookId)
        assertEquals("book-1", result.getValue("book-1").entityId)
    }

    @Test
    fun pushMapsProgressAndAcceptsSuccessfulWrites() = runTest {
        val mutation = mutation()
        val client = RecordingNetworkClient(postResult = Ok(Unit))
        val transport = StorytellerProgressTransport(client)

        val result = transport.pushProgress(listOf(mutation))

        assertEquals(
            listOf("POST:/api/v2/books/book-1/positions"),
            client.calls,
        )
        assertEquals(
            mutation.snapshot.toStorytellerServerPosition(
                bookUuid = mutation.entityId,
                serverId = client.serverId,
                libraryBookId = mutation.libraryBookId,
            ).toStorytellerApiModel(),
            client.postBody,
        )
        assertIs<com.retro99.sync.domain.ProgressPushResult.Accepted>(result.single())
    }

    @Test
    fun failedWritesRemainRejectedWithoutInventingARevision() = runTest {
        val client = RecordingNetworkClient(
            postResult = Err(AppError.NetworkError(IllegalStateException("offline"))),
        )
        val transport = StorytellerProgressTransport(client)

        val result = transport.pushProgress(listOf(mutation()))
        val rejected = assertIs<com.retro99.sync.domain.ProgressPushResult.Rejected>(result.single())

        assertTrue(rejected.reason.contains("offline"))
        assertNull(rejected.retryAfterMillis)
    }

    @Test
    fun `a 409 followed by a successful fetch is a conflict carrying the server position`() =
        runTest {
            // Given
            val serverPosition = StorytellerPositionApiModel(timestamp = 500L)
            val client = RecordingNetworkClient(
                getResult = Ok(serverPosition),
                postResult = Err(AppError.ApiError(code = 409, message = "Timestamp older")),
            )
            val transport = StorytellerProgressTransport(client)

            // When
            val result = transport.pushProgress(listOf(mutation()))

            // Then
            val conflict = assertIs<ProgressPushResult.Conflict>(result.single())
            assertEquals("mutation-1", conflict.mutationId)
            assertEquals(500L, conflict.remote.snapshot.timestamp)
            assertEquals("book-1", conflict.remote.remoteBookId)
            assertEquals(
                listOf("POST:/api/v2/books/book-1/positions", "GET:/api/v2/books/book-1/positions"),
                client.calls,
            )
        }

    @Test
    fun `a 409 followed by a failed fetch is rejected with a long retry delay`() = runTest {
        // Given
        val client = RecordingNetworkClient(
            getResult = Err(AppError.NetworkError(IllegalStateException("offline"))),
            postResult = Err(AppError.ApiError(code = 409)),
        )
        val transport = StorytellerProgressTransport(client)

        // When
        val result = transport.pushProgress(listOf(mutation()))

        // Then
        val rejected = assertIs<ProgressPushResult.Rejected>(result.single())
        assertEquals(
            StorytellerProgressTransport.CONFLICT_RETRY_AFTER_MILLIS,
            rejected.retryAfterMillis,
        )
        assertTrue(rejected.retryAfterMillis!! >= 15 * 60 * 1000L)
    }

    @Test
    fun `a server error stays rejected on the short backoff`() = runTest {
        // Given
        val client = RecordingNetworkClient(postResult = Err(AppError.ApiError(code = 500)))
        val transport = StorytellerProgressTransport(client)

        // When
        val result = transport.pushProgress(listOf(mutation()))

        // Then
        val rejected = assertIs<ProgressPushResult.Rejected>(result.single())
        assertNull(rejected.retryAfterMillis)
        assertEquals(listOf("POST:/api/v2/books/book-1/positions"), client.calls)
    }

    private fun mutation() = ProgressMutation(
        mutationId = "mutation-1",
        entityId = "book-1",
        remoteBookId = "book-1",
        libraryBookId = "library-book-1",
        kind = ProgressKind.EBOOK,
        snapshot = ProgressSnapshot(
            timestamp = 100L,
            createdAt = "2026-09-22T10:00:00Z",
            updatedAt = "2026-09-22T10:01:00Z",
            locator = ProgressLocator(
                href = "chapter.xhtml",
                type = "application/xhtml+xml",
                title = "Chapter 1",
                target = 7,
                cssSelector = null,
            ),
            audioTimestampMs = null,
            chapterIndex = 1,
            progression = 0.25,
            totalChapters = 4,
            totalDurationMs = null,
            totalProgression = 0.25,
            position = 7,
        ),
        baseVersion = null,
        observedAt = "2026-09-22T10:01:00Z",
    )
}

internal class RecordingNetworkClient(
    override val serverId: String = "storyteller-1",
    override val baseUrl: String = "https://storyteller.example",
    var getResult: AppResult<Any?> = Ok(null),
    var postResult: AppResult<Any?> = Ok(Unit),
) : ServerNetworkClient {
    val calls = mutableListOf<String>()
    var postBody: Any? = null

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

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> postWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> {
        calls += "POST:$path"
        postBody = body
        return postResult as AppResult<T>
    }

    override suspend fun <T> patchWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("PATCH is not used by Storyteller progress tests")

    override suspend fun <T> deleteWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("DELETE is not used by Storyteller progress tests")

    override suspend fun <T> postFormWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        formData: Map<String, String>,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("POST form is not used by Storyteller progress tests")

    override suspend fun downloadFile(
        path: String,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<ByteArray> = error("Downloads are not used by Storyteller progress tests")

    override suspend fun downloadFileToPath(
        path: String,
        destinationPath: String,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<String> = error("Downloads are not used by Storyteller progress tests")

    override suspend fun downloadFileToPathWithProgress(
        path: String,
        destinationPath: String,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<String> = error("Downloads are not used by Storyteller progress tests")

    override fun close() = Unit
}
