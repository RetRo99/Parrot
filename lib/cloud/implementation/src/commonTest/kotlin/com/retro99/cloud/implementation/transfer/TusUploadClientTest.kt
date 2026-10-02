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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TusUploadClientTest {
    @Test
    fun supabaseMetadataEncoderUsesStorageVocabularyAndMediaTypeContentType() {
        val metadata = SupabaseTusMetadataEncoder().encode(
            TusUploadMetadata(
                targetPath = "users/u/books/b/file.epub",
                bookUuid = "book-123",
                fileName = "file.epub",
                mediaType = "ebook",
                sizeBytes = 123,
                contentHash = "hash-value",
            ),
        )

        assertEquals(
            linkedMapOf(
                "bucketName" to "book-files",
                "objectName" to "users/u/books/b/file.epub",
                "contentType" to "application/epub+zip",
                "cacheControl" to "3600",
                "metadata" to "{\"sha256\":\"hash-value\"}",
            ),
            metadata,
        )
    }

    @Test
    fun supabaseRequestPolicyLeavesHeadUnauthenticatedAndAuthenticatesWrites() {
        val policy = SupabaseTusRequestHeaderPolicy(
            configuration = CloudConfiguration("https://cloud.example", "test-key"),
            accessTokenProvider = TusAccessTokenProvider { "test-jwt" },
        )

        assertEquals(emptyMap(), policy.headersFor(TusRequestType.Head))
        assertEquals(
            mapOf("Authorization" to "Bearer test-jwt", "apikey" to "test-key"),
            policy.headersFor(TusRequestType.Patch),
        )
    }

    @Test
    fun changedFileSizeIsReportedAsUploadVerificationFailure() = runTest {
        val client = newClient(byteArrayOf(1, 2, 3)) { error("No HTTP request is expected") }

        assertFailsWith<TusUploadVerificationException> {
            client.upload(
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

        val url = client.upload(
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
                HttpMethod.Head -> {
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertNull(request.headers["apikey"])
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            "Upload-Offset" to listOf("2"),
                            "Upload-Length" to listOf("4"),
                            "Upload-Expires" to listOf("tomorrow"),
                        ),
                    )
                }

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

        client.upload(
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

        client.upload(
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

        client.upload(
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

        client.upload(
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
            client.upload(
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
        val result = client.upload(
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

    @Test
    fun foreignAbsoluteLocationFailsBeforeAnyChunkIsSent() = runTest {
        val seenHosts = mutableListOf<String>()
        val client = newClient(byteArrayOf(1, 2, 3, 4)) { request ->
            seenHosts += request.url.host
            respond(
                content = "",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.Location, "https://evil.example/upload/1"),
            )
        }

        assertFailsWith<IllegalStateException> {
            client.upload(
                uploadEndpoint = "/storage/v1/upload/resumable",
                storagePath = "users/u/books/b/f.epub",
                localPath = "local.epub",
                sizeBytes = 4,
                contentHash = "abcd",
                resumeUrl = null,
                resumeOffset = 0,
                onSession = { _, _ -> error("Foreign session must not be stored") },
                onHashReset = {},
                onChunkHashed = {},
                onProgress = {},
            )
        }

        assertEquals(listOf("cloud.example"), seenHosts)
        client.httpClient.close()
    }

    @Test
    fun offOriginResumeUrlIsDroppedForFreshSession() = runTest {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val client = newClient(byteArrayOf(1, 2, 3, 4)) { request ->
            seen += request.method to request.url.host
            when (request.method) {
                HttpMethod.Post -> respond(
                    content = "",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.Location, "/storage/v1/upload/resumable/s"),
                )

                HttpMethod.Patch -> respond(
                    content = "",
                    status = HttpStatusCode.NoContent,
                    headers = headersOf("Upload-Offset", "4"),
                )

                else -> error("Unexpected TUS method ${request.method}")
            }
        }

        client.upload(
            uploadEndpoint = "/storage/v1/upload/resumable",
            storagePath = "users/u/books/b/f.epub",
            localPath = "local.epub",
            sizeBytes = 4,
            contentHash = "abcd",
            resumeUrl = "http://cloud.example/session-1",
            resumeOffset = 0,
            onSession = { _, _ -> },
            onHashReset = {},
            onChunkHashed = {},
            onProgress = {},
        )

        assertEquals(
            listOf(HttpMethod.Post to "cloud.example", HttpMethod.Patch to "cloud.example"),
            seen,
        )
        client.httpClient.close()
    }

    @Test
    fun relativeLocationResolvesAgainstStorageHostEndpoint() = runTest {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val client = newClient(byteArrayOf(1, 2, 3, 4)) { request ->
            seen += request.method to request.url.toString()
            when (request.method) {
                HttpMethod.Post -> respond(
                    content = "",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.Location, "/storage/v1/upload/resumable/s"),
                )

                HttpMethod.Patch -> respond(
                    content = "",
                    status = HttpStatusCode.NoContent,
                    headers = headersOf("Upload-Offset", "4"),
                )

                else -> error("Unexpected TUS method ${request.method}")
            }
        }

        val url = client.upload(
            uploadEndpoint = "https://cloud.storage.example:8443/storage/v1/upload/resumable",
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

        val session = "https://cloud.storage.example:8443/storage/v1/upload/resumable/s"
        assertEquals(session, url)
        assertEquals(HttpMethod.Patch to session, seen.last())
        client.httpClient.close()
    }

    @Test
    fun cancelSkipsAuthenticatedDeleteForOffOriginUrl() = runTest {
        val seen = mutableListOf<String>()
        val client = newClient(byteArrayOf(1)) { request ->
            seen += request.url.toString()
            assertEquals("Bearer test-jwt", request.headers[HttpHeaders.Authorization])
            respond(content = "", status = HttpStatusCode.NoContent)
        }

        assertFalse(client.tus.cancel(client.profile, "https://evil.example/upload/1"))
        assertFalse(client.tus.cancel(client.profile, "http://cloud.example/upload/1"))
        assertTrue(client.tus.cancel(client.profile, "https://cloud.example/upload/1"))

        assertEquals(listOf("https://cloud.example/upload/1"), seen)
        client.httpClient.close()
    }

    @Test
    fun trustedUploadUrlRequiresSameOriginOrSameSupabaseProject() {
        val endpoint = "https://ref.supabase.co/storage/v1/upload/resumable"
        assertTrue(isTrustedUploadUrl(endpoint, "https://ref.supabase.co:443/storage/v1/x"))
        assertTrue(isTrustedUploadUrl(endpoint, "https://ref.storage.supabase.co/storage/v1/x"))
        assertFalse(isTrustedUploadUrl(endpoint, "http://ref.supabase.co/storage/v1/x"))
        assertFalse(isTrustedUploadUrl(endpoint, "https://other.supabase.co/storage/v1/x"))
        assertFalse(isTrustedUploadUrl(endpoint, "https://ref.supabase.co:8443/x"))
        assertFalse(isTrustedUploadUrl(endpoint, "https://ref.supabase.co.evil.example/x"))
        assertTrue(isTrustedUploadUrl("http://10.0.0.2:8001/u", "http://10.0.0.2:8001/u/1"))
        assertFalse(isTrustedUploadUrl("http://10.0.0.2:8001/u", "http://10.0.0.2:9000/u/1"))
    }

    private fun newClient(
        fileBytes: ByteArray,
        handler: io.ktor.client.engine.mock.MockRequestHandler,
    ): TestClient {
        val engine = MockEngine(handler)
        val httpClient = HttpClient(engine)
        val configuration = CloudConfiguration(
            supabaseUrl = "https://cloud.example",
            publishableKey = "test-key",
        )
        val accessTokenProvider = TusAccessTokenProvider { "test-jwt" }
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
        )
        val profile = TusUploadProfile(
            baseUrl = configuration.supabaseUrl,
            metadataEncoder = SupabaseTusMetadataEncoder(),
            requestHeaderPolicy = SupabaseTusRequestHeaderPolicy(
                configuration = configuration,
                accessTokenProvider = accessTokenProvider,
            ),
        )
        return TestClient(tus = tus, httpClient = httpClient, profile = profile)
    }

    private data class TestClient(
        val tus: TusUploadClient,
        val httpClient: HttpClient,
        val profile: TusUploadProfile,
    )

    private suspend fun TestClient.upload(
        uploadEndpoint: String,
        storagePath: String,
        localPath: String,
        sizeBytes: Long,
        contentHash: String,
        resumeUrl: String?,
        resumeOffset: Long,
        onSession: suspend (url: String, expiresAt: String?) -> Unit,
        onHashReset: suspend () -> Unit,
        onChunkHashed: suspend (bytes: ByteArray) -> Unit,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
    ): String = tus.upload(
        profile = profile,
        uploadEndpoint = uploadEndpoint,
        metadata = TusUploadMetadata(
            targetPath = storagePath,
            bookUuid = "book-123",
            fileName = "book.epub",
            mediaType = "ebook",
            sizeBytes = sizeBytes,
            contentHash = contentHash,
        ),
        localPath = localPath,
        sizeBytes = sizeBytes,
        resumeUrl = resumeUrl,
        resumeOffset = resumeOffset,
        onSession = onSession,
        onHashReset = onHashReset,
        onChunkHashed = onChunkHashed,
        onProgress = onProgress,
    )
}
