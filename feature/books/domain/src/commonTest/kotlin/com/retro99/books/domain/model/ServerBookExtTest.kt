package com.retro99.books.domain.model

import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerBookExtTest {

    @Test
    fun `aggregateBookReplicas prefers local book with same canonical identity`() {
        val cloudBook = createBook(
            uuid = "cloud-id",
            serverId = "parrot-cloud",
            libraryBookId = "sha-256-v1:content-hash",
        )
        val localBook = createBook(
            uuid = "local-id",
            serverId = "local",
            libraryBookId = "sha-256-v1:content-hash",
            isLocal = true,
        )

        val result = listOf(cloudBook, localBook).aggregateBookReplicas()

        assertEquals(listOf(localBook), result)
    }

    @Test
    fun `aggregateBookReplicas matches replicas by content identity`() {
        val cloudBook = createBook(
            uuid = "cloud-id",
            serverId = "parrot-cloud",
            contentHash = "content-hash",
            contentHashAlgorithm = "sha-256-v1",
        )
        val localBook = createBook(
            uuid = "local-id",
            serverId = "local",
            contentHash = "content-hash",
            contentHashAlgorithm = "sha-256-v1",
            isLocal = true,
        )

        val result = listOf(cloudBook, localBook).aggregateBookReplicas()

        assertEquals(listOf(localBook), result)
    }

    @Test
    fun `aggregateBookReplicas keeps unrelated server books`() {
        val firstBook = createBook(
            uuid = "shared-id",
            serverId = "first-server",
        )
        val secondBook = createBook(
            uuid = "shared-id",
            serverId = "second-server",
        )

        val result = listOf(firstBook, secondBook).aggregateBookReplicas()

        assertEquals(listOf(firstBook, secondBook), result)
    }

    @Test
    fun `toBookDomainModel preserves local content identity`() {
        val serverBook = createBook(
            uuid = "local-id",
            serverId = "local",
            contentHash = "content-hash",
            contentHashAlgorithm = "sha-256-v1",
            isLocal = true,
        )

        val result = serverBook.toBookDomainModel()

        assertEquals("content-hash", result.contentHash)
        assertEquals("sha-256-v1", result.contentHashAlgorithm)
        assertEquals("sha-256-v1:content-hash", result.libraryBookId)
    }

    private fun createBook(
        uuid: String,
        serverId: String,
        libraryBookId: String? = null,
        contentHash: String? = null,
        contentHashAlgorithm: String? = null,
        isLocal: Boolean = false,
    ): ServerBook {
        return ServerBook(
            uuid = uuid,
            serverId = serverId,
            title = "Book",
            description = null,
            coverUrl = null,
            authors = emptyList(),
            narrators = emptyList(),
            series = emptyList(),
            tags = emptyList(),
            hasEbook = true,
            hasAudiobook = false,
            hasReadaloud = false,
            isLocal = isLocal,
            serverType = if (isLocal) ServerType.Local else ServerType.ParrotCloud,
            libraryBookId = libraryBookId,
            contentHash = contentHash,
            contentHashAlgorithm = contentHashAlgorithm,
        )
    }
}
