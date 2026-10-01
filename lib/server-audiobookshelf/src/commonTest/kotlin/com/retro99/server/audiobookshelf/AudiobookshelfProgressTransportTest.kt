package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.audiobookshelf.model.AudiobookshelfMediaProgressApiModel
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
        // currentTime is from the start of the book; without file lengths the file is unknown.
        assertEquals(12_500L, result.getValue("book-1").snapshot.bookTimeMs)
        assertNull(result.getValue("book-1").snapshot.audioTimestampMs)
        assertEquals("library-book-1", result.getValue("book-1").libraryBookId)
    }

    @Test
    fun `a pulled book time lands in the right file when the lengths are cached`() = runTest {
        // Given
        val client = RecordingNetworkClient(
            getResult = Ok(
                AudiobookshelfMediaProgressApiModel(
                    libraryItemId = "book-1",
                    duration = 36_000.0,
                    progress = 0.5125,
                    currentTime = 18_450.0,
                ),
            ),
        )
        val transport = AudiobookshelfProgressTransport(client) { _ -> FORTY_FILES }

        // When
        val snapshot = transport.fetchProgress(setOf("book-1")).getValue("book-1").snapshot

        // Then
        assertEquals(18_450_000L, snapshot.bookTimeMs)
        assertEquals(20, snapshot.chapterIndex)
        assertEquals(450_000L, snapshot.audioTimestampMs)
        assertEquals(36_000_000L, snapshot.totalDurationMs)
        assertEquals(0.5125, snapshot.totalProgression)
    }

    @Test
    fun `a pulled single-file position is the book time`() = runTest {
        // Given
        val client = RecordingNetworkClient(
            getResult = Ok(
                AudiobookshelfMediaProgressApiModel(
                    duration = 3_600.0,
                    progress = 0.5,
                    currentTime = 1_800.0,
                ),
            ),
        )
        val transport = AudiobookshelfProgressTransport(client) { _ -> listOf(3_600_000L) }

        // When
        val snapshot = transport.fetchProgress(setOf("book-1")).getValue("book-1").snapshot

        // Then
        assertEquals(1_800_000L, snapshot.bookTimeMs)
        assertEquals(0, snapshot.chapterIndex)
        assertEquals(1_800_000L, snapshot.audioTimestampMs)
        assertEquals(0.5, snapshot.totalProgression)
    }

    @Test
    fun `a multi-file position is pushed as book time and whole-book values`() = runTest {
        // Given
        val client = RecordingNetworkClient(patchResult = Ok(Unit))
        val mutation = audioMutation(
            offsetMs = 450_000L,
            trackIndex = 20,
            trackCount = 40,
            bookTimeMs = 18_450_000L,
            totalDurationMs = 36_000_000L,
            totalProgression = 0.5125,
        )

        // When
        val result = AudiobookshelfProgressTransport(client).pushProgress(listOf(mutation))

        // Then
        val body = assertIs<AudiobookshelfMediaProgressApiModel>(client.patchBody)
        assertEquals(18_450.0, body.currentTime)
        assertEquals(36_000.0, body.duration)
        assertEquals(0.5125, body.progress)
        assertIs<ProgressPushResult.Accepted>(result.single())
    }

    @Test
    fun `a multi-file position without book time is never sent`() = runTest {
        // Given
        val client = RecordingNetworkClient(patchResult = Ok(Unit))
        val mutation = audioMutation(
            offsetMs = 450_000L,
            trackIndex = 20,
            trackCount = 40,
            bookTimeMs = null,
            totalDurationMs = null,
            totalProgression = null,
        )

        // When
        val result = AudiobookshelfProgressTransport(client).pushProgress(listOf(mutation))

        // Then
        assertTrue(client.calls.isEmpty())
        val rejected = assertIs<ProgressPushResult.Rejected>(result.single())
        assertEquals(
            AudiobookshelfProgressTransport.MISSING_BOOK_TIME_RETRY_AFTER_MILLIS,
            rejected.retryAfterMillis,
        )
    }

    @Test
    fun `a single-file position without book time sends its offset`() = runTest {
        // Given
        val client = RecordingNetworkClient(patchResult = Ok(Unit))
        val mutation = audioMutation(
            offsetMs = 1_800_000L,
            trackIndex = 0,
            trackCount = 1,
            bookTimeMs = null,
            totalDurationMs = 3_600_000L,
            totalProgression = 0.5,
        )

        // When
        AudiobookshelfProgressTransport(client).pushProgress(listOf(mutation))

        // Then
        val body = assertIs<AudiobookshelfMediaProgressApiModel>(client.patchBody)
        assertEquals(1_800.0, body.currentTime)
        assertEquals(3_600.0, body.duration)
        assertEquals(0.5, body.progress)
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

    private fun audioMutation(
        offsetMs: Long,
        trackIndex: Int,
        trackCount: Int,
        bookTimeMs: Long?,
        totalDurationMs: Long?,
        totalProgression: Double?,
    ) = mutation().let { base ->
        base.copy(
            snapshot = base.snapshot.copy(
                locator = null,
                audioTimestampMs = offsetMs,
                chapterIndex = trackIndex,
                totalChapters = trackCount,
                progression = totalProgression,
                totalDurationMs = totalDurationMs,
                totalProgression = totalProgression,
                bookTimeMs = bookTimeMs,
            ),
        )
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

private val FORTY_FILES = List(40) { _ -> 15L * 60 * 1000 }

private class RecordingNetworkClient(
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
