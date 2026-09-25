package com.retro99.library.data

import com.retro99.database.api.library.DeviceReplicaRetirementResult
import com.retro99.database.api.library.LibraryBackfillResult
import com.retro99.database.api.library.LibraryReplicaRemovalDatabase
import com.retro99.database.api.library.LibraryReplicaRemovalIntent
import com.retro99.database.api.library.LibraryReplicaRemovalPhase
import com.retro99.database.api.library.LibrarySourceSnapshotsDatabase
import com.retro99.database.api.library.RemoteReplicaRetirementResult
import com.retro99.library.domain.operation.LibraryReplicaRemovalResult
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapter
import com.retro99.server.api.library.LibraryOperationAdapterRegistry
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourceSnapshotStatus
import com.retro99.server.api.library.StorageReplicaId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryReplicaRemovalDataRepositoryTest {
    @Test
    fun removalPersistsBeforeExecutionAndFinalizesOnlyAfterExactAcceptance() = runBlocking {
        // Given
        val request = request()
        val removalDatabase = FakeRemovalDatabase()
        val snapshotsDatabase = FakeSnapshotsDatabase(listOf(snapshot()))
        val adapter = FakeOperationAdapter()
        val classUnderTest = repository(removalDatabase, snapshotsDatabase, adapter)

        // When
        val result = classUnderTest.remove(
            groupId = LibraryGroupId("group-1"),
            request = request,
            requestedAt = "2026-09-24T00:00:00Z",
        )

        // Then
        assertEquals(LibraryReplicaRemovalResult.Completed("remove-1"), result)
        assertEquals(listOf(request), adapter.executedRequests)
        assertEquals(LibraryReplicaRemovalPhase.Completed, removalDatabase.intents.single().phase)
        assertEquals(
            Triple(
                request.target.source,
                request.target.resource,
                DeviceStorageRef("/imports/book.epub"),
            ),
            snapshotsDatabase.retired.single(),
        )
    }

    @Test
    fun blockedTransferLeavesIntentPendingAndRecoveryReusesItsOperationId() = runBlocking {
        // Given
        val request = request()
        val removalDatabase = FakeRemovalDatabase()
        val snapshotsDatabase = FakeSnapshotsDatabase(listOf(snapshot()))
        val adapter = FakeOperationAdapter().apply { available = false }
        val classUnderTest = repository(removalDatabase, snapshotsDatabase, adapter)

        // When
        val first = classUnderTest.remove(
            groupId = LibraryGroupId("group-1"),
            request = request,
            requestedAt = "2026-09-24T00:00:00Z",
        )
        adapter.available = true
        val recovered = classUnderTest.recoverPending(LibraryProfileId("profile-a"))

        // Then
        assertIs<LibraryReplicaRemovalResult.Blocked>(first)
        assertEquals("remove-1", first.operationId)
        assertEquals(listOf(LibraryReplicaRemovalResult.Completed("remove-1")), recovered)
        assertEquals("remove-1", adapter.executedRequests.single().operationId)
        assertEquals(LibraryReplicaRemovalPhase.Completed, removalDatabase.intents.single().phase)
    }

    @Test
    fun concurrentRecoveryExecutesAPendingRemovalOnlyOnce() = runBlocking {
        // Given
        val request = request()
        val removalDatabase = FakeRemovalDatabase()
        val snapshotsDatabase = FakeSnapshotsDatabase(listOf(snapshot()))
        val executeGate = CompletableDeferred<Unit>()
        val adapter = FakeOperationAdapter(executeGate).apply { available = false }
        val classUnderTest = repository(removalDatabase, snapshotsDatabase, adapter)
        val blocked = classUnderTest.remove(
            groupId = LibraryGroupId("group-1"),
            request = request,
            requestedAt = "2026-09-24T00:00:00Z",
        )
        assertIs<LibraryReplicaRemovalResult.Blocked>(blocked)
        adapter.available = true

        // When
        val firstRecovery = async {
            classUnderTest.recoverPending(LibraryProfileId("profile-a"))
        }
        withTimeout(5_000) { adapter.executionStarted.await() }
        val secondRecovery = async {
            classUnderTest.recoverPending(LibraryProfileId("profile-a"))
        }
        yield()
        executeGate.complete(Unit)

        // Then
        assertEquals(
            listOf(LibraryReplicaRemovalResult.Completed("remove-1")),
            firstRecovery.await(),
        )
        assertEquals(emptyList(), secondRecovery.await())
        assertEquals(listOf(request), adapter.executedRequests)
    }

    @Test
    fun sharedStorageDetachPreservesBytesUntilTheFinalOwnerIsRemoved() = runBlocking {
        // Given
        val request = request()
        val target = request.target as LibraryOperationTarget.DeviceReplica
        val otherSource = source("other-source")
        val otherTarget = target.copy(
            source = otherSource,
            resource = resource(otherSource.key),
            replicaId = StorageReplicaId("replica-2"),
        )
        val otherRequest = request.copy(
            operationId = "remove-2",
            target = otherTarget,
        )
        val snapshotsDatabase = FakeSnapshotsDatabase(
            listOf(snapshot(), snapshot("other-source", target.storageRef)),
        )
        val adapter = FakeOperationAdapter()
        val removalDatabase = FakeRemovalDatabase()
        val classUnderTest = repository(removalDatabase, snapshotsDatabase, adapter)

        // When
        val firstResult = classUnderTest.remove(
            groupId = LibraryGroupId("group-1"),
            request = request,
            requestedAt = "2026-09-24T00:00:00Z",
        )
        val storageAfterFirstRemoval = snapshotsDatabase.getSnapshots(LibraryProfileId("profile-a"))
        val executionsAfterFirstRemoval = adapter.executedRequests.toList()
        val secondResult = classUnderTest.remove(
            groupId = LibraryGroupId("group-1"),
            request = otherRequest,
            requestedAt = "2026-09-24T00:01:00Z",
        )

        // Then
        assertEquals(LibraryReplicaRemovalResult.Completed("remove-1"), firstResult)
        assertNull(
            storageAfterFirstRemoval.first { snapshot ->
                snapshot.source.key == target.source.key
            }.resources.single().localStorageReference,
        )
        assertEquals(
            target.storageRef,
            storageAfterFirstRemoval.first { snapshot ->
                snapshot.source.key == otherTarget.source.key
            }.resources.single().localStorageReference,
        )
        assertEquals(
            request.copy(target = target.copy(deleteStorageBytes = false)),
            executionsAfterFirstRemoval.single(),
        )
        assertEquals(LibraryReplicaRemovalResult.Completed("remove-2"), secondResult)
        assertEquals(
            listOf(executionsAfterFirstRemoval.single(), otherRequest),
            adapter.executedRequests,
        )
        assertEquals(2, snapshotsDatabase.retired.size)
        assertTrue(
            snapshotsDatabase.getSnapshots(LibraryProfileId("profile-a")).all { snapshot ->
                snapshot.resources.single().localStorageReference == null
            },
        )
        assertTrue(removalDatabase.intents.all { intent ->
            intent.phase == LibraryReplicaRemovalPhase.Completed
        })
    }

    private fun repository(
        removalDatabase: FakeRemovalDatabase,
        snapshotsDatabase: FakeSnapshotsDatabase,
        adapter: FakeOperationAdapter,
    ) = LibraryReplicaRemovalDataRepository(
        removalDatabase = removalDatabase,
        snapshotsDatabase = snapshotsDatabase,
        adapterRegistry = object : LibraryOperationAdapterRegistry {
            override fun adapter(adapterId: LibraryAdapterId): LibraryOperationAdapter? =
                adapter.takeIf { adapter.adapterId == adapterId }

            override fun adapters(): List<LibraryOperationAdapter> = listOf(adapter)
        },
    )

    private fun request() = LibraryOperationRequest(
        operationId = "remove-1",
        operation = LibraryOperation.RemoveDeviceReplica,
        assetId = MediaAssetId("asset-1"),
        target = LibraryOperationTarget.DeviceReplica(
            source = source(),
            resource = resource(source().key),
            replicaId = StorageReplicaId("replica-1"),
            storageRef = DeviceStorageRef("/imports/book.epub"),
        ),
    )

    private fun snapshot(
        bookId: String = "book-1",
        storageRef: DeviceStorageRef = DeviceStorageRef("/imports/book.epub"),
    ): SourceBookSnapshot {
        val source = source(bookId)
        return SourceBookSnapshot(
            source = source,
            metadata = com.retro99.server.api.library.SourceBookMetadata(title = "Book"),
            resources = listOf(
                SourceMediaResource(
                    reference = resource(source.key),
                    mediaType = "ebook",
                    availability = SourceResourceAvailability.DevicePresent,
                    localStorageReference = storageRef,
                ),
            ),
            status = SourceSnapshotStatus(
                observedAt = kotlin.time.Instant.parse("2026-09-24T00:00:00Z"),
                presence = SourcePresence.Present,
                isAuthoritative = true,
            ),
        )
    }

    private fun source(bookId: String = "book-1") = SourceBookRef(
        key = SourceBookKey(
            profileId = LibraryProfileId("profile-a"),
            adapterId = LibraryAdapterId("local"),
            accountIdentity = SourceAccountIdentity.Unresolved(SourceConnectionId("local")),
            nativeBookId = NativeBookId(bookId),
        ),
        connectionId = SourceConnectionId("local"),
    )

    private fun resource(key: SourceBookKey) = SourceResourceRef(key, "resource-1")

    private class FakeOperationAdapter(
        private val executeGate: CompletableDeferred<Unit>? = null,
    ) : LibraryOperationAdapter {
        override val adapterId = LibraryAdapterId("local")
        var available = true
        val executedRequests = mutableListOf<LibraryOperationRequest>()
        val executionStarted = CompletableDeferred<Unit>()

        override suspend fun availability(
            target: LibraryOperationTarget,
        ) = listOf(
            com.retro99.server.api.library.OperationAvailability(
                operation = LibraryOperation.RemoveDeviceReplica,
                isAvailable = available,
                reason = if (available) null else "A transfer is active",
            ),
        )

        override suspend fun execute(
            request: LibraryOperationRequest,
        ): LibraryOperationResult {
            executedRequests += request
            executionStarted.complete(Unit)
            executeGate?.await()
            return LibraryOperationResult.Accepted(request.operationId)
        }
    }

    private class FakeRemovalDatabase : LibraryReplicaRemovalDatabase {
        val intents = mutableListOf<LibraryReplicaRemovalIntent>()

        override suspend fun beginRemoval(
            intent: LibraryReplicaRemovalIntent,
        ): LibraryReplicaRemovalIntent {
            val current = intents.firstOrNull { existing ->
                existing.source.key == intent.source.key &&
                    existing.resource == intent.resource &&
                    existing.storageRef == intent.storageRef &&
                    existing.phase != LibraryReplicaRemovalPhase.Completed
            }
            if (current != null) return current
            intents += intent
            return intent
        }

        override suspend fun getRemoval(
            profileId: LibraryProfileId,
            operationId: String,
        ): LibraryReplicaRemovalIntent? = intents.firstOrNull { intent ->
            intent.profileId == profileId && intent.operationId == operationId
        }

        override suspend fun getPendingRemovals(
            profileId: LibraryProfileId,
        ): List<LibraryReplicaRemovalIntent> = intents.filter { intent ->
            intent.profileId == profileId && intent.phase != LibraryReplicaRemovalPhase.Completed
        }

        override suspend fun markRemovalEffectApplied(
            profileId: LibraryProfileId,
            operationId: String,
        ) {
            update(profileId, operationId) { intent ->
                intent.copy(phase = LibraryReplicaRemovalPhase.EffectApplied)
            }
        }

        override suspend fun completeRemoval(profileId: LibraryProfileId, operationId: String) {
            update(profileId, operationId) { intent ->
                intent.copy(phase = LibraryReplicaRemovalPhase.Completed)
            }
        }

        private fun update(
            profileId: LibraryProfileId,
            operationId: String,
            transform: (LibraryReplicaRemovalIntent) -> LibraryReplicaRemovalIntent,
        ) {
            val index = intents.indexOfFirst { intent ->
                intent.profileId == profileId && intent.operationId == operationId
            }
            check(index >= 0)
            intents[index] = transform(intents[index])
        }
    }

    private class FakeSnapshotsDatabase(
        snapshots: List<SourceBookSnapshot>,
    ) : LibrarySourceSnapshotsDatabase {
        private val snapshots = snapshots.toMutableList()
        val retired = mutableListOf<Triple<SourceBookRef, SourceResourceRef, DeviceStorageRef>>()

        override fun observeSnapshotChanges(profileId: LibraryProfileId): Flow<Unit> =
            flowOf(Unit)

        override suspend fun saveSnapshot(snapshot: SourceBookSnapshot): LibraryGroupId =
            LibraryGroupId("group-1")

        override suspend fun getSnapshot(source: SourceBookKey): SourceBookSnapshot? =
            snapshots.firstOrNull { snapshot -> snapshot.source.key == source }

        override suspend fun getSnapshots(profileId: LibraryProfileId): List<SourceBookSnapshot> =
            snapshots.filter { snapshot -> snapshot.source.key.profileId == profileId }

        override suspend fun retireDeviceReplica(
            source: SourceBookRef,
            resource: SourceResourceRef,
            expectedStorageRef: DeviceStorageRef,
        ): DeviceReplicaRetirementResult {
            val snapshotIndex = snapshots.indexOfFirst { snapshot ->
                snapshot.source.key == source.key
            }
            if (snapshotIndex < 0) return DeviceReplicaRetirementResult.NotFound
            val snapshot = snapshots[snapshotIndex]
            val resourceIndex = snapshot.resources.indexOfFirst { candidate ->
                candidate.reference == resource
            }
            if (resourceIndex < 0) return DeviceReplicaRetirementResult.NotFound
            val current = snapshot.resources[resourceIndex]
            if (current.localStorageReference != expectedStorageRef) {
                if (
                    current.localStorageReference == null &&
                    current.availability == SourceResourceAvailability.Unavailable
                ) {
                    return retirementResult(
                        DeviceReplicaRetirementResult.AlreadyRemoved,
                        expectedStorageRef,
                    )
                }
                return DeviceReplicaRetirementResult.ReferenceChanged
            }
            snapshots[snapshotIndex] = snapshot.copy(
                resources = snapshot.resources.mapIndexed { index, candidate ->
                    if (index == resourceIndex) {
                        candidate.copy(
                            availability = SourceResourceAvailability.Unavailable,
                            localStorageReference = null,
                        )
                    } else {
                        candidate
                    }
                },
            )
            retired += Triple(source, resource, expectedStorageRef)
            return retirementResult(DeviceReplicaRetirementResult.Removed, expectedStorageRef)
        }

        private fun retirementResult(
            baseResult: DeviceReplicaRetirementResult,
            expectedStorageRef: DeviceStorageRef,
        ): DeviceReplicaRetirementResult {
            val hasSharedReferences = snapshots.any { snapshot ->
                snapshot.resources.any { resource ->
                    resource.localStorageReference == expectedStorageRef
                }
            }
            return when {
                !hasSharedReferences -> baseResult
                baseResult == DeviceReplicaRetirementResult.Removed ->
                    DeviceReplicaRetirementResult.RemovedWithSharedReferences
                else -> DeviceReplicaRetirementResult.AlreadyRemovedWithSharedReferences
            }
        }

        override suspend fun retireRemoteReplica(
            resource: SourceResourceRef,
            expectedRemoteRef: RemoteResourceRef,
        ): RemoteReplicaRetirementResult = error("This method is unused in the test")

        override suspend fun backfillGroups(
            profileId: LibraryProfileId,
            migrationId: String,
            startedAt: String,
            completedAt: String,
        ) = LibraryBackfillResult(0, true)
    }
}
