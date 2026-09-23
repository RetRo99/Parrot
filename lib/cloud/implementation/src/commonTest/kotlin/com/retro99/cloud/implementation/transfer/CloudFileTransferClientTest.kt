package com.retro99.cloud.implementation.transfer

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
import kotlinx.coroutines.test.runTest

class CloudFileTransferClientTest {
    @Test
    fun resumesFromRangeAndStreamsOnlyRemainingBytes() = runTest {
        val httpClient = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("bytes=2-", request.headers[HttpHeaders.Range])
            respond(
                content = "cdef",
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentRange, "bytes 2-5/6"),
            )
        })
        val offsets = mutableListOf<Long>()
        val chunks = mutableListOf<String>()

        CloudFileTransferClient(httpClient).download(
            signedUrl = "https://storage.example/signed?token=secret",
            resumeOffset = 2,
            expectedSize = 6,
            onResponseOffset = { offsets += it },
            onChunk = { bytes -> chunks += bytes.decodeToString() },
        )

        assertEquals(listOf(2L), offsets)
        assertEquals(listOf("cdef"), chunks)
        httpClient.close()
    }

    @Test
    fun restartsAtZeroWhenServerIgnoresRange() = runTest {
        val httpClient = HttpClient(MockEngine {
            respond(content = "abcdef", status = HttpStatusCode.OK)
        })
        val offsets = mutableListOf<Long>()

        CloudFileTransferClient(httpClient).download(
            signedUrl = "https://storage.example/signed",
            resumeOffset = 3,
            expectedSize = 6,
            onResponseOffset = { offsets += it },
            onChunk = {},
        )

        assertEquals(listOf(0L), offsets)
        httpClient.close()
    }

    @Test
    fun expiredSignedUrlRequiresReauthorization() = runTest {
        val httpClient = HttpClient(MockEngine {
            respond(content = "", status = HttpStatusCode.Forbidden)
        })

        assertFailsWith<CloudFileTransferGrantExpiredException> {
            CloudFileTransferClient(httpClient).download(
                signedUrl = "https://storage.example/signed",
                resumeOffset = 0,
                expectedSize = 1,
                onResponseOffset = {},
                onChunk = {},
            )
        }
        httpClient.close()
    }
}
