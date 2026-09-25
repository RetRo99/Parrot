package com.retro99.server.storyteller

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.network.implementation.di.NetworkingModule
import com.retro99.server.api.ServerBook
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.storyteller.model.StorytellerCurrentUserResponse
import com.retro99.server.storyteller.model.StorytellerServerDetailsResponse
import com.retro99.server.storyteller.source.ServerBooksLocalSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StorytellerBooksRepositoryIdentityTest {
    @Test
    fun portableIdentityUsesServerAndAuthenticatedAccountIds() = runTest {
        // Given
        val serverId = "A1B2C3D4-E5F6-47A8-9012-3456789ABCDE"
        val accountId = "11111111-2222-4333-8444-555555555555"
        val networkClient = networkClient(
            serverDetails = Ok(StorytellerServerDetailsResponse(serverId)),
            currentUser = Ok(StorytellerCurrentUserResponse(accountId)),
        )

        // When
        val identity = repository(networkClient).libraryAccountIdentity()

        // Then
        assertEquals(
            SourceAccountIdentity.Portable(
                backendId = serverId.lowercase(),
                accountId = accountId,
            ),
            identity,
        )
        assertEquals(
            listOf("GET:/api/v2/server/details", "GET:/api/v2/user"),
            networkClient.calls,
        )
    }

    @Test
    fun unsupportedServerIdentityEndpointKeepsTheSourceUnresolved() = runTest {
        // Given
        val networkClient = networkClient(
            serverDetails = Err(AppError.ApiError(404, "not found")),
            currentUser = Ok(
                StorytellerCurrentUserResponse(
                    "11111111-2222-4333-8444-555555555555",
                ),
            ),
        )

        // When
        val identity = repository(networkClient).libraryAccountIdentity()

        // Then
        assertNull(identity)
        assertEquals(listOf("GET:/api/v2/server/details"), networkClient.calls)
    }

    @Test
    fun missingOrMalformedServerIdKeepsTheSourceUnresolved() = runTest {
        // Given
        val networkClient = networkClient(
            serverDetails = Ok(StorytellerServerDetailsResponse("https://books.example")),
            currentUser = Ok(
                StorytellerCurrentUserResponse(
                    "11111111-2222-4333-8444-555555555555",
                ),
            ),
        )

        // When
        val identity = repository(networkClient).libraryAccountIdentity()

        // Then
        assertNull(identity)
        assertEquals(listOf("GET:/api/v2/server/details"), networkClient.calls)
    }

    @Test
    fun missingMalformedOrUnavailableAuthenticatedUserIdReturnsNull() = runTest {
        // Given
        val serverId = "11111111-2222-4333-8444-555555555555"
        val missingUserId = networkClient(
            serverDetails = Ok(StorytellerServerDetailsResponse(serverId)),
            currentUser = Ok(StorytellerCurrentUserResponse()),
        )
        val malformedUserId = networkClient(
            serverDetails = Ok(StorytellerServerDetailsResponse(serverId)),
            currentUser = Ok(StorytellerCurrentUserResponse("reader")),
        )
        val unavailableUserEndpoint = networkClient(
            serverDetails = Ok(StorytellerServerDetailsResponse(serverId)),
            currentUser = Err(AppError.ApiError(401, "not authenticated")),
        )

        // When
        val identities = listOf(missingUserId, malformedUserId, unavailableUserEndpoint)
            .map { client -> repository(client).libraryAccountIdentity() }

        // Then
        assertEquals(listOf(null, null, null), identities)
        assertEquals(
            listOf("GET:/api/v2/server/details", "GET:/api/v2/user"),
            missingUserId.calls,
        )
        assertEquals(
            listOf("GET:/api/v2/server/details", "GET:/api/v2/user"),
            malformedUserId.calls,
        )
        assertEquals(
            listOf("GET:/api/v2/server/details", "GET:/api/v2/user"),
            unavailableUserEndpoint.calls,
        )
    }

    @Test
    fun endpointModelsIgnoreServerAddedFieldsAndDoNotRequireThemForLegacyResponses() {
        // Given
        val json = NetworkingModule().provideJson()
        val serverDetailsBody = """
            {
                "id": "11111111-2222-4333-8444-555555555555",
                "name": "Personal Library",
                "version": "2.1.0",
                "publicKey": "server-key"
            }
        """.trimIndent()
        val userBody = """
            {
                "id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                "username": "reader",
                "email": "reader@example.com",
                "permissions": { "bookRead": true }
            }
        """.trimIndent()
        val olderServerBody = """
            { "name": "Personal Library", "version": "1.0.0" }
        """.trimIndent()

        // When
        val serverDetails = json.decodeFromString<StorytellerServerDetailsResponse>(
            serverDetailsBody,
        )
        val currentUser = json.decodeFromString<StorytellerCurrentUserResponse>(userBody)
        val olderServer = json.decodeFromString<StorytellerServerDetailsResponse>(olderServerBody)

        // Then
        assertEquals("11111111-2222-4333-8444-555555555555", serverDetails.id)
        assertEquals("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", currentUser.id)
        assertNull(olderServer.id)
    }

    private fun networkClient(
        serverDetails: AppResult<StorytellerServerDetailsResponse>,
        currentUser: AppResult<StorytellerCurrentUserResponse>,
    ) = RecordingNetworkClient(
        getResultsByPath = mapOf(
            "/api/v2/server/details" to serverDetails,
            "/api/v2/user" to currentUser,
        ),
    )

    private fun repository(networkClient: RecordingNetworkClient) = StorytellerBooksRepository(
        networkClient = networkClient,
        localSource = object : ServerBooksLocalSource {
            override suspend fun getBooks(serverId: String) = Ok(null)

            override suspend fun getBook(serverId: String, uuid: String) = Ok(null)

            override suspend fun saveBooks(
                serverId: String,
                books: List<ServerBook>,
            ) = Ok(Unit)

            override suspend fun saveBook(
                serverId: String,
                book: ServerBook,
            ) = Ok(Unit)

            override suspend fun clearCache(serverId: String) = Ok(Unit)
        },
    )
}
