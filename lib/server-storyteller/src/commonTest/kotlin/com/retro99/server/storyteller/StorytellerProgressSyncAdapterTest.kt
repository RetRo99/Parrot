package com.retro99.server.storyteller

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.api.sync.SyncCheckpointDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerCredentials
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerNetworkClientProvider
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.server.api.ServerPosition
import com.retro99.server.storyteller.model.StorytellerPositionApiModel
import com.retro99.sync.data.ProgressSyncEngine
import com.retro99.sync.data.SyncBoundedPass
import com.retro99.sync.data.SyncOutboxPreflight
import com.retro99.sync.data.SyncPullEngine
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.SyncScope
import com.retro99.sync.domain.SyncTriggerReason
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorytellerProgressSyncAdapterTest {
    @Test
    fun acceptedPositionFetchesBeforePushAndDeletesOnlyItsMutation() = runTest {
        val mutation = positionMutation(
            mutationId = "storyteller-position",
            serverId = "storyteller-1",
        )
        val bookmark = unsupportedMutation(
            mutationId = "storyteller-bookmark",
            serverId = "storyteller-1",
        )
        val outbox = RecordingAdapterOutbox(listOf(mutation, bookmark))
        val positions = RecordingAdapterPositions()
        val client = RecordingNetworkClient(
            serverId = "storyteller-1",
            getResult = Ok(StorytellerPositionApiModel(timestamp = 9L)),
            postResult = Ok(Unit),
        )

        val result = createAdapter(outbox, positions).execute(client)

        assertEquals(
            SyncResult.Completed(
                pushedMutationCount = 1,
                pulledChangeCount = 0,
                pendingMutationCount = 0,
            ),
            result,
        )
        assertEquals(
            listOf(
                "GET:/api/v2/books/book-1/positions",
                "POST:/api/v2/books/book-1/positions",
            ),
            client.calls,
        )
        assertEquals(listOf(mutation.mutationId), outbox.deletedIds)
        assertEquals(listOf(bookmark.mutationId), outbox.remainingIds)
        assertEquals(1, positions.remotePositions.size)
    }

    @Test
    fun bookOpenRefreshesOnlyTheSelectedBookBeforeReaderInitialization() = runTest {
        val outbox = RecordingAdapterOutbox(emptyList())
        val positions = RecordingAdapterPositions()
        val client = RecordingNetworkClient(
            serverId = "storyteller-1",
            getResult = Ok(StorytellerPositionApiModel(timestamp = 9L)),
            postResult = Ok(Unit),
        )

        val result = createAdapter(outbox, positions).execute(
            request = SyncRequest(
                reason = SyncTriggerReason.BOOK_OPEN,
                scope = SyncScope.Books(setOf("book-1")),
            ),
            networkClient = client,
        )

        assertEquals(SyncResult.Completed(0, 0, 0), result)
        assertEquals(listOf("GET:/api/v2/books/book-1/positions"), client.calls)
        assertEquals(1, positions.localPositions.size)
    }

    @Test
    fun rejectedPositionRemainsDurableAndRecordsRetryFailure() = runTest {
        val mutation = positionMutation(
            mutationId = "retry-position",
            serverId = "storyteller-1",
        )
        val outbox = RecordingAdapterOutbox(listOf(mutation))
        val client = RecordingNetworkClient(
            serverId = "storyteller-1",
            getResult = Ok(StorytellerPositionApiModel(timestamp = 9L)),
            postResult = Err(AppError.NetworkError(IllegalStateException("offline"))),
        )

        val result = createAdapter(outbox, RecordingAdapterPositions()).execute(client)

        assertEquals(
            SyncResult.Completed(
                pushedMutationCount = 0,
                pulledChangeCount = 0,
                pendingMutationCount = 1,
            ),
            result,
        )
        assertTrue(outbox.deletedIds.isEmpty())
        assertEquals(listOf(mutation.mutationId), outbox.failureIds)
        assertEquals(listOf(mutation.mutationId), outbox.remainingIds)
    }

    @Test
    fun selectionIsScopedToServerAndLeavesUnsupportedMutationsDurable() = runTest {
        val serverOne = positionMutation("server-one-position", "storyteller-1")
        val serverTwo = positionMutation("server-two-position", "storyteller-2")
        val bookmark = unsupportedMutation("server-one-bookmark", "storyteller-1")
        val outbox = RecordingAdapterOutbox(listOf(serverOne, serverTwo, bookmark))
        val client = RecordingNetworkClient(
            serverId = "storyteller-1",
            getResult = Ok(StorytellerPositionApiModel(timestamp = 9L)),
            postResult = Ok(Unit),
        )

        createAdapter(outbox, RecordingAdapterPositions()).execute(client)

        assertEquals(listOf(serverOne.mutationId), outbox.deletedIds)
        assertEquals(
            listOf(serverTwo.mutationId, bookmark.mutationId),
            outbox.remainingIds,
        )
    }

    @Test
    fun runtimeDestinationPinsProfileAndDiscoversOnlyAuthenticatedStorytellerServers() = runTest {
        val mutation = positionMutation("runtime-position", "storyteller-1")
        val outbox = RecordingAdapterOutbox(listOf(mutation))
        val positions = RecordingAdapterPositions()
        val storytellerClient = RecordingNetworkClient(
            serverId = "storyteller-1",
            getResult = Ok(StorytellerPositionApiModel(timestamp = 9L)),
            postResult = Ok(Unit),
        )
        val nonStoryteller = serverConfig("audiobookshelf-1", ServerType.Audiobookshelf)
        val storyteller = serverConfig("storyteller-1", ServerType.Storyteller)
        val serverRegistry = TestServerRegistry(
            allServers = listOf(storyteller, nonStoryteller),
            authenticatedServers = listOf(storyteller),
        )
        val clientProvider = RecordingNetworkClientProvider(
            clients = mapOf(storyteller.id to storytellerClient),
        )
        val profileSession = RecordingProfileDatabaseSession()

        val result = createAdapter(
            outbox = outbox,
            positions = positions,
            serverRegistry = serverRegistry,
            networkClientProvider = clientProvider,
            profileDatabaseSession = profileSession,
            userRegistry = TestUserRegistry(activeProfileId = "profile-1"),
        ).execute(SyncRequest())

        assertEquals(
            SyncResult.Completed(
                pushedMutationCount = 1,
                pulledChangeCount = 0,
                pendingMutationCount = 0,
            ),
            result,
        )
        assertEquals(listOf("profile-1"), profileSession.profileIds)
        assertEquals(listOf("storyteller-1"), clientProvider.createdServerIds)
        assertEquals(listOf(mutation.mutationId), outbox.deletedIds)
    }

    private fun createAdapter(
        outbox: RecordingAdapterOutbox,
        positions: RecordingAdapterPositions,
        serverRegistry: ServerRegistry = TestServerRegistry(),
        networkClientProvider: ServerNetworkClientProvider = RecordingNetworkClientProvider(),
        profileDatabaseSession: ProfileDatabaseSession = RecordingProfileDatabaseSession(),
        userRegistry: UserRegistry = TestUserRegistry(),
    ): StorytellerProgressSyncAdapter {
        return StorytellerProgressSyncAdapter(
            syncOutboxPreflight = SyncOutboxPreflight(outbox),
            progressSyncEngine = ProgressSyncEngine(outbox, positions),
            syncBoundedPass = SyncBoundedPass(
                SyncPullEngine(RecordingCheckpointDatabase()),
            ),
            serverRegistry = serverRegistry,
            networkClientProvider = networkClientProvider,
            profileDatabaseSession = profileDatabaseSession,
            userRegistry = userRegistry,
        )
    }

    private fun serverConfig(id: String, type: ServerType) = ServerConfig(
        id = id,
        name = id,
        type = type,
        baseUrl = "https://$id.example",
        addedAt = 0L,
    )

    private fun positionMutation(
        mutationId: String,
        serverId: String,
    ): SyncOutboxEntry {
        val position = ServerPosition(
            bookUuid = "book-1",
            serverId = serverId,
            libraryBookId = "library-book-1",
            timestamp = 100L,
            createdAt = "2026-09-22T10:00:00Z",
            updatedAt = "2026-09-22T10:01:00Z",
            locatorHref = "chapter.xhtml",
            locatorType = "application/xhtml+xml",
            locatorTitle = "Chapter 1",
            locatorTarget = 7,
            audioTimestampMs = null,
            chapterIndex = 1,
            progression = 0.25,
            totalChapters = 4,
            totalDurationMs = null,
            totalProgression = 0.25,
            position = 7,
        )
        return SyncOutboxEntry.new(
            cloudUserId = serverId,
            entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
            entityId = position.bookUuid,
            operation = SyncOutboxEntry.OPERATION_UPSERT,
            payload = json.encodeToString(OutboxPositionPayload(position)),
        ).copy(mutationId = mutationId)
    }

    private fun unsupportedMutation(
        mutationId: String,
        serverId: String,
    ): SyncOutboxEntry {
        return SyncOutboxEntry.new(
            cloudUserId = serverId,
            entityType = SyncOutboxEntry.ENTITY_TYPE_BOOKMARK,
            entityId = "bookmark-1",
            operation = SyncOutboxEntry.OPERATION_UPSERT,
            payload = "{}",
        ).copy(mutationId = mutationId)
    }

    private companion object {
        val json = Json {
            encodeDefaults = true
        }
    }
}

@Serializable
private data class OutboxPositionPayload(
    val position: ServerPosition,
)

private class RecordingAdapterOutbox(
    initialEntries: List<SyncOutboxEntry>,
) : SyncOutboxDatabase {
    private val entries = initialEntries.toMutableList()
    val deletedIds = mutableListOf<String>()
    val failureIds = mutableListOf<String>()
    val remainingIds: List<String>
        get() = entries.map { entry -> entry.mutationId }

    override suspend fun enqueue(entry: SyncOutboxEntry) {
        entries += entry
    }

    override suspend fun bindUnassignedMutations(cloudUserId: String) = Unit

    override suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> {
        return entries.filter { entry -> entry.cloudUserId == cloudUserId }
    }

    override suspend fun getEligible(
        cloudUserId: String,
        now: String,
    ): List<SyncOutboxEntry> {
        return getPending(cloudUserId)
    }

    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) = Unit

    override suspend fun markDispatched(mutationId: String) {
        update(mutationId) { entry ->
            entry.copy(state = SyncOutboxEntry.STATE_DISPATCHED)
        }
    }

    override suspend fun markConflict(mutationId: String, error: String) = Unit

    override suspend fun delete(mutationId: String) {
        deletedIds += mutationId
        entries.removeAll { entry -> entry.mutationId == mutationId }
    }

    override suspend fun deleteByEntityType(entityType: String) = Unit

    override suspend fun recordFailure(
        mutationId: String,
        nextAttemptAt: String,
        error: String,
    ) {
        failureIds += mutationId
        update(mutationId) { entry ->
            entry.copy(
                attemptCount = entry.attemptCount + 1,
                nextAttemptAt = nextAttemptAt,
                lastError = error,
            )
        }
    }

    override suspend fun coalesce(
        entityType: String,
        entityId: String,
        entry: SyncOutboxEntry,
    ) = Unit

    override suspend fun clearAllData() {
        entries.clear()
    }

    private fun update(
        mutationId: String,
        transform: (SyncOutboxEntry) -> SyncOutboxEntry,
    ) {
        val index = entries.indexOfFirst { entry -> entry.mutationId == mutationId }
        if (index >= 0) entries[index] = transform(entries[index])
    }
}

private class RecordingAdapterPositions : PositionDatabase {
    val localPositions = mutableListOf<PositionEntity>()
    val remotePositions = mutableListOf<PositionEntity>()

    override suspend fun upsertPosition(position: PositionEntity) {
        localPositions += position
    }

    override suspend fun upsertPositionWithMutation(
        position: PositionEntity,
        mutation: SyncOutboxEntry,
    ) = Unit

    override suspend fun updateRemoteRevision(
        bookUuid: String,
        remoteRevision: Long,
        expectedLocalGeneration: Long?,
    ) = Unit

    override suspend fun upsertRemotePosition(position: PositionEntity) {
        remotePositions += position
    }

    override suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionEntity? = null

    override suspend fun deleteRemotePosition(bookUuid: String) = Unit

    override suspend fun getPositionByBookUuid(bookUuid: String): PositionEntity? = null

    override suspend fun getAllPositions(): List<PositionEntity> = emptyList()

    override suspend fun deletePosition(bookUuid: String) = Unit

    override fun observePositionByBookUuid(bookUuid: String): Flow<PositionEntity?> = emptyFlow()

    override fun observeAllPositions(): Flow<List<PositionEntity>> = emptyFlow()

    override suspend fun clearAllData() = Unit
}

private class RecordingCheckpointDatabase : SyncCheckpointDatabase {
    override suspend fun getCheckpoint(
        destinationId: String,
        remoteAccountId: String,
    ): SyncCheckpoint? = null

    override suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) = Unit

    override suspend fun clearAllData() = Unit
}

private class RecordingProfileDatabaseSession : ProfileDatabaseSession {
    val profileIds = mutableListOf<String>()

    override suspend fun <T> withProfile(
        localProfileId: String,
        operation: suspend () -> T,
    ): T {
        profileIds += localProfileId
        return operation()
    }
}

private class RecordingNetworkClientProvider(
    private val clients: Map<String, ServerNetworkClient> = emptyMap(),
) : ServerNetworkClientProvider {
    val createdServerIds = mutableListOf<String>()

    override fun create(serverConfig: ServerConfig): ServerNetworkClient {
        createdServerIds += serverConfig.id
        return clients[serverConfig.id]
            ?: error("No test client for ${serverConfig.id}")
    }

    override suspend fun createForServerId(serverId: String): ServerNetworkClient? = null
}

private class TestServerRegistry(
    private val allServers: List<ServerConfig> = emptyList(),
    private val authenticatedServers: List<ServerConfig> = emptyList(),
) : ServerRegistry {
    override fun observeAllServers(): Flow<List<ServerConfig>> = emptyFlow()

    override suspend fun getAllServers(): List<ServerConfig> = allServers

    override suspend fun addServer(
        name: String,
        type: ServerType,
        baseUrl: String,
    ): ServerConfig = error("Server registry is not used by direct adapter tests")

    override suspend fun addServerWithId(
        id: String,
        name: String,
        type: ServerType,
        baseUrl: String,
    ): ServerConfig = error("Server registry is not used by direct adapter tests")

    override suspend fun updateServer(config: ServerConfig) = Unit

    override suspend fun removeServer(serverId: String) = Unit

    override suspend fun getServer(serverId: String): ServerConfig? = null

    override fun observeAllAuthStates(): Flow<Map<String, ServerAuthState>> = emptyFlow()

    override fun observeAuthState(serverId: String): Flow<ServerAuthState> = emptyFlow()

    override suspend fun isAuthenticated(serverId: String): Boolean = false

    override fun observeAuthenticatedServers(): Flow<List<ServerConfig>> = emptyFlow()

    override suspend fun getAuthenticatedServers(): List<ServerConfig> = authenticatedServers

    override suspend fun saveCredentials(credentials: ServerCredentials) = Unit

    override suspend fun getCredentials(serverId: String): ServerCredentials? = null

    override suspend fun clearCredentials(serverId: String) = Unit

    override suspend fun clearAllCredentials() = Unit

    override suspend fun deactivateServer(serverId: String) = Unit
}

private class TestUserRegistry(
    private val activeProfileId: String? = null,
) : UserRegistry {
    override fun observeAllProfiles(): Flow<List<UserProfile>> = emptyFlow()

    override suspend fun getAllProfiles(): List<UserProfile> = emptyList()

    override suspend fun createProfile(
        id: String?,
        name: String,
        avatarId: Int?,
    ): UserProfile = error("User registry is not used by direct adapter tests")

    override suspend fun updateProfile(profile: UserProfile) = Unit

    override suspend fun deleteProfile(profileId: String) = Unit

    override suspend fun getProfile(profileId: String): UserProfile? = null

    override fun observeActiveProfile(): Flow<UserProfile?> = emptyFlow()

    override suspend fun getActiveProfile(): UserProfile? = null

    override fun getActiveProfileId(): String? = activeProfileId

    override suspend fun setActiveProfile(profileId: String) = Unit

    override suspend fun clearActiveProfile() = Unit

    override suspend fun hasProfiles(): Boolean = false

    override fun isProfileActive(): Boolean = false
}
