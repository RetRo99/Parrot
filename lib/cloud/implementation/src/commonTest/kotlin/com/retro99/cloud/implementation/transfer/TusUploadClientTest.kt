package com.retro99.cloud.implementation.transfer

import com.retro99.cloud.implementation.CloudConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TusUploadClientTest {
    @Test
    fun changedFileSizeIsReportedAsUploadVerificationFailure() = runTest {
        val client = newClient(byteArrayOf(1, 2, 3)) { error("No HTTP request is expected") }

        assertFailsWith<TusUploadVerificationException> {
            client.tus.upload(
                uploadEndpoint = "/storage/v1/upload/resumable",
                storagePath = "users/u/books/b/f.epub",
                localPath = "local.epub",
                sizeBytes = 4,
                contentHash = "abcd",
                resumeUrl = null,
                resumeOffset = 0,
                onSession = { _, _ -> },
                onHashReset = {},
                onChunkHashed = {},
                onProgress = {},
            )
        }

        client.httpClient.close()
    }

    @Test
    fun createsSessionAndUploadsBoundedChunkWithAuthAndProgress() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val seenMethods = mutableListOf<HttpMethod>()
        val client = newClient(bytes) { request ->
            seenMethods += request.method
            when (request.method) {
                HttpMethod.Post -> {
                    assertEquals("Bearer test-jwt", request.headers[HttpHeaders.Authorization])
                    assertEquals("test-key", request.headers["apikey"])
                    assertTrue(request.headers["Upload-Metadata"].orEmpty().contains("bucketName "))
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.Location, "/storage/v1/upload/resumable/session-1"),
                    )
                }

                HttpMethod.Patch -> {
                    assertEquals("0", request.headers["Upload-Offset"])
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf("Upload-Offset", "4"),
                    )
                }

                else -> error("Unexpected TUS method ${request.method}")
            }
        }
        val progress = mutableListOf<Long>()
        val hashed = mutableListOf<Byte>()
        val sessions = mutableListOf<String>()

        val url = client.tus.upload(
            uploadEndpoint = "/storage/v1/upload/resumable",
            storagePath = "users/u/books/b/f.epub",
            localPath = "local.epub",
            sizeBytes = bytes.size.toLong(),
            contentHash = "abcd",
            resumeUrl = null,
            resumeOffset = 0,
            onSession = { sessionUrl, _ -> sessions += sessionUrl },
            onHashReset = {},
            onChunkHashed = { chunk -> hashed += chunk.toList() },
            onProgress = { transferred -> progress += transferred },
        )

        assertEquals("https://cloud.example/storage/v1/upload/resumable/session-1", url)
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Patch), seenMethods)
        assertEquals(listOf(0L, 4L), progress)
        assertEquals(bytes.toList(), hashed)
        assertEquals(listOf(url), sessions)
        client.httpClient.close()
    }

    @Test
    fun resumesFromServerOffsetAndHashesAcknowledgedPrefix() = runTest {
        val bytes = byteArrayOf(10, 20, 30, 40)
        val patchOffsets = mutableListOf<String?>()
        val methods = mutableListOf<HttpMethod>()
        val client = newClient(bytes) { request ->
            methods += request.method
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        "Upload-Offset" to listOf("2"),
                        "Upload-Length" to listOf("4"),
                        "Upload-Expires" to listOf("tomorrow"),
                    ),
                )

                HttpMethod.Patch -> {
                    patchOffsets += request.headers["Upload-Offset"]
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf("Upload-Offset", "4"),
                    )
                }

                else -> error("Unexpected TUS method ${request.method}")
            }
        }
        val progress = mutableListOf<Long>()
        val hashed = mutableListOf<Byte>()
        val sessions = mutableListOf<Pair<String, String?>>()

        client.tus.upload(
            uploadEndpoint = "/storage/v1/upload/resumable",
            storagePath = "users/u/books/b/f.epub",
            localPath = "local.epub",
            sizeBytes = bytes.size.toLong(),
            contentHash = "abcd",
            resumeUrl = "https://cloud.example/session-1",
            resumeOffset = 2,
            onSession = { url, expiresAt -> sessions += url to expiresAt },
            onHashReset = {},
            onChunkHashed = { chunk -> hashed += chunk.toList() },
            onProgress = { transferred -> progress += transferred },
        )

        assertEquals(listOf(HttpMethod.Head, HttpMethod.Patch), methods)
        assertEquals(listOf<String?>("2"), patchOffsets)
        assertEquals(listOf(2L, 4L), progress)
        assertEquals(bytes.toList(), hashed)
        assertEquals(listOf<Pair<String, String?>>("https://cloud.example/session-1" to "tomorrow"), sessions)
        client.httpClient.close()
    }

    @Test
    fun adoptsServerOffsetBehindPersistedResumeOffset() = runTest {
        val bytes = byteArrayOf(10, 20, 30, 40)
        val patchOffsets = mutableListOf<String?>()
        val progress = mutableListOf<Long>()
        val hashed = mutableListOf<Byte>()
        var hashResetCount = 0
        val client = newClient(bytes) { request ->
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        "Upload-Offset" to listOf("2"),
                        "Upload-Length" to listOf("4"),
                    ),
                )

                HttpMethod.Patch -> {
                    patchOffsets += request.headers["Upload-Offset"]
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf("Upload-Offset", "4"),
                    )
                }

                else -> error("Unexpected TUS method ${request.method}")
            }
        }

        client.tus.upload(
            uploadEndpoint = "/storage/v1/upload/resumable",
            storagePath = "users/u/books/b/f.epub",
            localPath = "local.epub",
            sizeBytes = bytes.size.toLong(),
            contentHash = "abcd",
            resumeUrl = "https://cloud.example/session-1",
            resumeOffset = 4,
            onSession = { _, _ -> },
            onHashReset = { hashResetCount++ },
            onChunkHashed = { chunk -> hashed += chunk.toList() },
            onProgress = { transferred -> progress += transferred },
        )

        assertEquals(listOf<String?>("2"), patchOffsets)
        assertEquals(listOf(2L, 4L), progress)
        assertEquals(1, hashResetCount)
        assertEquals(bytes.toList(), hashed)
        client.httpClient.close()
    }

    @Test
    fun patchConflictRefreshesOffsetBeforeCompletingHash() = runTest {
        val bytes = byteArrayOf(5, 6, 7, 8)
        var patchCount = 0
        var headCount = 0
        val client = newClient(bytes) { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    headCount++
                    val offset = if (headCount == 1) "0" else "4"
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            "Upload-Offset" to listOf(offset),
                            "Upload-Length" to listOf("4"),
                        ),
                    )
                }

                HttpMethod.Patch -> {
                    patchCount++
                    respond(content = "", status = HttpStatusCode.Conflict)
                }

                else -> error("Unexpected TUS method ${request.method}")
            }
        }
        val progress = mutableListOf<Long>()
        val hashed = mutableListOf<Byte>()

        client.tus.upload(
            uploadEndpoint = "/storage/v1/upload/resumable",
            storagePath = "users/u/books/b/f.epub",
            localPath = "local.epub",
            sizeBytes = bytes.size.toLong(),
            contentHash = "abcd",
            resumeUrl = "https://cloud.example/session-1",
            resumeOffset = 0,
            onSession = { _, _ -> },
            onHashReset = {},
            onChunkHashed = { chunk -> hashed += chunk.toList() },
            onProgress = { transferred -> progress += transferred },
        )

        assertEquals(1, patchCount)
        assertEquals(2, headCount)
        assertEquals(listOf(0L, 4L), progress)
        assertEquals(bytes.toList(), hashed)
        client.httpClient.close()
    }

    @Test
    fun conflictWithServerBehindRewindsProgressAndResetsHashBeforeReupload() = runTest {
        val chunkSize = 6 * 1024 * 1024
        val bytes = ByteArray(chunkSize * 2) { index -> index.toByte() }
        var patchCount = 0
        var headCount = 0
        var hashResetCount = 0
        var hashedByteCount = 0L
        val patchOffsets = mutableListOf<String?>()
        val progress = mutableListOf<Long>()
        val client = newClient(bytes) { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    headCount++
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            "Upload-Offset" to listOf("0"),
                            "Upload-Length" to listOf(bytes.size.toString()),
                        ),
                    )
                }

                HttpMethod.Patch -> {
                    patchCount++
                    patchOffsets += request.headers["Upload-Offset"]
                    when (patchCount) {
                        1 -> respond(
                            content = "",
                            status = HttpStatusCode.NoContent,
                            headers = headersOf("Upload-Offset", chunkSize.toString()),
                        )

                        2 -> respond(content = "", status = HttpStatusCode.Conflict)

                        3 -> respond(
                            content = "",
                            status = HttpStatusCode.NoContent,
                            headers = headersOf("Upload-Offset", chunkSize.toString()),
                        )

                        else -> respond(
                            content = "",
                            status = HttpStatusCode.NoContent,
                            headers = headersOf("Upload-Offset", bytes.size.toString()),
                        )
                    }
                }

                else -> error("Unexpected TUS method ${request.method}")
            }
        }

        client.tus.upload(
            uploadEndpoint = "/storage/v1/upload/resumable",
            storagePath = "users/u/books/b/f.epub",
            localPath = "local.epub",
            sizeBytes = bytes.size.toLong(),
            contentHash = "abcd",
            resumeUrl = "https://cloud.example/session-1",
            resumeOffset = 0,
            onSession = { _, _ -> },
            onHashReset = {
                hashResetCount++
                hashedByteCount = 0
            },
            onChunkHashed = { chunk -> hashedByteCount += chunk.size },
            onProgress = { transferred -> progress += transferred },
        )

        assertEquals(2, headCount)
        assertEquals(4, patchCount)
        assertEquals(
            listOf<String?>("0", chunkSize.toString(), "0", chunkSize.toString()),
            patchOffsets,
        )
        assertEquals(listOf(0L, chunkSize.toLong(), 0L, chunkSize.toLong(), bytes.size.toLong()), progress)
        assertEquals(1, hashResetCount)
        assertEquals(bytes.size.toLong(), hashedByteCount)
        client.httpClient.close()
    }

    @Test
    fun expiredSessionRequiresFreshAttemptAndRehashesFromStart() = runTest {
        val bytes = ByteArray(6 * 1024 * 1024 + 1) { index -> index.toByte() }
        var patchCount = 0
        var createCount = 0
        val client = newClient(bytes) { request ->
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        "Upload-Offset" to listOf("0"),
                        "Upload-Length" to listOf(bytes.size.toString()),
                    ),
                )

                HttpMethod.Post -> {
                    createCount++
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf(
                            HttpHeaders.Location,
                            "/storage/v1/upload/resumable/session-$createCount",
                        ),
                    )
                }

                HttpMethod.Patch -> {
                    patchCount++
                    when (patchCount) {
                        1 -> respond(
                            content = "",
                            status = HttpStatusCode.NoContent,
                            headers = headersOf("Upload-Offset", (6 * 1024 * 1024).toString()),
                        )

                        2 -> respond(content = "", status = HttpStatusCode.Gone)

                        3 -> respond(
                            content = "",
                            status = HttpStatusCode.NoContent,
                            headers = headersOf("Upload-Offset", (6 * 1024 * 1024).toString()),
                        )

                        else -> respond(
                            content = "",
                            status = HttpStatusCode.NoContent,
                            headers = headersOf("Upload-Offset", bytes.size.toString()),
                        )
                    }
                }

                else -> error("Unexpected TUS method ${request.method}")
            }
        }
        var firstAttemptHashedBytes = 0L
        assertFailsWith<TusUploadSessionExpiredException> {
            client.tus.upload(
                uploadEndpoint = "/storage/v1/upload/resumable",
                storagePath = "users/u/books/b/f.epub",
                localPath = "local.epub",
                sizeBytes = bytes.size.toLong(),
                contentHash = "abcd",
                resumeUrl = "https://cloud.example/session-old",
                resumeOffset = 0,
                onSession = { _, _ -> },
                onHashReset = {},
                onChunkHashed = { chunk -> firstAttemptHashedBytes += chunk.size },
                onProgress = {},
            )
        }
        assertEquals(6L * 1024L * 1024L, firstAttemptHashedBytes)

        var retryHashedBytes = 0L
        val result = client.tus.upload(
            uploadEndpoint = "/storage/v1/upload/resumable",
            storagePath = "users/u/books/b/f.epub",
            localPath = "local.epub",
            sizeBytes = bytes.size.toLong(),
            contentHash = "abcd",
            resumeUrl = null,
            resumeOffset = 0,
            onSession = { _, _ -> },
            onHashReset = {},
            onChunkHashed = { chunk -> retryHashedBytes += chunk.size },
            onProgress = {},
        )

        assertEquals("https://cloud.example/storage/v1/upload/resumable/session-1", result)
        assertEquals(bytes.size.toLong(), retryHashedBytes)
        assertEquals(1, createCount)
        client.httpClient.close()
    }

    private fun newClient(
        fileBytes: ByteArray,
        handler: io.ktor.client.engine.mock.MockRequestHandler,
    ): TestClient {
        val engine = MockEngine(handler)
        val httpClient = HttpClient(engine)
        val tus = TusUploadClient(
            httpClient = httpClient,
            localFileSource = object : TusLocalFileSource {
                override suspend fun size(path: String): Long = fileBytes.size.toLong()

                override suspend fun read(path: String, offset: Long, length: Int): ByteArray {
                    val start = offset.toInt().coerceAtMost(fileBytes.size)
                    val end = (start + length).coerceAtMost(fileBytes.size)
                    return fileBytes.copyOfRange(start, end)
                }
            },
            configuration = CloudConfiguration(
                supabaseUrl = "https://cloud.example",
                publishableKey = "test-key",
            ),
            accessTokenProvider = TusAccessTokenProvider { "test-jwt" },
        )
        return TestClient(tus = tus, httpClient = httpClient)
    }

    private data class TestClient(
        val tus: TusUploadClient,
        val httpClient: HttpClient,
    )
}
