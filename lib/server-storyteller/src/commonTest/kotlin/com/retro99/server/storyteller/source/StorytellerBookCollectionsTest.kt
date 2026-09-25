package com.retro99.server.storyteller.source

import com.retro99.server.api.ServerBookCollection
import com.retro99.server.storyteller.model.StorytellerBookApiModel
import com.retro99.server.storyteller.model.toDomain
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StorytellerBookCollectionsTest {
    @Test
    fun decodesAndCachesCollectionsFromTheStorytellerBookContract() {
        // Given
        val apiBook = Json.decodeFromString<StorytellerBookApiModel>(
            """
                {
                  "uuid": "book-1",
                  "title": "A Book",
                  "collections": [
                    {
                      "uuid": "collection-1",
                      "name": "Favorites",
                      "createdAt": "2026-09-20T00:00:00Z",
                      "updatedAt": "2026-09-21T00:00:00Z"
                    }
                  ]
                }
            """.trimIndent(),
        )
        val expectedCollections = listOf(
            ServerBookCollection(
                id = "collection-1",
                name = "Favorites",
                createdAt = "2026-09-20T00:00:00Z",
                updatedAt = "2026-09-21T00:00:00Z",
            ),
        )

        // When
        val serverBook = apiBook.toDomain(serverId = "storyteller-1", baseUrl = null)
        val restoredBook = serverBook.toEntity().toServerBook(baseUrl = null)

        // Then
        assertEquals(expectedCollections, serverBook.collections)
        assertEquals(expectedCollections, restoredBook.collections)
    }

    @Test
    fun olderStorytellerBookResponsesDefaultToNoCollections() {
        val apiBook = Json.decodeFromString<StorytellerBookApiModel>(
            """{"uuid":"book-2","title":"No collection data"}""",
        )

        assertEquals(
            emptyList(),
            apiBook.toDomain(serverId = "storyteller-1", baseUrl = null).collections,
        )
    }
}
